package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 10.23.0: Paper server updater. Reads the official PaperMC download service (fill v3), shows the
 * installed build, newer builds with their commit notes, and – only on an explicit request from the
 * app or web panel – replaces the server JAR carefully: download, SHA-256 check against PaperMC,
 * JAR check, backup of the running JAR, atomic replacement, verification and automatic rollback.
 * When anything fails, a step-by-step manual guide is returned. By default only builds of the
 * installed Minecraft version are offered; Minecraft upgrades need an explicit setting.
 */
final class ServerUpdater {
  static final String FEATURE = "server-updater-v1", VIEW = "data.server-updater", MANAGE = "server-updater.manage";
  private static final String API = "https://fill.papermc.io/v3/projects/";
  private static final long CACHE_MS = 10 * 60_000L, MAX_JAR = 256L * 1024 * 1024;

  /** Progress of the one install that may run at a time. Read by the API, written by the worker. */
  static final class Job {
    volatile String phase = "idle", message = "", error = "", version = "", actor = "";
    volatile int build;
    volatile long bytes, total, started, finished;
    volatile boolean ok;
    volatile List<String> guide = List.of();
    Map<String, Object> json() {
      return SimplexAdminSuite.appMap("phase", phase, "message", message, "error", error, "version", version, "build", build, "bytes", bytes, "total", total,
          "progress", total > 0 ? Math.min(1.0, bytes / (double) total) : 0.0, "started", started, "finished", finished, "ok", ok, "actor", actor, "guide", guide,
          "running", !Set.of("idle", "done", "failed").contains(phase));
    }
  }

  private final SimplexAdminSuite plugin;
  private final Path folder, historyFile;
  private final HttpClient http;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "SimplexAdminSuite-ServerUpdater"); t.setDaemon(true); return t; });
  private final Map<String, Object[]> cache = new HashMap<>();
  private volatile Job job = new Job();

  ServerUpdater(SimplexAdminSuite plugin) throws IOException {
    this.plugin = plugin;
    this.folder = plugin.getDataFolder().toPath().resolve("server-updates");
    this.historyFile = folder.resolve("history.yml");
    Files.createDirectories(folder);
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    cleanupParts();
  }

  void stop() { worker.shutdownNow(); }

  // ------------------------------------------------------------------ installed server

  record Installed(String project, String minecraft, int build, String commit, String brand, Path jar, String jarProblem, int java) {}

  Installed installed() {
    String project = "paper", minecraft = Bukkit.getMinecraftVersion(), commit = "", brand = Bukkit.getName();
    int build = -1;
    try {
      io.papermc.paper.ServerBuildInfo info = io.papermc.paper.ServerBuildInfo.buildInfo();
      minecraft = info.minecraftVersionId();
      build = info.buildNumber().orElse(-1);
      commit = info.gitCommit().orElse("");
      brand = info.brandName();
      String id = info.brandId().value().toLowerCase(Locale.ROOT);
      if (id.contains("folia")) project = "folia";
    } catch (LinkageError | RuntimeException older) {
      var m = java.util.regex.Pattern.compile("-(\\d+)-").matcher(Bukkit.getVersion());
      if (m.find()) build = Integer.parseInt(m.group(1));
    }
    String configured = plugin.getConfig().getString("server-updater.project", "");
    if (configured != null && configured.matches("[a-z0-9-]{2,32}")) project = configured;
    Path jar = null;
    String problem = "";
    try { jar = serverJar(); } catch (IllegalStateException e) { problem = e.getMessage(); }
    if (jar != null && !Files.isWritable(jar)) problem = "Die Server-JAR ist schreibgeschützt.";
    if (jar != null && !Files.isWritable(jar.toAbsolutePath().getParent())) problem = "Der Server-Ordner ist schreibgeschützt.";
    return new Installed(project, minecraft, build, commit, brand, jar, problem, Runtime.version().feature());
  }

  /** The launcher JAR from the JVM class path (java -jar server.jar), resolved against the working directory. */
  static Path serverJar() {
    String configured = System.getProperty("simplex.serverJar", "");
    String classPath = configured.isBlank() ? System.getProperty("java.class.path", "") : configured;
    for (String part : classPath.split(java.io.File.pathSeparator)) {
      if (!part.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
      Path path = Paths.get(part);
      if (!path.isAbsolute()) path = Paths.get(System.getProperty("user.dir", ".")).resolve(path);
      path = path.normalize();
      if (Files.isRegularFile(path) && !path.toString().contains(java.io.File.separator + "plugins" + java.io.File.separator) && !path.toString().contains(java.io.File.separator + "libraries" + java.io.File.separator)) return path;
    }
    throw new IllegalStateException("Die laufende Server-JAR wurde nicht gefunden (Klassenpfad ohne JAR-Datei).");
  }

  // ------------------------------------------------------------------ PaperMC API

  private Object fetch(String url, boolean refresh) throws Exception {
    long now = System.currentTimeMillis();
    synchronized (cache) {
      Object[] hit = cache.get(url);
      if (!refresh && hit != null && now - (long) hit[0] < CACHE_MS) return hit[1];
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
        .header("User-Agent", "SimplexAdminSuite/" + plugin.getDescription().getVersion() + " (server-updater)").header("Accept", "application/json").GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) throw new IllegalArgumentException("Bei PaperMC nicht gefunden.");
    if (response.statusCode() != 200) throw new IOException("PaperMC antwortet mit HTTP " + response.statusCode());
    Object value = DiscordBotManager.Json.parse(response.body());
    synchronized (cache) { cache.put(url, new Object[] {now, value}); }
    return value;
  }

  record Build(int id, String channel, long time, List<Map<String, Object>> commits, String name, String sha256, long size, String url) {
    Map<String, Object> json(int current, String installedVersion, String version) {
      boolean same = version.equals(installedVersion);
      return SimplexAdminSuite.appMap("id", id, "channel", channel, "time", time, "commits", commits, "name", name, "sha256", sha256, "size", size,
          "installed", same && id == current, "newer", !same || id > current);
    }
  }

  @SuppressWarnings("unchecked")
  List<Build> builds(String project, String version, boolean refresh) throws Exception {
    Object raw = fetch(API + project + "/versions/" + enc(version) + "/builds", refresh);
    List<Build> out = new ArrayList<>();
    if (!(raw instanceof List<?> list)) return out;
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> m)) continue;
      int id = m.get("id") instanceof Number n ? n.intValue() : -1;
      if (id < 0) continue;
      long time = 0;
      try { time = Instant.parse(String.valueOf(m.get("time"))).toEpochMilli(); } catch (RuntimeException ignored) { }
      List<Map<String, Object>> commits = new ArrayList<>();
      if (m.get("commits") instanceof List<?> cs) for (Object c : cs) if (c instanceof Map<?, ?> cm) {
        String message = String.valueOf(cm.get("message"));
        String summary = message.lines().findFirst().orElse("").strip();
        commits.add(SimplexAdminSuite.appMap("sha", String.valueOf(cm.get("sha")), "summary", summary.length() > 200 ? summary.substring(0, 199) + "…" : summary,
            "message", message.length() > 1500 ? message.substring(0, 1499) + "…" : message));
      }
      String name = "", sha = "", url = "";
      long size = 0;
      if (m.get("downloads") instanceof Map<?, ?> downloads && downloads.get("server:default") instanceof Map<?, ?> d) {
        name = String.valueOf(d.get("name"));
        url = String.valueOf(d.get("url"));
        size = d.get("size") instanceof Number n ? n.longValue() : 0;
        if (d.get("checksums") instanceof Map<?, ?> c) sha = String.valueOf(c.get("sha256"));
      }
      out.add(new Build(id, String.valueOf(m.get("channel")).toUpperCase(Locale.ROOT), time, commits, name, sha.toLowerCase(Locale.ROOT), size, url));
    }
    out.sort(Comparator.comparingInt(Build::id).reversed());
    return out;
  }

  int javaMinimum(String project, String version, boolean refresh) {
    try {
      Object raw = fetch(API + project + "/versions/" + enc(version), refresh);
      if (raw instanceof Map<?, ?> m && m.get("version") instanceof Map<?, ?> v && v.get("java") instanceof Map<?, ?> j && j.get("version") instanceof Map<?, ?> jv && jv.get("minimum") instanceof Number n) return n.intValue();
    } catch (Exception ignored) { }
    return 0;
  }

  /** All versions of the project, newest first (stable releases only unless experimental is enabled). */
  List<String> versions(String project, boolean refresh) throws Exception {
    Object raw = fetch(API + project, refresh);
    List<String> out = new ArrayList<>();
    if (raw instanceof Map<?, ?> m && m.get("versions") instanceof Map<?, ?> groups)
      for (Object group : groups.values()) if (group instanceof List<?> list) for (Object v : list) out.add(String.valueOf(v));
    boolean experimental = plugin.getConfig().getBoolean("server-updater.include-experimental", false);
    if (!experimental) out.removeIf(v -> v.matches(".*(-pre|-rc|snapshot|-beta|-alpha).*"));
    return out;
  }

  private Set<String> channels() {
    return plugin.getConfig().getBoolean("server-updater.include-experimental", false) ? Set.of("STABLE", "BETA", "ALPHA", "RECOMMENDED") : Set.of("STABLE", "RECOMMENDED");
  }

  private static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }

  // ------------------------------------------------------------------ status for app & web

  Map<String, Object> status(String requestedVersion, boolean refresh, boolean manage) throws Exception {
    Installed in = installed();
    boolean allowUpgrade = plugin.getConfig().getBoolean("server-updater.allow-minecraft-upgrade", false);
    String version = requestedVersion == null || requestedVersion.isBlank() ? in.minecraft : requestedVersion;
    Map<String, Object> out = SimplexAdminSuite.appMap("feature", FEATURE, "manage", manage, "job", job.json(), "history", history());
    out.put("installed", SimplexAdminSuite.appMap("project", in.project, "brand", in.brand, "minecraft", in.minecraft, "build", in.build, "commit", in.commit,
        "jar", in.jar == null ? "" : in.jar.getFileName().toString(), "jarProblem", in.jarProblem, "java", in.java, "bukkit", Bukkit.getVersion()));
    out.put("policy", SimplexAdminSuite.appMap("allowMinecraftUpgrade", allowUpgrade, "includeExperimental", plugin.getConfig().getBoolean("server-updater.include-experimental", false),
        "keepBackups", keepBackups()));
    String error = "";
    List<Map<String, Object>> rows = new ArrayList<>(), versionRows = new ArrayList<>();
    int newer = 0;
    try {
      List<Build> builds = builds(in.project, version, refresh);
      Set<String> channels = channels();
      for (Build b : builds) {
        if (!channels.contains(b.channel) && !(version.equals(in.minecraft) && b.id == in.build)) continue;
        Map<String, Object> row = b.json(in.build, in.minecraft, version);
        if (Boolean.TRUE.equals(row.get("newer")) && version.equals(in.minecraft)) newer++;
        row.put("installable", manage && in.jarProblem.isEmpty() && (version.equals(in.minecraft) || allowUpgrade) && !Boolean.TRUE.equals(row.get("installed")));
        rows.add(row);
        if (rows.size() >= 30) break;
      }
      int javaMin = javaMinimum(in.project, version, refresh);
      out.put("javaMinimum", javaMin);
      out.put("javaCompatible", javaMin <= 0 || in.java >= javaMin);
      List<String> versions = versions(in.project, refresh);
      int index = versions.indexOf(in.minecraft);
      for (int i = 0; i < versions.size() && versionRows.size() < 8; i++) {
        String v = versions.get(i);
        versionRows.add(SimplexAdminSuite.appMap("id", v, "installed", v.equals(in.minecraft), "newer", index < 0 || i < index, "selectable", v.equals(in.minecraft) || allowUpgrade));
        if (i > index && index >= 0) break;
      }
    } catch (Exception e) {
      error = "PaperMC ist gerade nicht erreichbar: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
    out.put("version", version);
    out.put("builds", rows);
    out.put("versions", versionRows);
    out.put("newerBuilds", newer);
    out.put("upToDate", error.isEmpty() && newer == 0);
    out.put("error", error);
    out.put("checkedAt", System.currentTimeMillis());
    return out;
  }

  // ------------------------------------------------------------------ install

  Map<String, Object> install(String actor, String version, int buildId) throws Exception {
    if (job.json().get("running") == Boolean.TRUE) throw new IllegalStateException("Es läuft bereits ein Server-Update.");
    Installed in = installed();
    if (!in.jarProblem.isEmpty()) throw new IllegalStateException(in.jarProblem + " Bitte manuell aktualisieren – Anleitung im Updater.");
    boolean allowUpgrade = plugin.getConfig().getBoolean("server-updater.allow-minecraft-upgrade", false);
    if (!version.equals(in.minecraft) && !allowUpgrade)
      throw new IllegalArgumentException("Minecraft-Versionswechsel sind ausgeschaltet. Erlaube sie zuerst unter Einstellungen (server-updater.allow-minecraft-upgrade).");
    Build target = builds(in.project, version, true).stream().filter(b -> b.id == buildId).findFirst().orElseThrow(() -> new IllegalArgumentException("Build " + buildId + " nicht gefunden."));
    if (version.equals(in.minecraft) && buildId == in.build) throw new IllegalArgumentException("Dieser Build ist bereits installiert.");
    int javaMin = javaMinimum(in.project, version, true);
    if (javaMin > 0 && in.java < javaMin) throw new IllegalArgumentException("Diese Version braucht Java " + javaMin + ", der Server läuft mit Java " + in.java + ". Java zuerst beim Hoster umstellen.");
    if (!target.sha256.matches("[0-9a-f]{64}") || !target.url.startsWith("https://")) throw new IllegalStateException("PaperMC liefert keine prüfbare Download-Adresse.");
    Job next = new Job();
    next.phase = "queued"; next.version = version; next.build = buildId; next.actor = actor; next.started = System.currentTimeMillis(); next.total = target.size;
    next.message = "Update wird vorbereitet …";
    next.guide = guide(in, target);
    job = next;
    worker.execute(() -> run(next, in, target));
    plugin.suiteAudit(actor, "Server-Update gestartet", in.project + " " + in.minecraft + "-" + in.build + " → " + version + "-" + buildId);
    return job.json();
  }

  private void run(Job j, Installed in, Build target) {
    Path part = folder.resolve(in.project + "-" + j.version + "-" + j.build + ".jar.part");
    Path verified = folder.resolve(in.project + "-" + j.version + "-" + j.build + ".jar");
    Path jar = in.jar;
    Path backup = null;
    try {
      // 1. Download with live progress and hashing.
      j.phase = "download"; j.message = "Lade " + target.name + " von PaperMC …";
      HttpRequest request = HttpRequest.newBuilder(URI.create(target.url)).timeout(Duration.ofMinutes(10))
          .header("User-Agent", "SimplexAdminSuite/" + plugin.getDescription().getVersion() + " (server-updater)").GET().build();
      HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) throw new IOException("Download fehlgeschlagen (HTTP " + response.statusCode() + ")");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (Thread.currentThread().isInterrupted()) throw new IOException("Abgebrochen");
          output.write(buffer, 0, read);
          digest.update(buffer, 0, read);
          j.bytes += read;
          if (j.bytes > MAX_JAR) throw new IOException("Datei ist unerwartet groß");
        }
      }
      // 2. Verify checksum and JAR structure.
      j.phase = "verify"; j.message = "Prüfe Prüfsumme und JAR …";
      String sha = HexFormat.of().formatHex(digest.digest());
      if (!sha.equals(target.sha256)) throw new IOException("Prüfsumme stimmt nicht mit PaperMC überein – Download verworfen.");
      if (target.size > 0 && Files.size(part) != target.size) throw new IOException("Dateigröße stimmt nicht.");
      verifyJar(part);
      Files.move(part, verified, StandardCopyOption.REPLACE_EXISTING);
      // 3. Back up the running JAR.
      j.phase = "backup"; j.message = "Sichere die aktuelle Server-JAR …";
      Path backups = folder.resolve("backups");
      Files.createDirectories(backups);
      backup = backups.resolve(jar.getFileName().toString().replaceAll("\\.jar$", "") + "-" + in.minecraft + "-" + (in.build < 0 ? "unbekannt" : in.build) + "-" + System.currentTimeMillis() + ".jar");
      Files.copy(jar, backup, StandardCopyOption.COPY_ATTRIBUTES);
      if (!hash(backup).equals(hash(jar))) throw new IOException("Sicherung ist nicht identisch.");
      // 4. Atomic replacement next to the server JAR, then verification.
      j.phase = "replace"; j.message = "Ersetze " + jar.getFileName() + " …";
      Path staging = jar.resolveSibling("." + jar.getFileName() + ".simplex-new");
      Files.copy(verified, staging, StandardCopyOption.REPLACE_EXISTING);
      try { Files.move(staging, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(staging, jar, StandardCopyOption.REPLACE_EXISTING); }
      if (!hash(jar).equals(target.sha256)) throw new IOException("Die neue Server-JAR konnte nicht bestätigt werden.");
      Files.deleteIfExists(verified);
      pruneBackups(backups);
      j.ok = true; j.phase = "done"; j.finished = System.currentTimeMillis();
      j.message = "Fertig: " + jar.getFileName() + " ist jetzt " + in.project + " " + j.version + " Build " + j.build + ". Aktiv nach dem nächsten Neustart.";
      record(j, in, backup, "");
      Bukkit.getScheduler().runTask(plugin, () -> plugin.suiteAudit(j.actor, "Server-Update installiert", in.project + " " + j.version + "-" + j.build + " · aktiv nach Neustart"));
    } catch (Exception error) {
      String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
      String rollback = "";
      if (backup != null && Files.isRegularFile(backup) && jar != null) {
        try {
          String current = Files.isRegularFile(jar) ? hash(jar) : "";
          if (!current.equals(hash(backup))) { Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING); rollback = " Die alte Server-JAR wurde wiederhergestellt."; }
        } catch (Exception restoreError) {
          rollback = " ACHTUNG: Die Wiederherstellung schlug fehl – Sicherung liegt unter plugins/" + plugin.getDataFolder().getName() + "/server-updates/backups/" + backup.getFileName() + ".";
        }
      }
      try { Files.deleteIfExists(part); } catch (IOException ignored) { }
      j.ok = false; j.phase = "failed"; j.finished = System.currentTimeMillis();
      j.error = reason + rollback;
      j.message = "Automatisches Update nicht möglich. Folge der Anleitung, um es selbst zu erledigen.";
      record(j, in, backup, j.error);
      Bukkit.getScheduler().runTask(plugin, () -> plugin.suiteAudit(j.actor, "Server-Update fehlgeschlagen", j.error));
    }
  }

  static void verifyJar(Path path) throws IOException {
    try (JarFile jar = new JarFile(path.toFile(), true)) {
      Manifest manifest = jar.getManifest();
      String main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
      if (main == null || main.isBlank()) throw new IOException("Die Datei ist keine startbare Server-JAR.");
      if (jar.getEntry("version.json") == null && jar.getEntry("META-INF/versions.list") == null) throw new IOException("Die Datei sieht nicht wie ein Paper-Server aus.");
    }
  }

  static String hash(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Manual fallback that always works: the exact file, checksum and where it goes. */
  List<String> guide(Installed in, Build target) {
    String jarName = in.jar == null ? "server.jar" : in.jar.getFileName().toString();
    return List.of(
        "Lade die Datei herunter: " + target.url,
        "Prüfe die SHA-256-Prüfsumme: " + target.sha256,
        "Stoppe den Server im Hoster-Panel (UltraServers → Konsole → Stop).",
        "Öffne den Dateimanager und benenne die alte " + jarName + " zur Sicherheit in " + jarName.replaceAll("\\.jar$", "") + "-alt.jar um.",
        "Lade die neue Datei hoch und benenne sie in " + jarName + " um.",
        "Starte den Server. Beim ersten Start entpackt Paper die neue Version – das dauert etwas länger.",
        "Falls dein Hoster die Server-JAR beim Start selbst lädt: im Startup-Tab die automatische Aktualisierung bzw. die Version passend einstellen.");
  }

  private int keepBackups() { return Math.max(1, Math.min(10, plugin.getConfig().getInt("server-updater.keep-backups", 3))); }

  private void pruneBackups(Path backups) {
    try (var stream = Files.list(backups)) {
      List<Path> files = stream.filter(p -> p.toString().endsWith(".jar")).sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
      for (int i = keepBackups(); i < files.size(); i++) Files.deleteIfExists(files.get(i));
    } catch (IOException ignored) { }
  }

  private void cleanupParts() {
    try (var stream = Files.list(folder)) { stream.filter(p -> p.toString().endsWith(".part")).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }); }
    catch (IOException ignored) { }
  }

  private synchronized void record(Job j, Installed in, Path backup, String error) {
    YamlConfiguration y = new YamlConfiguration();
    try { if (Files.isRegularFile(historyFile)) y.load(historyFile.toFile()); } catch (Exception ignored) { }
    List<Map<?, ?>> list = new ArrayList<>(y.getMapList("items"));
    list.add(0, SimplexAdminSuite.appMap("time", System.currentTimeMillis(), "actor", j.actor, "from", in.minecraft + "-" + in.build, "to", j.version + "-" + j.build,
        "ok", j.ok, "error", error, "backup", backup == null ? "" : backup.getFileName().toString()));
    while (list.size() > 25) list.remove(list.size() - 1);
    y.set("items", list);
    try { Files.writeString(historyFile, y.saveToString()); } catch (IOException e) { plugin.getLogger().warning("Server-Update-Verlauf: " + e.getMessage()); }
  }

  List<Map<?, ?>> history() {
    if (!Files.isRegularFile(historyFile)) return List.of();
    YamlConfiguration y = new YamlConfiguration();
    try { y.load(historyFile.toFile()); } catch (Exception e) { return List.of(); }
    return y.getMapList("items");
  }

  Map<String, Object> settings(String actor, Map<String, Object> body) {
    if (body.containsKey("allowMinecraftUpgrade")) plugin.getConfig().set("server-updater.allow-minecraft-upgrade", Boolean.TRUE.equals(body.get("allowMinecraftUpgrade")));
    if (body.containsKey("includeExperimental")) plugin.getConfig().set("server-updater.include-experimental", Boolean.TRUE.equals(body.get("includeExperimental")));
    if (body.get("keepBackups") instanceof Number n) plugin.getConfig().set("server-updater.keep-backups", Math.max(1, Math.min(10, n.intValue())));
    plugin.saveConfig();
    plugin.suiteAudit(actor, "Server-Updater eingestellt", "Versionswechsel " + plugin.getConfig().getBoolean("server-updater.allow-minecraft-upgrade") + " · Vorabversionen " + plugin.getConfig().getBoolean("server-updater.include-experimental"));
    synchronized (cache) { cache.clear(); }
    return SimplexAdminSuite.appMap("notice", "Einstellungen gespeichert.");
  }

  // ------------------------------------------------------------------ API

  boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
    if (!path.equals("/v1/server-updater")) return false;
    api.requirePermission(key, VIEW);
    boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "full");
    if (method.equals("GET")) {
      Map<String, String> query = AppApiManager.query(x.getRequestURI());
      if ("true".equals(query.get("job"))) { AppApiManager.send(x, 200, SimplexAdminSuite.appMap("job", job.json())); return true; }
      AppApiManager.send(x, 200, status(query.getOrDefault("version", ""), "true".equals(query.get("refresh")), manage));
    } else if (method.equals("POST")) {
      if (!manage) throw new AppApiManager.ApiError(403, "Server-Updates benötigen " + MANAGE + " und einen full-Schlüssel.");
      Map<String, Object> body = AppApiManager.body(x, 4096);
      if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
      String actor = api.actorName(key);
      String operation = String.valueOf(body.getOrDefault("operation", ""));
      Object result = switch (operation) {
        case "install" -> {
          int build = body.get("build") instanceof Number n ? n.intValue() : -1;
          String version = String.valueOf(body.getOrDefault("version", "")).strip();
          if (build < 0 || !version.matches("[0-9A-Za-z._-]{1,32}")) throw new IllegalArgumentException("Version oder Build fehlt");
          yield SimplexAdminSuite.appMap("job", install(actor, version, build));
        }
        case "restart" -> {
          int seconds = body.get("seconds") instanceof Number n ? n.intValue() : 60;
          boolean started = api.onMain(rev, () -> plugin.updateRestart(actor, seconds, "Server-Update"));
          if (!started) throw new IllegalStateException("Es läuft bereits ein Neustart.");
          yield SimplexAdminSuite.appMap("notice", "Neustart in " + seconds + " Sekunden geplant.");
        }
        case "settings" -> api.onMain(rev, () -> settings(actor, body));
        default -> throw new IllegalArgumentException("Unbekannte Aktion");
      };
      AppApiManager.send(x, 200, result);
    } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
    return true;
  }
}
