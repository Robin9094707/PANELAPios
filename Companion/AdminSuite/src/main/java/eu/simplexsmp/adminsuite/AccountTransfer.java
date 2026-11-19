package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static eu.simplexsmp.adminsuite.SimplexAdminSuite.appMap;

/** Shared preview and execution boundary for app, web and the Java/Bedrock admin menus. */
final class AccountTransfer {
    static final String FEATURE = "account-transfer-v1";
    private static final String ENDPOINT = "/v1/account-transfer";
    private static final String CONFIRM = "TAUSCHEN";
    private static final long VALID_MS = 90_000;
    private final SimplexAdminSuite plugin;
    private final Path root = Path.of(".").toAbsolutePath().normalize();
    private final AccountTransferFiles files;
    private final Set<UUID> held = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean executing = new AtomicBoolean();
    private volatile boolean recoveryUnavailable;

    record Options(boolean points, boolean luckPerms, boolean homes, boolean multiverse) {
        static Options from(Map<String,Object> body) {
            return new Options(Boolean.TRUE.equals(body.get("points")), Boolean.TRUE.equals(body.get("luckPerms")),
                Boolean.TRUE.equals(body.get("homes")), Boolean.TRUE.equals(body.get("multiverse")));
        }
        Map<String,Object> json() { return appMap("points", points, "luckPerms", luckPerms, "homes", homes, "multiverse", multiverse); }
    }
    record Selection(UUID old, UUID current, Options options) {}
    record Context(AccountTransferFiles.Account old, AccountTransferFiles.Account current,
                   List<File> worlds, List<Path> homes, Path multiverse, Plugin luckPerms, Long oldPoints, Long newPoints) {}
    record Draft(Selection selection, Context context, AccountTransferFiles.Plan plan,
                 AccountTransferLuckPerms.Pair rights, String revision) {}

    AccountTransfer(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        files = new AccountTransferFiles(root, plugin.getDataFolder().toPath().resolve("account-transfers"));
        try {
            for (Path directory : files.pending()) {
                Properties index = AccountTransferFiles.index(directory);
                UUID old = UUID.fromString(index.getProperty("old")), current = UUID.fromString(index.getProperty("new"));
                try {
                    if (AccountTransferFiles.needsRecovery(directory, "files")) {
                        files.restore(directory);
                        plugin.getLogger().warning("Unterbrochenen Datei-Tausch zurückgesetzt: " + directory.getFileName());
                    }
                    if (AccountTransferFiles.needsRecovery(directory, "rights"))
                        throw new IOException("LuckPerms-Zustand anhand von luckperms-before.json prüfen.");
                } catch (IOException error) {
                    held.add(old);
                    held.add(current);
                    plugin.getLogger().severe("Account-Transfer muss wiederhergestellt werden (" + directory.getFileName() + "): " + error.getMessage());
                }
            }
        } catch (IOException | RuntimeException error) {
            recoveryUnavailable = true;
            plugin.getLogger().severe("Account-Transfer-Journal konnte nicht gelesen werden: " + error.getMessage());
        }
    }

    boolean held(UUID id) { return held.contains(id); }

    private Map<String,Object> capabilities() {
        return appMap("points", plugin.pointsEconomy != null,
            "luckPerms", enabled("LuckPerms") != null,
            "homes", !homeFolders().isEmpty(),
            "multiverse", multiverseFolder() != null,
            "homesDetected", present("SimplexHomes", "Essentials"),
            "multiverseDetected", present("Multiverse-Inventories") || Files.isDirectory(root.resolve("plugins/Multiverse-Inventories")),
            "confirmation", CONFIRM, "ready", !recoveryUnavailable);
    }

    private Plugin enabled(String name) {
        Plugin addon = Bukkit.getPluginManager().getPlugin(name);
        return addon != null && addon.isEnabled() ? addon : null;
    }

    private boolean present(String... names) {
        return Arrays.stream(names).anyMatch(name -> Bukkit.getPluginManager().getPlugin(name) != null
            || Files.isDirectory(root.resolve("plugins").resolve(name)));
    }

    private List<Path> homeFolders() {
        List<Path> folders = new ArrayList<>();
        Path essentials = root.resolve("plugins/Essentials/userdata");
        if (enabled("Essentials") == null && Files.isDirectory(essentials)) folders.add(essentials);
        // SimplexHomes builds may use different storage. Only an explicitly verified, per-UUID YAML layout is accepted.
        String configured = plugin.getConfig().getString("account-transfer.simplex-homes-directory", "").trim();
        if (!configured.isEmpty() && enabled("SimplexHomes") == null) {
            Path parent = root.resolve("plugins/SimplexHomes");
            Path folder = root.resolve(configured).normalize();
            if (folder.startsWith(parent) && Files.isDirectory(folder) && !folders.contains(folder)) folders.add(folder);
        }
        return List.copyOf(folders);
    }

    private Path multiverseFolder() {
        Path folder = root.resolve("plugins/Multiverse-Inventories");
        return enabled("Multiverse-Inventories") == null && Files.isDirectory(folder) ? folder : null;
    }

    private AccountTransferFiles.Account account(UUID id) {
        var profile = plugin.expansionProfile(id);
        if (profile == null) throw new IllegalArgumentException("Spielerprofil wurde nicht gefunden.");
        return new AccountTransferFiles.Account(id, profile.displayName());
    }

    private List<Map<String,Object>> players(String input) {
        String query = input.strip().toLowerCase(Locale.ROOT);
        if (query.length() > 64) throw new IllegalArgumentException("Suchbegriff ist zu lang.");
        return plugin.expansionProfiles().stream()
            .filter(p -> query.isBlank() || p.displayName().toLowerCase(Locale.ROOT).contains(query) || p.uniqueId().toString().equals(query))
            .limit(24)
            .map(p -> appMap("id", p.uniqueId().toString(), "name", p.displayName(), "online", Bukkit.getPlayer(p.uniqueId()) != null))
            .toList();
    }

    private Context context(Selection selected, boolean ownLock) {
        if (recoveryUnavailable) throw new AppApiManager.ApiError(503, "Transfer-Journal muss zuerst geprüft werden.");
        UUID old = selected.old, current = selected.current;
        if (old.equals(current)) throw new IllegalArgumentException("Wähle zwei verschiedene Accounts.");
        if (!ownLock && (held(old) || held(current))) throw new AppApiManager.ApiError(409, "Einer der Accounts ist für einen Transfer reserviert.");
        if (Bukkit.getPlayer(old) != null || Bukkit.getPlayer(current) != null)
            throw new AppApiManager.ApiError(409, "Beide Accounts müssen ausgeloggt sein.");
        Options options = selected.options;
        var homeFolders = options.homes ? homeFolders() : List.<Path>of();
        Path multiverse = options.multiverse ? multiverseFolder() : null;
        Plugin rights = options.luckPerms ? enabled("LuckPerms") : null;
        if ((options.homes && homeFolders.isEmpty()) || (options.multiverse && multiverse == null)
            || (options.luckPerms && rights == null) || (options.points && plugin.pointsEconomy == null))
            throw new AppApiManager.ApiError(409, "Eine ausgewählte Integration ist gerade nicht verfügbar.");
        Long left = options.points ? plugin.getPointsApi().balance(old) : null;
        Long right = options.points ? plugin.getPointsApi().balance(current) : null;
        return new Context(account(old), account(current), Bukkit.getWorlds().stream().map(World::getWorldFolder).toList(),
            homeFolders, multiverse, rights, left, right);
    }

    private Draft draft(Selection selection, Context context) throws Exception {
        var permissions = context.luckPerms == null ? null : AccountTransferLuckPerms.load(context.luckPerms, selection.old, selection.current);
        var plan = files.plan(context.worlds, context.old, context.current, context.homes, context.multiverse);
        String revision = AccountTransferFiles.hash((selection.toString() + "\n" + plan.revision()
            + "\n" + context.oldPoints + "\n" + context.newPoints
            + "\n" + (permissions == null ? "-" : permissions.revision())).getBytes(StandardCharsets.UTF_8));
        return new Draft(selection, context, plan, permissions, revision);
    }

    private List<Map<String,Object>> rows(Draft draft) {
        var rows = new ArrayList<>(draft.plan.rows());
        if (draft.context.oldPoints != null)
            rows.add(appMap("area", "Simplex Points", "path", "", "old", draft.context.oldPoints + " SP", "new", draft.context.newPoints + " SP"));
        if (draft.rights != null)
            rows.add(appMap("area", "LuckPerms", "path", "", "old", draft.rights.old().primary() + " · " + draft.rights.old().nodes().size() + " Einträge",
                "new", draft.rights.current().primary() + " · " + draft.rights.current().nodes().size() + " Einträge"));
        return rows;
    }

    private Object execute(Selection selection, String revision, String actor, Runnable authority) throws Exception {
        boolean reserved = false;
        Path pendingDirectory = null;
        if (!executing.compareAndSet(false, true)) throw new AppApiManager.ApiError(409, "Ein Account-Transfer läuft bereits.");
        try {
            Context context = main(() -> {
                authority.run();
                Context current = context(selection, false);
                held.add(selection.old);
                held.add(selection.current);
                return current;
            });
            reserved = true;
            Draft actual = draft(selection, context);
            if (!actual.revision.equals(revision)) throw changed();
            UUID id = UUID.randomUUID();
            Path directory = files.backup(id, context.old, context.current, actual.plan);
            pendingDirectory = directory;
            if (actual.rights != null) AccountTransferLuckPerms.backup(actual.rights, directory);
            if (context.oldPoints != null)
                AccountTransferFiles.write(directory.resolve("points-before.txt"),
                    (context.oldPoints + "\n" + context.newPoints + "\n").getBytes(StandardCharsets.UTF_8));
            main(() -> {
                authority.run();
                Context latest = context(selection, true);
                if (!Objects.equals(latest.oldPoints, context.oldPoints) || !Objects.equals(latest.newPoints, context.newPoints)
                    || !latest.homes.equals(context.homes) || !Objects.equals(latest.multiverse, context.multiverse)
                    || latest.luckPerms != context.luckPerms) throw changed();
                files.commit(directory, actual.plan);
                return null;
            });

            var successes = new ArrayList<String>();
            var errors = new ArrayList<String>();
            successes.add("Spielstand und ausgewählte Dateien getauscht");
            if (context.oldPoints != null) {
                try {
                    main(() -> {
                        authority.run();
                        if (!plugin.pointsEconomy.store.swapBalances(selection.old, selection.current,
                                context.oldPoints, context.newPoints, "Account-Transfer " + id)) throw changed();
                        try {
                            plugin.pointsEconomy.mirror(selection.old);
                            plugin.pointsEconomy.mirror(selection.current);
                        } catch (RuntimeException error) { plugin.getLogger().warning("Punkteanzeige nach Transfer " + id + " wird später synchronisiert."); }
                        return null;
                    });
                    successes.add("Simplex Points getauscht");
                } catch (Exception error) { errors.add("Simplex Points: " + message(error)); }
            }
            if (actual.rights != null) {
                try {
                    main(() -> { authority.run(); if (enabled("LuckPerms") != context.luckPerms) throw changed(); return null; });
                    var currentRights = AccountTransferLuckPerms.load(context.luckPerms, selection.old, selection.current);
                    if (!currentRights.revision().equals(actual.rights.revision())) throw changed();
                    AccountTransferFiles.write(directory.resolve("rights.pending"), new byte[0]);
                    try { AccountTransferLuckPerms.swap(currentRights); }
                    catch (Exception error) {
                        if (error.getSuppressed().length == 0 && !(error instanceof TimeoutException))
                            AccountTransferFiles.write(directory.resolve("rights.restored"), new byte[0]);
                        throw error;
                    }
                    AccountTransferFiles.write(directory.resolve("rights.completed"), new byte[0]);
                    successes.add("LuckPerms-Gruppen und Rechte getauscht");
                } catch (Exception error) {
                    errors.add("LuckPerms: " + message(error) + " · Sicherung " + id);
                    if (AccountTransferFiles.needsRecovery(directory, "rights"))
                        plugin.getLogger().severe("LuckPerms-Rücksetzung prüfen: " + id + " " + error);
                }
            }
            boolean complete = errors.isEmpty();
            String status = complete ? "Account-Transfer abgeschlossen" : "Account-Transfer teilweise abgeschlossen";
            try {
                var report = appMap("id", id.toString(), "actor", actor, "completed", successes, "errors", errors);
                AccountTransferFiles.write(directory.resolve("result.json"), DiscordBotManager.Json.stringify(report).getBytes(StandardCharsets.UTF_8));
                main(() -> { plugin.suiteAudit(actor, status, context.old.name() + " ↔ " + context.current.name() + " · " + id); return null; });
            } catch (Exception error) { plugin.getLogger().warning("Transfer " + id + " ausgeführt, Ergebnisprotokoll nicht vollständig: " + error); }
            return appMap("ok", complete, "title", status, "completed", successes, "errors", errors, "backup", id.toString());
        } finally {
            if (reserved) {
                // Keep the lock when the file journal could not be restored.
                if (!AccountTransferFiles.needsRecovery(pendingDirectory, "files") && !AccountTransferFiles.needsRecovery(pendingDirectory, "rights")) {
                    held.remove(selection.old);
                    held.remove(selection.current);
                }
            }
            executing.set(false);
        }
    }

    private static AppApiManager.ApiError changed() {
        return new AppApiManager.ApiError(409, "Daten haben sich seit der Vorschau geändert. Bitte neu prüfen.");
    }

    private <T> T main(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        AtomicInteger state = new AtomicInteger();
        FutureTask<T> work = new FutureTask<>(() -> {
            if (!state.compareAndSet(0, 1)) throw new CancellationException();
            return task.call();
        });
        Bukkit.getScheduler().runTask(plugin, work);
        try { return work.get(12, TimeUnit.SECONDS); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        } catch (TimeoutException error) {
            // If already started, wait for the outcome rather than release a reservation during a write.
            if (state.compareAndSet(0, 2)) throw new IOException("Server hat die Anfrage nicht rechtzeitig angenommen.");
            return work.get();
        }
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    boolean route(AppApiManager api, HttpExchange exchange, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (!path.equals(ENDPOINT) && !path.startsWith(ENDPOINT + "/")) return false;
        Runnable authority = () -> {
            if (!api.currentListener(rev)) throw new AppApiManager.ApiError(503, "Panel-Verbindung wurde neu geladen.");
            if (!api.security.valid(key) || api.security.locked(key)) throw new AppApiManager.ApiError(401, "Sitzung abgelaufen.");
            if (!key.scope().equals("full") || key.id().startsWith("user:") && !api.security.users.owner(key.label()))
                throw new AppApiManager.ApiError(403, "Der Account-Transfer benötigt den Hauptadmin oder einen Vollzugriffsschlüssel.");
        };
        authority.run();
        Object reply;
        if (method.equals("GET") && path.equals(ENDPOINT)) {
            reply = api.onMain(rev, this::capabilities);
        } else if (method.equals("GET") && path.equals(ENDPOINT + "/players")) {
            String query = AppApiManager.query(exchange.getRequestURI()).getOrDefault("q", "");
            reply = appMap("items", api.onMain(rev, () -> players(query)));
        } else if (method.equals("POST") && path.equals(ENDPOINT + "/preview")) {
            if (!api.rate("account-transfer-preview:" + key.id(), 10)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
            var body = AppApiManager.body(exchange, 4096);
            Selection selection = selection(body);
            var draft = draft(selection, api.onMain(rev, () -> context(selection, false)));
            var payload = appMap("kind", FEATURE, "old", selection.old.toString(), "new", selection.current.toString(),
                "options", selection.options.json(), "revision", draft.revision);
            String ticket = api.security.prepare(key, DiscordBotManager.Json.stringify(payload), System.currentTimeMillis(), VALID_MS);
            reply = appMap("ticket", ticket, "oldName", draft.context.old.name(), "newName", draft.context.current.name(),
                "rows", rows(draft), "fileCount", draft.plan.changes().size(), "expiresIn", VALID_MS / 1000);
        } else if (method.equals("POST") && path.equals(ENDPOINT + "/execute")) {
            if (!api.rate("account-transfer-execute:" + key.id(), 6)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
            var body = AppApiManager.body(exchange, 4096);
            if (!CONFIRM.equals(AiAdmin.text(body, "confirm"))) throw new IllegalArgumentException("Zum Bestätigen TAUSCHEN eingeben.");
            var stored = api.security.consume(key, AiAdmin.text(body, "ticket"), System.currentTimeMillis());
            var payload = AiAdmin.object(DiscordBotManager.Json.parse(stored));
            if (!FEATURE.equals(AiAdmin.text(payload, "kind"))) throw new IllegalArgumentException("Bestätigung gehört zu einer anderen Aktion.");
            reply = execute(selection(payload), AiAdmin.text(payload, "revision"), api.actorName(key), authority);
        } else throw new AppApiManager.ApiError(405, "Unbekannte Transfer-Anfrage.");
        AppApiManager.send(exchange, 200, reply);
        return true;
    }

    private static Selection selection(Map<String,Object> body) {
        return new Selection(UUID.fromString(AiAdmin.text(body, "old")), UUID.fromString(AiAdmin.text(body, "new")),
            Options.from(AiAdmin.object(body.get("options"))));
    }

    boolean gameAllowed(Player player) {
        return (plugin.appApi == null || !plugin.appApi.remote(player)) && plugin.adminAccess.master(player) && plugin.adminSessionUsable(player);
    }

    void open(Player admin) {
        choose(admin, "Alter Account", "", old -> choose(admin, "Neuer Account", "",
            current -> options(admin, new Selection(old, current, Options.from(capabilities())))));
    }

    private void choose(Player admin, String title, String filter, Consumer<UUID> picked) {
        if (!gameAllowed(admin)) return;
        List<AdminUpdateMenu.Button> menu = new ArrayList<>();
        menu.add(new AdminUpdateMenu.Button("Spieler suchen", "Name oder UUID eingeben", Material.SPYGLASS,
            () -> plugin.adminUpdate.prompt(admin, title + " suchen", filter, text -> choose(admin, title, text, picked))));
        for (var player : players(filter)) {
            UUID id = UUID.fromString(player.get("id").toString());
            boolean online = Boolean.TRUE.equals(player.get("online"));
            if (online) menu.add(AdminUpdateMenu.info(player.get("name").toString(), "Online · zuerst ausloggen", Material.GRAY_DYE));
            else menu.add(new AdminUpdateMenu.Button(player.get("name").toString(), id.toString(), Material.PLAYER_HEAD, () -> picked.accept(id)));
        }
        menu.add(new AdminUpdateMenu.Button("Abbrechen", "Zur Admin-Zentrale", Material.OAK_DOOR, () -> plugin.adminUpdate.open(admin)));
        plugin.adminUpdate.show(admin, title + " wählen", menu, 0);
    }

    private void options(Player admin, Selection selected) {
        if (!gameAllowed(admin)) return;
        if (selected.old.equals(selected.current)) { plugin.menuError(admin, "Wähle zwei verschiedene Accounts."); open(admin); return; }
        var enabled = capabilities();
        var o = selected.options;
        List<AdminUpdateMenu.Button> menu = new ArrayList<>();
        menu.add(AdminUpdateMenu.info(account(selected.old).name() + " ↔ " + account(selected.current).name(),
            "Beide Spielstände werden getauscht. Das Inventar des neuen Accounts landet auf dem alten.", Material.CHEST));
        addToggle(menu, admin, selected, "Simplex Points", "points", o.points, new Options(!o.points, o.luckPerms, o.homes, o.multiverse), enabled);
        addToggle(menu, admin, selected, "LuckPerms", "luckPerms", o.luckPerms, new Options(o.points, !o.luckPerms, o.homes, o.multiverse), enabled);
        addToggle(menu, admin, selected, "Homes", "homes", o.homes, new Options(o.points, o.luckPerms, !o.homes, o.multiverse), enabled);
        addToggle(menu, admin, selected, "Multiverse-Inventories", "multiverse", o.multiverse, new Options(o.points, o.luckPerms, o.homes, !o.multiverse), enabled);
        menu.add(new AdminUpdateMenu.Button("Vorschau erstellen", "Bestand auf beiden Seiten prüfen", Material.WRITABLE_BOOK,
            () -> async(admin, () -> draft(selected, main(() -> context(selected, false))), plan -> review(admin, plan))));
        menu.add(new AdminUpdateMenu.Button("Auswahl ändern", "Accounts neu wählen", Material.OAK_DOOR, () -> open(admin)));
        plugin.adminUpdate.show(admin, "Account-Transfer", menu, 0);
    }

    private void addToggle(List<AdminUpdateMenu.Button> menu, Player admin, Selection selected, String title, String key,
                           boolean on, Options updated, Map<String,Object> allowed) {
        if (Boolean.TRUE.equals(allowed.get(key)))
            menu.add(new AdminUpdateMenu.Button(title + (on ? " · an" : " · aus"), "Für diesen Tausch umschalten", Material.LEVER,
                () -> options(admin, new Selection(selected.old, selected.current, updated))));
        else menu.add(AdminUpdateMenu.info(title, "Auf diesem Server aktuell nicht verfügbar", Material.GRAY_DYE));
    }

    private void review(Player admin, Draft draft) {
        long expires = System.currentTimeMillis() + VALID_MS;
        var used = new AtomicBoolean();
        List<AdminUpdateMenu.Button> menu = new ArrayList<>();
        menu.add(AdminUpdateMenu.info(draft.context.old.name() + " ↔ " + draft.context.current.name(),
            "Links liegt der bisherige Stand des alten Accounts. Beim Tausch werden beide Seiten vertauscht.", Material.CHEST));
        for (var row : rows(draft)) menu.add(AdminUpdateMenu.info(row.get("area").toString(),
            row.get("path") + "\nAlt: " + row.get("old") + "\nNeu: " + row.get("new"), Material.PAPER));
        menu.add(new AdminUpdateMenu.Button("Tausch bestätigen", "TAUSCHEN eintippen · Vorschau 90 Sekunden gültig", Material.EMERALD,
            () -> plugin.adminUpdate.prompt(admin, "Account-Transfer bestätigen", "", input -> {
                if (!gameAllowed(admin)) return;
                if (!CONFIRM.equals(input.strip())) { plugin.menuError(admin, "Zum Bestätigen TAUSCHEN eingeben."); return; }
                if (System.currentTimeMillis() > expires || !used.compareAndSet(false, true)) {
                    plugin.menuError(admin, "Bitte eine neue Vorschau erstellen."); options(admin, draft.selection); return;
                }
                async(admin, () -> execute(draft.selection, draft.revision, admin.getName(), () -> {
                    if (!gameAllowed(admin)) throw new AppApiManager.ApiError(403, "Hauptadmin-Sitzung abgelaufen.");
                }), reply -> {
                    Map<String,Object> result = AiAdmin.object(reply);
                    String details = result.get("completed") + "\n" + result.get("errors") + "\nSicherung: " + result.get("backup");
                    plugin.adminUpdate.detail(admin, result.get("title").toString(), details, () -> open(admin));
                });
            })));
        menu.add(new AdminUpdateMenu.Button("Zurück", "Optionen ändern", Material.OAK_DOOR, () -> options(admin, draft.selection)));
        plugin.adminUpdate.show(admin, "Tauschvorschau", menu, 0);
    }

    private <T> void async(Player admin, Callable<T> work, Consumer<T> show) {
        if (!gameAllowed(admin)) return;
        plugin.menuNotice(admin, "Account-Transfer wird bearbeitet …");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T result = work.call();
                Bukkit.getScheduler().runTask(plugin, () -> { if (gameAllowed(admin)) show.accept(result); });
            } catch (Exception error) {
                Bukkit.getScheduler().runTask(plugin, () -> { if (gameAllowed(admin)) plugin.menuError(admin, message(error)); });
            }
        });
    }
}
