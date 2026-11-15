package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 10.20.0: once LiteBans is switched off, the familiar commands (/ban, /tempban, /unban, /mute, /kick, /warn …)
 * are answered by the Suite. Each label simply forwards to the matching {@code /sas} sub command, so permissions,
 * role limits, confirmations and the bilingual player texts stay exactly as before. Labels that belong to another
 * enabled plugin (for example Essentials) are left alone.
 */
final class BanCommands {
    static final Map<String, String> LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ban", "ban"); m.put("tempban", "tempban"); m.put("unban", "unban"); m.put("pardon", "unban");
        m.put("mute", "mute"); m.put("tempmute", "tempmute"); m.put("unmute", "unmute");
        m.put("kick", "kick"); m.put("warn", "warn");
        m.put("ipban", "ban"); m.put("tempipban", "tempban"); m.put("banip", "ban");
        m.put("banlist", "banlist");
        LABELS = Collections.unmodifiableMap(m);
    }

    private BanCommands() { }

    static int takeOver(SimplexAdminSuite plugin) {
        PluginCommand sas = plugin.getCommand("sas");
        if (sas == null) return 0;
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        int taken = 0;
        for (var e : LABELS.entrySet()) {
            String label = e.getKey();
            if (label.equals("pardon") && !plugin.getConfig().getBoolean("litebans-migration.take-over-pardon", false)) continue;
            Command current = known.get(label);
            if (!replaceable(current)) continue;
            Forward command = new Forward(plugin, sas, label, e.getValue());
            known.put(label, command);
            known.put("simplexadminsuite:" + label, command);
            taken++;
        }
        known.entrySet().removeIf(entry -> entry.getKey().startsWith("litebans:") && entry.getValue() instanceof PluginCommand pc && !pc.getPlugin().isEnabled());
        try { Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer()); } catch (Throwable ignored) { }
        for (Player p : Bukkit.getOnlinePlayers()) try { p.updateCommands(); } catch (Throwable ignored) { }
        return taken;
    }

    /** Free label, LiteBans (enabled or not), a disabled plugin or vanilla – never another active plugin's command. */
    static boolean replaceable(Command current) {
        if (current == null) return true;
        if (current instanceof Forward) return true;
        if (current instanceof PluginCommand pc) return pc.getPlugin().getName().equalsIgnoreCase("LiteBans") || !pc.getPlugin().isEnabled();
        String type = current.getClass().getName();
        return type.contains("VanillaCommandWrapper") || type.contains("BukkitCommandWrapper") && type.contains("minecraft");
    }

    static final class Forward extends Command {
        private final SimplexAdminSuite plugin;
        private final PluginCommand sas;
        private final String sub;
        Forward(SimplexAdminSuite plugin, PluginCommand sas, String label, String sub) {
            super(label, "Simplex Admin Suite: " + sub + " (ersetzt LiteBans)", "/" + label + (sub.equals("banlist") ? "" : " <Spieler> …"), List.of());
            this.plugin = plugin; this.sas = sas; this.sub = sub;
        }
        @Override public boolean execute(CommandSender sender, String label, String[] args) {
            if (!plugin.isEnabled()) return false;
            if (sub.equals("banlist")) {
                if (!plugin.has(sender, "simplexadminsuite.bans")) { sender.sendMessage(plugin.prefix() + "Keine Berechtigung."); return true; }
                Map<String, Object> data = plugin.banCenter == null ? Map.of() : plugin.banCenter.overview("all", "");
                Object kpis = data.get("kpis");
                sender.sendMessage(plugin.prefix() + "Aktive Banns: " + (kpis instanceof Map<?, ?> k ? k.get("cases") + " Fälle · " + k.get("main") + " Hauptbanns · " + k.get("alts") + " Alt-Konten" : "–")
                        + ". Übersicht: App/Webpanel → Bann-Zentrale, Status eines Spielers: /checkban <Spieler>");
                return true;
            }
            String[] forwarded = new String[args.length + 1];
            forwarded[0] = sub; System.arraycopy(args, 0, forwarded, 1, args.length);
            return plugin.onCommand(sender, sas, "sas", forwarded);
        }
        @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (sub.equals("banlist")) return List.of();
            String[] forwarded = new String[args.length + 1];
            forwarded[0] = sub; System.arraycopy(args, 0, forwarded, 1, args.length);
            List<String> out = plugin.onTabComplete(sender, sas, "sas", forwarded);
            return out == null ? List.of() : out;
        }
    }
}
