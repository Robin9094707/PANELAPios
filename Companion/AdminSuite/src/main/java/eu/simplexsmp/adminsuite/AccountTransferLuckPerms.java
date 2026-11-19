package eu.simplexsmp.adminsuite;

import org.bukkit.plugin.Plugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static eu.simplexsmp.adminsuite.LuckPermsAdmin.call;
import static eu.simplexsmp.adminsuite.SimplexAdminSuite.appMap;

/** Uses LuckPerms' normal user node map, including negative nodes, contexts and expiry. */
final class AccountTransferLuckPerms {
    record User(Object handle, String primary, List<?> nodes, List<Map<String,Object>> facts) {
        Map<String,Object> snapshot() { return appMap("primaryGroup", primary, "nodes", facts); }
    }
    record Pair(Object manager, User old, User current) {
        String revision() { return AccountTransferFiles.hash(json(List.of(old.snapshot(), current.snapshot()))); }
    }

    static Pair load(Plugin plugin, UUID old, UUID current) throws Exception {
        Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, plugin.getClass().getClassLoader());
        Object manager = call(provider.getMethod("get").invoke(null), "getUserManager");
        // Loading from the provider also validates that its storage is reachable before any write.
        Object left = await(call(manager, "loadUser", old));
        Object right = await(call(manager, "loadUser", current));
        return new Pair(manager, user(left), user(right));
    }

    static User user(Object user) throws Exception {
        var data = call(user, "data");
        List<?> nodes = List.copyOf((Collection<?>) call(data, "toCollection"));
        List<Map<String,Object>> facts = new ArrayList<>();
        for (Object node : nodes) {
            var ctx = new ArrayList<String>();
            for (Object context : (Set<?>) call(call(node, "getContexts"), "toSet")) {
                ctx.add(DiscordBotManager.Json.stringify(List.of(call(context, "getKey"), call(context, "getValue"))));
            }
            Collections.sort(ctx);
            Object expiry = call(node, "getExpiry");
            facts.add(appMap("key", call(node, "getKey"), "value", call(node, "getValue"),
                "expiresAt", expiry instanceof Instant time ? time.toString() : "", "contexts", ctx));
        }
        facts.sort(Comparator.comparing(DiscordBotManager.Json::stringify));
        return new User(user, String.valueOf(call(user, "getPrimaryGroup")), nodes, List.copyOf(facts));
    }

    static void backup(Pair pair, Path dir) throws Exception {
        AccountTransferFiles.write(dir.resolve("luckperms-before.json"), json(appMap(
            "old", pair.old.snapshot(), "new", pair.current.snapshot())));
    }

    static void swap(Pair pair) throws Exception {
        try {
            replace(pair.old.handle, pair.current);
            replace(pair.current.handle, pair.old);
            await(call(pair.manager, "saveUser", pair.old.handle));
            await(call(pair.manager, "saveUser", pair.current.handle));
        } catch (Exception failure) {
            restore(pair, pair.old, failure);
            restore(pair, pair.current, failure);
            throw failure;
        }
    }

    private static void restore(Pair pair, User original, Exception failure) {
        try {
            replace(original.handle, original);
            await(call(pair.manager, "saveUser", original.handle));
        } catch (Exception error) { failure.addSuppressed(error); }
    }

    static void replace(Object target, User values) throws Exception {
        Object map = call(target, "data");
        call(map, "clear");
        for (Object node : values.nodes) call(map, "add", node);
        if (!values.primary.equals(call(target, "getPrimaryGroup")))
            call(target, "setPrimaryGroup", values.primary);
        if (!values.primary.equals(call(target, "getPrimaryGroup")))
            throw new IllegalStateException("LuckPerms hat die Hauptgruppe nicht übernommen.");
    }

    private static Object await(Object future) throws Exception { return ((Future<?>) future).get(8, TimeUnit.SECONDS); }

    private static byte[] json(Object value) { return DiscordBotManager.Json.stringify(value).getBytes(StandardCharsets.UTF_8); }
}
