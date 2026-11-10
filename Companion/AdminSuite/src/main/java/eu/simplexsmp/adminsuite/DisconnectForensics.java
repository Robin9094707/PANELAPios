package eu.simplexsmp.adminsuite;

import io.netty.channel.Channel;
import java.io.File;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 10.16.0 Disconnect-Forensik: everything around a disconnect that the server side can observe without reading packet
 * contents or storing addresses.
 * <ul>
 * <li>Kernel TCP statistics (RTT, retransmissions, loss, unacknowledged segments) for native epoll connections.</li>
 * <li>Who closed the connection and – when the close started on the event loop – the calling code.</li>
 * <li>A pseudonymous network group (salted hash of the /24 or /48 prefix) to see whether one provider is affected.
 *     The address itself is never stored.</li>
 * <li>Client details: brand, exact protocol version through ViaVersion when installed, view distance, locale.</li>
 * <li>Network relevant server settings and plugins that touch connections, with optional plugins worth adding.</li>
 * </ul>
 */
final class DisconnectForensics {
    static final String FEATURE = "disconnect-forensics-v1";
    private static volatile byte[] salt;
    private static volatile Map<String, Object> environmentCache = Map.of();
    private static volatile long environmentAt;

    private DisconnectForensics() {}

    // ------------------------------------------------------------------ netty helpers (event loop)

    static String transport(Channel channel) {
        String name = channel == null ? "" : channel.getClass().getSimpleName();
        if (name.contains("Epoll")) return "epoll";
        if (name.contains("IOUring") || name.contains("IoUring")) return "io_uring";
        if (name.contains("KQueue")) return "kqueue";
        if (name.contains("Nio")) return "nio";
        if (name.contains("Local")) return "local";
        return name.isBlank() ? "" : name;
    }

    /** Kernel TCP statistics through netty's EpollSocketChannel#tcpInfo(); empty for other transports. */
    static Map<String, Object> tcpInfo(Channel channel) {
        if (channel == null || !channel.getClass().getSimpleName().contains("Epoll")) return Map.of();
        try {
            Method method = channel.getClass().getMethod("tcpInfo");
            Object info = method.invoke(channel);
            if (info == null) return Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : List.of("rtt", "rttvar", "retransmits", "totalRetrans", "lost", "unacked", "sndCwnd", "lastDataRecv", "lastAckRecv", "pmtu", "rcvRtt", "retrans")) {
                try {
                    Object value = info.getClass().getMethod(key).invoke(info);
                    if (value instanceof Number n) out.put(key, key.equals("rtt") || key.equals("rttvar") || key.equals("rcvRtt") ? n.longValue() / 1000 : n.longValue());
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return Map.of(); }
    }

    /** Readable exception text without addresses. */
    static String describe(Throwable cause) {
        if (cause == null) return "";
        StringBuilder text = new StringBuilder(cause.getClass().getSimpleName());
        if (cause.getMessage() != null) text.append(": ").append(cause.getMessage());
        Throwable root = cause.getCause();
        for (int depth = 0; root != null && depth < 3; depth++, root = root.getCause())
            text.append(" ← ").append(root.getClass().getSimpleName()).append(root.getMessage() == null ? "" : ": " + root.getMessage());
        return SparkProfile.clip(PacketTrace.redact(text.toString()), 240);
    }

    private static final List<String> FRAMEWORK = List.of("java.", "javax.", "jdk.", "sun.", "io.netty.", "net.minecraft.", "org.bukkit.", "io.papermc.",
        "com.destroystokyo.", "org.spigotmc.", "ca.spottedleaf.", "com.mojang.", "eu.simplexsmp.adminsuite.SuitePacketGuard", "eu.simplexsmp.adminsuite.DisconnectForensics");

    /** Interesting frames of the code that closed a connection: plugin frames first, then the Minecraft call site. */
    static List<String> callers(StackTraceElement[] stack) {
        List<String> plugins = new ArrayList<>(), server = new ArrayList<>();
        for (StackTraceElement frame : stack) {
            String type = frame.getClassName();
            if (type.startsWith("java.lang.Thread") || type.startsWith("io.netty.") || type.contains("SuitePacketGuard") || type.contains("DisconnectForensics")) continue;
            String text = type + "." + frame.getMethodName() + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
            if (FRAMEWORK.stream().noneMatch(type::startsWith)) { if (plugins.size() < 4) plugins.add(text); }
            else if (type.startsWith("net.minecraft.") || type.startsWith("io.papermc.") || type.startsWith("org.bukkit.craftbukkit.")) { if (server.size() < 4) server.add(text); }
        }
        List<String> out = new ArrayList<>(plugins);
        out.addAll(server);
        return out;
    }

    /** Plugin owning the first non-server frame, by the package of its main class. */
    static String culprit(List<String> frames, Map<String, String> packages) {
        for (String frame : frames)
            for (var entry : packages.entrySet()) if (frame.startsWith(entry.getKey())) return entry.getValue();
        return "";
    }

    static Map<String, String> pluginPackages() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                String main = plugin.getDescription().getMain();
                int dot = main.lastIndexOf('.');
                if (dot > 0) out.put(main.substring(0, dot + 1), plugin.getName());
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** Salted 8-character hash of the network prefix; empty for unknown addresses. Never reversible to an address. */
    static String networkGroup(SimplexAdminSuite plugin, SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet) || inet.getAddress() == null) return "";
        InetAddress ip = inet.getAddress();
        if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress()) return "lokal/proxy";
        byte[] raw = ip.getAddress();
        byte[] prefix = Arrays.copyOf(raw, raw.length == 4 ? 3 : 6);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt(plugin));
            digest.update(prefix);
            return HexFormat.of().formatHex(digest.digest()).substring(0, 8);
        } catch (Exception e) { return ""; }
    }

    private static byte[] salt(SimplexAdminSuite plugin) {
        byte[] value = salt;
        if (value != null) return value;
        synchronized (DisconnectForensics.class) {
            if (salt != null) return salt;
            Path file = plugin.getDataFolder().toPath().resolve("diagnostics").resolve("network-salt.bin");
            try {
                if (Files.isRegularFile(file) && Files.size(file) == 32) value = Files.readAllBytes(file);
                else {
                    value = new byte[32];
                    new SecureRandom().nextBytes(value);
                    Files.createDirectories(file.getParent());
                    Files.write(file, value);
                }
            } catch (Exception e) { value = new byte[32]; new SecureRandom().nextBytes(value); }
            salt = value;
            return value;
        }
    }

    // ------------------------------------------------------------------ client details (main thread)

    /** Brand, exact protocol version (ViaVersion), view distance and locale of a player who is still known to Bukkit. */
    static Map<String, Object> client(SimplexAdminSuite plugin, Player player) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (player == null) return out;
        String brand = ClientProbeDiagnostics.brand(player);
        out.put("brand", SparkProfile.clip(brand, 60));
        out.put("brandLabel", brand.isBlank() ? "" : ClientBrands.label(brand, true));
        int protocol = viaProtocol(player.getUniqueId());
        if (protocol > 0) { out.put("protocol", protocol); out.put("version", protocolName(protocol)); }
        try { out.put("viewDistance", player.getClientViewDistance()); } catch (Throwable ignored) { }
        try { out.put("locale", String.valueOf(player.locale())); } catch (Throwable ignored) { }
        try { out.put("nearbyEntities", player.getNearbyEntities(32, 32, 32).size()); } catch (Throwable ignored) { }
        return out;
    }

    /** ViaVersion through reflection: Via.getAPI().getPlayerVersion(uuid). -1 without ViaVersion. */
    static int viaProtocol(UUID player) {
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) return -1;
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Object value = api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, player);
            return value instanceof Number n ? n.intValue() : -1;
        } catch (Throwable unavailable) { return -1; }
    }

    static String protocolName(int protocol) {
        return switch (protocol) {
            case 767 -> "1.21–1.21.1"; case 768 -> "1.21.2–1.21.3"; case 769 -> "1.21.4"; case 770 -> "1.21.5"; case 771 -> "1.21.6";
            case 772 -> "1.21.7–1.21.8"; case 773 -> "1.21.9–1.21.10"; case 774 -> "1.21.11"; case 766 -> "1.20.5–1.20.6"; case 765 -> "1.20.3–1.20.4";
            case 764 -> "1.20.2"; case 763 -> "1.20–1.20.1";
            default -> "Protokoll " + protocol;
        };
    }

    // ------------------------------------------------------------------ server environment (main thread, cached)

    private static final Map<String, String[]> PLUGINS = new LinkedHashMap<>();
    static {
        String[][] rows = {
            {"ViaVersion", "protocol", "Übersetzt Client-Versionen. Nach Updates häufige Ursache für Paketfehler – immer aktuell halten."},
            {"ViaBackwards", "protocol", "Erlaubt ältere Clients; Fehler betreffen nur Spieler mit älteren Versionen."},
            {"ViaRewind", "protocol", "Sehr alte Clients (1.7/1.8) – eigene Paketfehler möglich."},
            {"ProtocolLib", "packets", "Verändert Pakete für andere Plugins. Bei Paketfehlern zuerst aktualisieren."},
            {"packetevents", "packets", "Paket-Bibliothek für andere Plugins (z. B. Anticheats)."},
            {"Geyser-Spigot", "bedrock", "Bedrock-Übersetzung über UDP – Java-Verbindungen laufen getrennt davon."},
            {"floodgate", "bedrock", "Bedrock-Anmeldung ohne Java-Konto."},
            {"TCPShield", "proxy", "DDoS-Schutz/Proxy-Protokoll: echte Spieler-Netze werden sichtbar."},
            {"HAProxyDetector", "proxy", "Proxy-Protokoll-Erkennung."},
            {"BungeeGuard", "proxy", "Server läuft hinter einem Proxy."},
            {"Grim", "anticheat", "Anticheat – kann bei Lag gesammelt kicken."}, {"GrimAC", "anticheat", "Anticheat – kann bei Lag gesammelt kicken."},
            {"Vulcan", "anticheat", "Anticheat – kann bei Lag gesammelt kicken."}, {"Matrix", "anticheat", "Anticheat."}, {"Spartan", "anticheat", "Anticheat."},
            {"Themis", "anticheat", "Anticheat."}, {"Intave", "anticheat", "Anticheat."}, {"Karhu", "anticheat", "Anticheat."}, {"Polar", "anticheat", "Anticheat."},
            {"NoCheatPlus", "anticheat", "Anticheat."}, {"AAC", "anticheat", "Anticheat."},
            {"ExploitFixer", "guard", "Netzwerk-/Exploit-Schutz: trennt Verbindungen bei verdächtigen Paketen."},
            {"AntiBot", "guard", "Bot-Schutz – kann Verbindungen trennen."}, {"LPX", "guard", "Paketschutz – kann Verbindungen trennen."},
            {"AntiVPN", "guard", "VPN-Sperre beim Beitritt."}, {"spark", "profiler", "Profiler: zeigt Tick-, GC- und Plugin-Last im Moment einer Welle."},
            {"Chunky", "world", "Vorgenerierung – entlastet beim Erkunden."}};
        for (String[] r : rows) PLUGINS.put(r[0].toLowerCase(Locale.ROOT), new String[]{r[0], r[1], r[2]});
    }

    static Map<String, Object> environment(SimplexAdminSuite plugin) {
        long now = System.currentTimeMillis();
        if (now - environmentAt < 300_000 && !environmentCache.isEmpty()) return environmentCache;
        File root = plugin.getDataFolder().getAbsoluteFile().getParentFile() == null ? new File(".") : plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
        if (root == null) root = new File(".");
        List<Map<String, Object>> checks = new ArrayList<>();
        Properties props = new Properties();
        File properties = new File(root, "server.properties");
        if (properties.isFile()) try (Reader reader = Files.newBufferedReader(properties.toPath(), StandardCharsets.ISO_8859_1)) { props.load(reader); } catch (Exception ignored) { }
        int compression = intOf(props.getProperty("network-compression-threshold"), 256);
        checks.add(check("Kompression ab", compression < 0 ? "warn" : compression < 64 ? "info" : "ok", compression < 0 ? "aus" : compression + " Bytes",
            compression < 0 ? "Ohne Kompression braucht jeder Spieler deutlich mehr Bandbreite – bei schwachen Leitungen mehr Abbrüche." : compression < 64 ? "Sehr niedrige Schwelle kostet CPU." : "Standard."));
        int rate = intOf(props.getProperty("rate-limit"), 0);
        checks.add(check("Paket-Ratenlimit (server.properties)", rate > 0 ? "warn" : "ok", rate > 0 ? rate + " Pakete/s" : "aus",
            rate > 0 ? "Spieler mit vielen Paketen (Mods, schnelle Klicks) werden getrennt." : "Kein zusätzliches Limit."));
        boolean nativeTransport = !"false".equalsIgnoreCase(props.getProperty("use-native-transport", "true"));
        checks.add(check("Native Netzwerkschicht (epoll)", nativeTransport ? "ok" : "info", nativeTransport ? "an" : "aus",
            nativeTransport ? "TCP-Kernelwerte (RTT, Neuübertragungen) stehen der Forensik zur Verfügung." : "Ohne epoll fehlen TCP-Kernelwerte in der Analyse."));
        if ("true".equalsIgnoreCase(props.getProperty("prevent-proxy-connections", "false")))
            checks.add(check("prevent-proxy-connections", "info", "an", "Spieler hinter VPN/Proxy werden beim Login abgewiesen."));
        File spigot = new File(root, "spigot.yml");
        boolean bungee = false;
        if (spigot.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(spigot);
            int timeout = yaml.getInt("settings.timeout-time", 60);
            bungee = yaml.getBoolean("settings.bungeecord", false);
            checks.add(check("Watchdog-Zeit (spigot.yml)", timeout < 30 ? "warn" : "ok", timeout + " s", timeout < 30 ? "Sehr kurz – bei kurzen Hängern startet der Server neu." : "Unauffällig."));
        }
        File paper = new File(new File(root, "config"), "paper-global.yml");
        boolean velocity = false, proxyProtocol = false;
        if (paper.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(paper);
            velocity = yaml.getBoolean("proxies.velocity.enabled", false);
            proxyProtocol = yaml.getBoolean("proxies.proxy-protocol", false);
            double packetRate = yaml.getDouble("packet-limiter.all-packets.max-packet-rate", 500);
            checks.add(check("Paper Paket-Limiter", packetRate > 0 && packetRate < 300 ? "warn" : "ok", packetRate <= 0 ? "aus" : Math.round(packetRate) + " Pakete/s",
                packetRate > 0 && packetRate < 300 ? "Niedrig – Clients mit Mods können gekickt werden." : "Standard."));
        }
        String proxy = velocity ? "Velocity" : bungee ? "BungeeCord" : proxyProtocol ? "Proxy-Protokoll (z. B. TCPShield/HAProxy)" : "kein Proxy";
        checks.add(check("Proxy", velocity || bungee || proxyProtocol ? "info" : "ok", proxy,
            velocity || bungee ? "Trennungen können auch vom Proxy ausgehen – dessen Log zur gleichen Uhrzeit prüfen." : proxyProtocol ? "Echte Netze sichtbar." : "Spieler verbinden sich direkt."));
        List<Map<String, Object>> plugins = new ArrayList<>();
        Set<String> installed = new HashSet<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            String key = p.getName().toLowerCase(Locale.ROOT);
            installed.add(key);
            String[] known = PLUGINS.get(key);
            if (known == null) continue;
            plugins.add(SimplexAdminSuite.appMap("name", p.getName(), "version", p.getDescription().getVersion(), "enabled", p.isEnabled(), "role", known[1], "note", known[2]));
        }
        List<Map<String, Object>> recommended = new ArrayList<>();
        if (!installed.contains("viaversion")) recommended.add(SimplexAdminSuite.appMap("name", "ViaVersion", "why",
            "Optional: liefert die exakte Client-Version jedes Java-Spielers. Die Analyse erkennt dann, ob nur eine bestimmte Version getrennt wird."));
        if (!installed.contains("spark")) recommended.add(SimplexAdminSuite.appMap("name", "spark", "why",
            "Empfohlen: Tick-, GC- und Plugin-Profile im Moment einer Welle. Die Suite importiert und analysiert die Profile automatisch."));
        if (!proxyProtocol && !velocity && !bungee) recommended.add(SimplexAdminSuite.appMap("name", "Proxy-Protokoll beim DDoS-Schutz", "why",
            "Nur falls euer Hoster einen DDoS-Filter/Proxy vorschaltet: mit aktiviertem Proxy-Protokoll (z. B. TCPShield-Plugin) sieht die Suite echte Netzbereiche statt der Filter-Adresse."));
        String transport = "";
        Map<String, Object> out = SimplexAdminSuite.appMap("at", now, "server", Bukkit.getName() + " " + Bukkit.getVersion(), "proxy", proxy, "checks", checks,
            "plugins", plugins, "recommended", recommended, "transport", transport, "nativeTransport", nativeTransport, "compression", compression);
        environmentCache = out;
        environmentAt = now;
        return out;
    }

    private static Map<String, Object> check(String title, String status, String value, String hint) {
        return SimplexAdminSuite.appMap("title", title, "status", status, "value", value, "hint", hint);
    }

    private static int intOf(String value, int fallback) { try { return Integer.parseInt(value.strip()); } catch (RuntimeException e) { return fallback; } }

    // ------------------------------------------------------------------ live network (main thread)

    /** Every online player's connection right now – used during a wave to see who is about to drop. */
    static Map<String, Object> live(SimplexAdminSuite plugin) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> groups = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> trace = plugin.qualityOfLife == null ? Map.of() : plugin.qualityOfLife.packets.trace(p.getUniqueId(), now);
            String group = AiAdmin.text(trace, "networkGroup");
            if (!group.isBlank()) groups.merge(group, 1, Integer::sum);
            Map<?, ?> tcp = trace.get("tcp") instanceof Map<?, ?> m ? m : Map.of();
            long gap = AiAdmin.number(trace.get("lastInboundAgeMs"));
            long rtt = AiAdmin.number(tcp.get("rtt"));
            int ping = ConnectionDiagnostics.ping(p);
            String state = gap >= 5000 ? "stall" : !Boolean.TRUE.equals(trace.getOrDefault("writable", true)) ? "congested"
                : AiAdmin.number(tcp.get("retransmits")) > 0 || ping > 400 ? "unstable" : "ok";
            rows.add(SimplexAdminSuite.appMap("name", p.getName(), "uuid", p.getUniqueId().toString(), "platform", plugin.isBedrockClient(p) ? "bedrock" : "java",
                "ping", ping, "rtt", rtt, "retrans", AiAdmin.number(tcp.get("totalRetrans")), "lost", AiAdmin.number(tcp.get("lost")), "unacked", AiAdmin.number(tcp.get("unacked")),
                "rateInBps", trace.getOrDefault("rateInBps", 0), "rateOutBps", trace.getOrDefault("rateOutBps", 0), "lastInboundAgeMs", gap,
                "pendingWriteBytes", trace.getOrDefault("pendingWriteBytes", 0), "unwritableEvents", trace.getOrDefault("unwritableEvents", 0),
                "transport", trace.getOrDefault("transport", ""), "networkGroup", group, "connectionAgeMs", trace.getOrDefault("connectionAgeMs", 0),
                "client", client(plugin, p), "state", state, "traced", !trace.isEmpty()));
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> !"ok".equals(r.get("state")) ? 0 : 1).thenComparing(r -> String.valueOf(r.get("name")), String.CASE_INSENSITIVE_ORDER));
        for (Map<String, Object> row : rows) row.put("groupSize", groups.getOrDefault(String.valueOf(row.get("networkGroup")), 0));
        long stalled = rows.stream().filter(r -> "stall".equals(r.get("state"))).count();
        return SimplexAdminSuite.appMap("at", now, "items", rows, "stalled", stalled, "online", rows.size(), "environment", environment(plugin),
            "hint", stalled >= 2 ? stalled + " Verbindungen senden seit über 5 s nichts – eine Welle kündigt sich an oder der Server hängt." : "");
    }
}
