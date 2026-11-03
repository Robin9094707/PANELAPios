package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

/**
 * 10.0.0 "Rechte-Sperren": fine-grained, optionally temporary locks per player and per kind of block or entity.
 * A locked player can neither use nor break nor blow up what the lock covers – independent of Lands trust, since
 * every check runs at HIGHEST priority and cancels regardless of what other protection plugins allowed. The area
 * of a lock decides where it applies: everywhere, everywhere except the player's own land (as owner), or only in
 * foreign and trusted lands (own land and the wilderness stay allowed).
 *
 * Players without any lock cost exactly one map lookup per event. State lives in {@code access-locks.properties}
 * (one JSON object per player) and is written atomically; nothing else in the plugin's data changes.
 */
final class AccessLocks implements Listener {
    record Flag(String id, String title, String titleEn, String explain, Material icon, String defaultScope) { }
    record Scope(String id, String title, String explain) { }
    record Preset(String id, String title, String explain, Map<String, String> flags) { }
    record Entry(String scope, long until, String reason, String actor, long since) {
        boolean active(long now) { return until == 0L || until > now; }
    }
    /** Where a block or entity sits relative to the player (Lands). */
    enum Standing { WILD, OWNER, TRUSTED, FOREIGN }

    static final Map<String, Flag> FLAGS = new LinkedHashMap<>();
    static final Map<String, Scope> SCOPES = new LinkedHashMap<>();
    static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
    static {
        flag("chest", "Truhen", "Chests", "Truhen, Fallentruhen, Kupfertruhen, Truhenloren und Truhenboote: öffnen, abbauen, sprengen, Trichter darunter.", Material.CHEST, "own-wild");
        flag("shulker", "Shulkerkisten", "Shulker boxes", "Alle Shulkerkisten: öffnen, abbauen und sprengen.", Material.SHULKER_BOX, "own-wild");
        flag("container", "Andere Behälter", "Other containers", "Fässer, Trichter, Spender, Werfer, Öfen, Braustände, Crafter, Regale, Krüge, Lesepulte, Plattenspieler und Trichterloren.", Material.BARREL, "own-wild");
        flag("enderchest", "Endertruhen", "Ender chests", "Endertruhen öffnen, abbauen und sprengen (eigener Inhalt bleibt erhalten).", Material.ENDER_CHEST, "everywhere");
        flag("doors", "Türen & Tore", "Doors & gates", "Türen, Falltüren und Zauntore: öffnen, abbauen, sprengen – auch nicht über den Block darunter.", Material.OAK_DOOR, "own-wild");
        flag("redstone", "Redstone & Schalter", "Redstone & switches", "Knöpfe, Hebel, Druckplatten, Verstärker, Komparatoren, Sensoren, Notenblöcke und Stolperdrähte.", Material.LEVER, "own-wild");
        flag("utility", "Arbeits- & Nutzblöcke", "Workstations", "Werkbank, Amboss, Zaubertisch, Schmiede, Webstuhl, Betten, Seelenanker, Glocke, Leuchtfeuer, Kessel, Komposter, Bienenstöcke.", Material.CRAFTING_TABLE, "own-wild");
        flag("decor", "Rahmen & Rüstungsständer", "Frames & armor stands", "Rahmen, Leuchtrahmen, Bilder und Rüstungsständer: benutzen, drehen, abnehmen und zerstören.", Material.ITEM_FRAME, "own-wild");
        flag("animals", "Tiere & Dorfbewohner", "Animals & villagers", "Tiere und Dorfbewohner: angreifen, anleinen, scheren, reiten, füttern und handeln.", Material.WHEAT, "own-wild");
        flag("build", "Bauen & Abbauen", "Building", "Jeden Block setzen oder abbauen, Eimer, Feuer, Loren/Boote setzen.", Material.BRICKS, "own");
        flag("pickup", "Wertvolle fremde Items", "Valuable foreign items", "Wertvolle Items (Diamanten, Netherite, Shulker, verzauberte Items …), die ein anderer Spieler gedroppt oder abgebaut hat, nicht aufheben.", Material.DIAMOND, "own");
        flag("pickupall", "Alle fremden Items", "All foreign items", "Gar keine Items aufheben, die von anderen Spielern stammen (Drops, Tode, abgebaute Blöcke).", Material.HOPPER, "own");
        SCOPES.put("everywhere", new Scope("everywhere", "Überall gesperrt", "Gilt überall – auch im eigenen Land, in vertrauten Ländern und in der Wildnis."));
        SCOPES.put("own", new Scope("own", "Nur im eigenen Land erlaubt", "Erlaubt nur in Ländern, die dem Spieler selbst gehören (Besitzer). Gesperrt in vertrauten (getrusteten) und fremden Ländern sowie in der Wildnis."));
        SCOPES.put("own-wild", new Scope("own-wild", "Eigenes Land & Wildnis erlaubt", "Gesperrt nur in fremden und vertrauten (getrusteten) Ländern. Im eigenen Land und in der Wildnis bleibt alles erlaubt."));
        PRESETS.put("thief", new Preset("thief", "Klauer", "Behälter nur noch im eigenen Land (und in der Wildnis); wertvolle fremde Items nur im eigenen Land aufheben.",
            linked("chest", "own-wild", "shulker", "own-wild", "container", "own-wild", "decor", "own-wild", "pickup", "own")));
        PRESETS.put("containers", new Preset("containers", "Behälter komplett sperren", "Truhen, Shulker, Endertruhen und alle anderen Behälter – überall, auch im eigenen Land.",
            linked("chest", "everywhere", "shulker", "everywhere", "container", "everywhere", "enderchest", "everywhere")));
        PRESETS.put("griefer", new Preset("griefer", "Griefer", "Bauen, Behälter, Türen, Redstone, Rahmen und Tiere nur noch im eigenen Land.",
            linked("build", "own", "chest", "own", "shulker", "own", "container", "own", "doors", "own", "redstone", "own", "utility", "own", "decor", "own", "animals", "own", "pickup", "own")));
        PRESETS.put("lockdown", new Preset("lockdown", "Alles sperren", "Jede Interaktion überall sperren – der Spieler kann nur noch laufen und chatten.",
            linked("chest", "everywhere", "shulker", "everywhere", "container", "everywhere", "enderchest", "everywhere", "doors", "everywhere", "redstone", "everywhere", "utility", "everywhere",
                "decor", "everywhere", "animals", "everywhere", "build", "everywhere", "pickupall", "everywhere")));
    }
    private static void flag(String id, String title, String en, String explain, Material icon, String scope) { FLAGS.put(id, new Flag(id, title, en, explain, icon, scope)); }
    private static Map<String, String> linked(String... pairs) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]); return Collections.unmodifiableMap(m); }

    /**
     * 10.0.1: classic per-player rights that the lock areas replace. They are no longer offered separately (no
     * double management); an old block is still honoured, shown inside its area and lifted together with it.
     */
    static final Map<String, String> COVERED = linked("chest", "chest", "shulkers", "shulker", "enderchest", "enderchest", "doors", "doors",
        "buttons", "redstone", "levers", "redstone", "redstone", "redstone", "furnaces", "container", "hoppers", "container", "jukeboxes", "container",
        "lecterns", "container", "campfires", "container", "crafting", "utility", "enchanting", "utility", "anvils", "utility", "brewing", "utility",
        "beacons", "utility", "beds", "utility", "respawn-anchors", "utility", "item-frames", "decor", "armor-stands", "decor", "animals", "animals",
        "villagers", "animals", "build", "build", "break", "build");
    /** Remaining classic rights, grouped for the unified view (each can be blocked permanently or for a time). */
    static final Map<String, String> RIGHT_GROUPS = linked("chat", "Chat & Befehle", "chat-colors", "Chat & Befehle", "chat-formatting", "Chat & Befehle",
        "private-messages", "Chat & Befehle", "mentions", "Chat & Befehle", "commands", "Chat & Befehle", "signs", "Chat & Befehle", "books", "Chat & Befehle",
        "pvp", "Kampf & Gegenstände", "projectiles", "Kampf & Gegenstände", "potions", "Kampf & Gegenstände", "food", "Kampf & Gegenstände", "explosives", "Kampf & Gegenstände",
        "fire", "Kampf & Gegenstände", "bucket", "Kampf & Gegenstände", "drop", "Kampf & Gegenstände", "pickup", "Kampf & Gegenstände", "fishing", "Kampf & Gegenstände", "interact", "Kampf & Gegenstände",
        "move", "Bewegung & Welten", "nether", "Bewegung & Welten", "end", "Bewegung & Welten", "elytra", "Bewegung & Welten", "vehicles", "Bewegung & Welten", "teleportation", "Bewegung & Welten");
    static String rightGroup(String id) { return RIGHT_GROUPS.getOrDefault(id, "Weitere"); }

    static final List<String> DEFAULT_VALUABLES = List.of("DIAMOND*", "*DIAMOND_ORE", "NETHERITE*", "ANCIENT_DEBRIS", "EMERALD*", "*EMERALD_ORE", "GOLD_BLOCK", "RAW_GOLD_BLOCK",
        "*SHULKER_BOX", "ELYTRA", "TOTEM_OF_UNDYING", "ENCHANTED_GOLDEN_APPLE", "GOLDEN_APPLE", "NETHER_STAR", "BEACON", "TRIDENT", "MACE", "HEAVY_CORE", "DRAGON_EGG", "DRAGON_HEAD",
        "*_SMITHING_TEMPLATE", "ENCHANTED_BOOK", "EXPERIENCE_BOTTLE", "CONDUIT", "HEART_OF_THE_SEA", "SPAWNER", "TRIAL_KEY", "OMINOUS_TRIAL_KEY");
    private static final Set<String> CONTAINER_NAMES = Set.of("BARREL", "HOPPER", "DISPENSER", "DROPPER", "FURNACE", "BLAST_FURNACE", "SMOKER", "BREWING_STAND", "CRAFTER",
        "CHISELED_BOOKSHELF", "DECORATED_POT", "LECTERN", "JUKEBOX", "CAMPFIRE", "SOUL_CAMPFIRE");
    private static final Set<String> REDSTONE_NAMES = Set.of("LEVER", "REPEATER", "COMPARATOR", "DAYLIGHT_DETECTOR", "NOTE_BLOCK", "TRIPWIRE", "TRIPWIRE_HOOK", "TARGET");
    private static final Set<String> UTILITY_NAMES = Set.of("CRAFTING_TABLE", "ENCHANTING_TABLE", "GRINDSTONE", "STONECUTTER", "LOOM", "CARTOGRAPHY_TABLE", "SMITHING_TABLE", "FLETCHING_TABLE",
        "BEACON", "RESPAWN_ANCHOR", "BELL", "COMPOSTER", "BEEHIVE", "BEE_NEST", "FLOWER_POT", "CAKE", "VAULT", "LODESTONE");

    private final SimplexAdminSuite plugin;
    final LandsBridge lands;
    private final Path file;
    private final Map<UUID, Map<String, Entry>> locks = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<Material, String> blockCategories = new EnumMap<>(Material.class);
    private final Set<Material> valuables = EnumSet.noneOf(Material.class);
    private final Map<String, Long> notified = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> explosives = new ConcurrentHashMap<>();
    private final Map<String, UUID> triggers = new ConcurrentHashMap<>();
    private final Map<String, Long> triggerTimes = new ConcurrentHashMap<>();
    private final List<Object[]> deaths = new ArrayList<>();
    private final NamespacedKey ownerKey;
    private boolean enchantedValuable = true;
    private volatile boolean anyPickup;

    AccessLocks(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.lands = new LandsBridge(plugin);
        this.file = plugin.getDataFolder().toPath().resolve("access-locks.properties");
        this.ownerKey = new NamespacedKey("simplexadminsuite", "drop_owner");
        for (Material m : Material.values()) {
            try {
                // Classified by name/tag only: item-only materials never match, and no registry lookup is needed.
                if (m.name().startsWith("LEGACY_")) continue;
                String category = classify(m);
                if (category != null) blockCategories.put(m, category);
            } catch (RuntimeException | LinkageError unsupported) { /* material unknown to this server version */ }
        }
        reloadSettings();
        load();
    }

    private static boolean tagged(String name, Material m) {
        try {
            Tag<Material> tag = switch (name) { case "shulker" -> Tag.SHULKER_BOXES; case "doors" -> Tag.DOORS; case "trapdoors" -> Tag.TRAPDOORS; case "gates" -> Tag.FENCE_GATES;
                case "buttons" -> Tag.BUTTONS; case "plates" -> Tag.PRESSURE_PLATES; case "beds" -> Tag.BEDS; default -> Tag.ANVIL; };
            return tag != null && tag.isTagged(m);
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    static String classify(Material m) {
        String n = m.name();
        if (tagged("shulker", m) || n.endsWith("SHULKER_BOX")) return "shulker";
        if (n.equals("ENDER_CHEST")) return "enderchest";
        if (n.endsWith("CHEST")) return "chest";
        if (CONTAINER_NAMES.contains(n) || n.endsWith("_SHELF") || n.equals("SHELF")) return "container";
        if (tagged("doors", m) || tagged("trapdoors", m) || tagged("gates", m) || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") || n.endsWith("_FENCE_GATE")) return "doors";
        if (tagged("buttons", m) || tagged("plates", m) || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE") || REDSTONE_NAMES.contains(n)) return "redstone";
        if (tagged("beds", m) || tagged("anvil", m) || n.endsWith("_BED") || n.endsWith("ANVIL") || UTILITY_NAMES.contains(n) || n.endsWith("CAULDRON") || n.startsWith("POTTED_") || n.endsWith("CANDLE_CAKE")) return "utility";
        return null;
    }

    void reloadSettings() {
        List<String> patterns = plugin.getConfig().getStringList("access-locks.pickup.valuable-items");
        if (patterns.isEmpty()) patterns = DEFAULT_VALUABLES;
        valuables.clear();
        for (String raw : patterns) {
            String glob = raw.trim().toUpperCase(Locale.ROOT);
            if (!glob.matches("[A-Z0-9_*]{1,64}")) continue;
            Pattern regex = Pattern.compile(glob.replace("*", "[A-Z0-9_]*"));
            for (Material m : Material.values()) { try { if (!m.name().startsWith("LEGACY_") && regex.matcher(m.name()).matches()) valuables.add(m); } catch (RuntimeException | LinkageError unsupported) { /* skip */ } }
        }
        enchantedValuable = plugin.getConfig().getBoolean("access-locks.pickup.enchanted-items", true);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!Files.exists(file)) return;
        Properties stored = new Properties();
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { stored.load(in); }
        catch (IOException e) { plugin.getLogger().warning("Rechte-Sperren konnten nicht gelesen werden; Datei bleibt unverändert: " + e.getMessage()); return; }
        for (String key : stored.stringPropertyNames()) {
            UUID id; try { id = UUID.fromString(key); } catch (IllegalArgumentException skip) { continue; }
            try {
                if (!(DiscordBotManager.Json.parse(stored.getProperty(key)) instanceof Map<?, ?> row)) continue;
                if (row.get("name") != null) names.put(id, String.valueOf(row.get("name")));
                Map<String, Entry> entries = new ConcurrentHashMap<>();
                if (row.get("flags") instanceof Map<?, ?> flags) for (var e : flags.entrySet()) {
                    if (!FLAGS.containsKey(String.valueOf(e.getKey())) || !(e.getValue() instanceof Map<?, ?> v)) continue;
                    String scope = String.valueOf(v.get("scope"));
                    entries.put(String.valueOf(e.getKey()), new Entry(SCOPES.containsKey(scope) ? scope : "everywhere", number(v.get("until")), str(v.get("reason")), str(v.get("by")), number(v.get("since"))));
                }
                if (!entries.isEmpty()) locks.put(id, entries);
            } catch (RuntimeException broken) { plugin.getLogger().warning("Rechte-Sperre für " + key + " übersprungen (ungültig)."); }
        }
        refreshPickup();
    }

    private synchronized void save() {
        Properties out = new Properties();
        for (var e : locks.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Map<String, Object> flags = new LinkedHashMap<>();
            e.getValue().forEach((id, entry) -> flags.put(id, SimplexAdminSuite.appMap("scope", entry.scope(), "until", entry.until(), "reason", entry.reason(), "by", entry.actor(), "since", entry.since())));
            out.setProperty(e.getKey().toString(), DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("name", names.getOrDefault(e.getKey(), ""), "flags", flags)));
        }
        try { AtomicState.save(file, out, "Simplex Admin Suite access locks (10.0.0)"); }
        catch (IOException e) { plugin.getLogger().warning("Rechte-Sperren konnten nicht gespeichert werden: " + e.getMessage()); }
    }

    /** Removes expired locks (called every few seconds and lazily on reads). */
    void tick() {
        long now = System.currentTimeMillis(); boolean changed = false;
        for (var it = locks.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            List<String> expired = e.getValue().entrySet().stream().filter(x -> !x.getValue().active(now)).map(Map.Entry::getKey).toList();
            for (String flag : expired) {
                e.getValue().remove(flag); changed = true;
                Player online = Bukkit.getPlayer(e.getKey());
                if (online != null) online.sendMessage(plugin.prefix() + plugin.playerText(online, "§aDeine Sperre für §f" + FLAGS.get(flag).title() + " §aist abgelaufen.", "§aYour lock for §f" + FLAGS.get(flag).titleEn() + " §ahas expired."));
                plugin.suiteAudit("System", "Rechte-Sperre abgelaufen", names.getOrDefault(e.getKey(), e.getKey().toString()) + " · " + FLAGS.get(flag).title());
            }
            if (e.getValue().isEmpty()) it.remove();
        }
        notified.entrySet().removeIf(e -> now - e.getValue() > 30_000L);
        triggerTimes.entrySet().removeIf(e -> { if (now - e.getValue() > 5_000L) { triggers.remove(e.getKey()); return true; } return false; });
        if (explosives.size() > 512) explosives.clear();
        synchronized (deaths) { deaths.removeIf(d -> Bukkit.getCurrentTick() - (int) d[3] > 2); }
        if (changed) { refreshPickup(); save(); }
    }

    private void refreshPickup() { anyPickup = locks.values().stream().anyMatch(m -> m.containsKey("pickup") || m.containsKey("pickupall")); }

    // ------------------------------------------------------------------ queries

    Entry entry(UUID player, String flag) {
        Map<String, Entry> map = locks.get(player);
        if (map == null) return null;
        Entry e = map.get(flag);
        return e != null && e.active(System.currentTimeMillis()) ? e : null;
    }
    boolean locked(UUID player) { Map<String, Entry> m = locks.get(player); return m != null && !m.isEmpty(); }

    Standing standing(UUID player, Location location) { return lands.standing(location, player); }

    /** Whether {@code flag} forbids the player at this location. */
    boolean blocked(UUID player, String flag, Location location) {
        Entry e = entry(player, flag);
        if (e == null) return false;
        return switch (e.scope()) {
            case "own" -> standing(player, location) != Standing.OWNER;
            case "own-wild" -> { Standing s = standing(player, location); yield s != Standing.OWNER && s != Standing.WILD; }
            default -> true;
        };
    }

    String category(Material m) { return blockCategories.get(m); }

    static String entityCategory(Entity e) {
        if (e instanceof StorageMinecart || e instanceof ChestBoat) return "chest";
        if (e instanceof HopperMinecart) return "container";
        if (e instanceof Hanging && !(e instanceof LeashHitch) || e instanceof ArmorStand) return "decor";
        if (e instanceof Animals || e instanceof AbstractVillager || e instanceof Allay) return "animals";
        return null;
    }

    /** First flag (block category or build) that forbids breaking/using this block for the player, or null. */
    private String blockingFlag(UUID player, Block block, boolean modifying) {
        String category = category(block.getType());
        if (category != null && blocked(player, category, block.getLocation())) return category;
        if (modifying && blocked(player, "build", block.getLocation())) return "build";
        return null;
    }

    /** A block whose removal would drop a protected door, plate or switch attached to it. */
    private String supportFlag(UUID player, Block block) {
        if (!locked(player) || !plugin.getConfig().getBoolean("access-locks.protect-supports", true)) return null;
        Block above = block.getRelative(BlockFace.UP);
        String up = category(above.getType());
        if (up != null && (up.equals("doors") || up.equals("redstone")) && dependsOn(above, block) && blocked(player, up, above.getLocation())) return up;
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN)) {
            Block side = block.getRelative(face);
            String c = category(side.getType());
            if (c != null && (c.equals("redstone") || c.equals("doors")) && dependsOn(side, block) && blocked(player, c, side.getLocation())) return c;
        }
        return null;
    }
    private static boolean dependsOn(Block attached, Block support) {
        BlockData data = attached.getBlockData();
        if (data instanceof Door door) return door.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM && attached.getRelative(BlockFace.DOWN).equals(support);
        if (data instanceof FaceAttachable f) {
            return switch (f.getAttachedFace()) {
                case FLOOR -> attached.getRelative(BlockFace.DOWN).equals(support);
                case CEILING -> attached.getRelative(BlockFace.UP).equals(support);
                default -> data instanceof Directional d && attached.getRelative(d.getFacing().getOppositeFace()).equals(support);
            };
        }
        return attached.getRelative(BlockFace.DOWN).equals(support) && !(data instanceof Directional);
    }

    // ------------------------------------------------------------------ messages

    private void deny(Player p, String flag, Location at) {
        if (p == null) return;
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + ":" + flag;
        Long last = notified.get(key);
        boolean quiet = flag.startsWith("pickup");
        Entry e = entry(p.getUniqueId(), flag);
        Flag f = FLAGS.get(flag);
        if (e == null || f == null) return;
        if (last == null || now - last > (quiet ? 4_000L : 2_500L)) {
            notified.put(key, now);
            String remaining = e.until() == 0 ? plugin.playerText(p, "dauerhaft", "permanent") : plugin.playerText(p, "noch ", "") + remaining(e.until() - now, plugin.playerText(p, "de", "en").equals("de")) + plugin.playerText(p, "", " left");
            String where = switch (e.scope()) {
                case "own" -> plugin.playerText(p, " Erlaubt nur in deinem eigenen Land.", " Only allowed in your own land.");
                case "own-wild" -> plugin.playerText(p, " Erlaubt nur in deinem eigenen Land und in der Wildnis.", " Only allowed in your own land and the wilderness.");
                default -> "";
            };
            String bar = plugin.playerText(p, "§c✖ " + f.title() + " sind für dich gesperrt §7(" + remaining + ")", "§c✖ " + f.titleEn() + " are locked for you §7(" + remaining + ")");
            p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(bar));
            if (!quiet && plugin.getConfig().getBoolean("access-locks.notify-chat", true))
                p.sendMessage(plugin.prefix() + plugin.playerText(p, "§cRechte-Sperre: §f" + f.title() + " §c– " + remaining + "." + where + (e.reason().isBlank() ? "" : " §7Grund: " + e.reason()),
                    "§cAccess lock: §f" + f.titleEn() + " §c– " + remaining + "." + where + (e.reason().isBlank() ? "" : " §7Reason: " + e.reason())));
        }
    }

    static String remaining(long millis, boolean german) {
        long minutes = Math.max(1, (millis + 59_999L) / 60_000L);
        long days = minutes / 1440, hours = minutes % 1440 / 60, mins = minutes % 60;
        StringBuilder b = new StringBuilder();
        if (days > 0) b.append(days).append(german ? " T. " : "d ");
        if (hours > 0) b.append(hours).append(german ? " Std. " : "h ");
        if (days == 0 && mins > 0) b.append(mins).append(german ? " Min." : "m");
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ listeners: blocks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (block == null) return;
        Player p = e.getPlayer();
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && ("utility".equals(category(block.getType())) && (block.getType().name().endsWith("_BED") || block.getType() == Material.RESPAWN_ANCHOR))) {
            String key = key(block.getLocation()); triggers.put(key, p.getUniqueId()); triggerTimes.put(key, System.currentTimeMillis());
        }
        if (!locked(p.getUniqueId())) return;
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            String c = category(block.getType());
            if (c != null && blocked(p.getUniqueId(), c, block.getLocation())) { e.setCancelled(true); deny(p, c, block.getLocation()); }
            else if (block.getType() == Material.FARMLAND && blocked(p.getUniqueId(), "build", block.getLocation())) e.setCancelled(true);
            return;
        }
        String c = category(block.getType());
        if (c == null) return;
        // Left-clicks only matter for note blocks; breaking is handled in BlockBreakEvent.
        if (e.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK && block.getType() != Material.NOTE_BLOCK) return;
        if (blocked(p.getUniqueId(), c, block.getLocation())) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            e.setCancelled(true);
            deny(p, c, block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void breakBlock(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!locked(p.getUniqueId())) return;
        String flag = blockingFlag(p.getUniqueId(), e.getBlock(), true);
        if (flag == null) flag = supportFlag(p.getUniqueId(), e.getBlock());
        if (flag != null) { e.setCancelled(true); deny(p, flag, e.getBlock().getLocation()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void place(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!locked(p.getUniqueId())) return;
        Block placed = e.getBlockPlaced();
        if (blocked(p.getUniqueId(), "build", placed.getLocation())) { e.setCancelled(true); deny(p, "build", placed.getLocation()); return; }
        if (placed.getType() == Material.HOPPER && plugin.getConfig().getBoolean("access-locks.block-hoppers", true)) {
            String flag = drainFlag(p.getUniqueId(), placed.getRelative(BlockFace.UP));
            if (flag != null) { e.setCancelled(true); deny(p, flag, placed.getLocation()); }
        }
    }

    /** A hopper below this block would pull items out of a container the player may not touch. */
    private String drainFlag(UUID player, Block above) {
        String c = category(above.getType());
        if (c == null || !(c.equals("chest") || c.equals("shulker") || c.equals("container"))) return null;
        return blocked(player, c, above.getLocation()) ? c : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void entityPlace(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null || !locked(p.getUniqueId())) return;
        Location at = e.getEntity().getLocation();
        if (blocked(p.getUniqueId(), "build", at)) { e.setCancelled(true); deny(p, "build", at); return; }
        if (e.getEntity() instanceof HopperMinecart && plugin.getConfig().getBoolean("access-locks.block-hoppers", true)) {
            String flag = drainFlag(p.getUniqueId(), at.getBlock().getRelative(BlockFace.UP));
            if (flag != null) { e.setCancelled(true); deny(p, flag, at); }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void bucketEmpty(PlayerBucketEmptyEvent e) { if (locked(e.getPlayer().getUniqueId()) && blocked(e.getPlayer().getUniqueId(), "build", e.getBlock().getLocation())) { e.setCancelled(true); deny(e.getPlayer(), "build", e.getBlock().getLocation()); } }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void bucketFill(PlayerBucketFillEvent e) { if (locked(e.getPlayer().getUniqueId()) && blocked(e.getPlayer().getUniqueId(), "build", e.getBlock().getLocation())) { e.setCancelled(true); deny(e.getPlayer(), "build", e.getBlock().getLocation()); } }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void ignite(BlockIgniteEvent e) { Player p = e.getPlayer(); if (p != null && locked(p.getUniqueId()) && blocked(p.getUniqueId(), "build", e.getBlock().getLocation())) { e.setCancelled(true); deny(p, "build", e.getBlock().getLocation()); } }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void hangingPlace(HangingPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null || !locked(p.getUniqueId())) return;
        Location at = e.getEntity().getLocation();
        String flag = blocked(p.getUniqueId(), "build", at) ? "build" : blocked(p.getUniqueId(), "decor", at) ? "decor" : null;
        if (flag != null) { e.setCancelled(true); deny(p, flag, at); }
    }

    // ------------------------------------------------------------------ listeners: explosions

    private UUID responsible(Entity source) {
        for (int depth = 0; source != null && depth < 4; depth++) {
            if (source instanceof Player p) return p.getUniqueId();
            UUID tracked = explosives.get(source.getUniqueId());
            if (tracked != null) return tracked;
            if (source instanceof TNTPrimed tnt) { source = tnt.getSource(); continue; }
            if (source instanceof Projectile projectile) { ProjectileSource shooter = projectile.getShooter(); source = shooter instanceof Entity entity ? entity : null; continue; }
            if (source instanceof Creeper creeper && creeper.getTarget() instanceof Player target) return target.getUniqueId();
            if (source instanceof Tameable tame && tame.getOwner() instanceof Player owner) return owner.getUniqueId();
            return null;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackExplosive(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal || e.getEntity() instanceof ExplosiveMinecart)) return;
        UUID who = responsible(e.getDamager());
        if (who != null) explosives.put(e.getEntity().getUniqueId(), who);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void entityExplode(EntityExplodeEvent e) {
        if (locks.isEmpty()) return;
        UUID who = responsible(e.getEntity());
        if (who == null || !locked(who)) return;
        filterExplosion(who, e.blockList(), e.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void blockExplode(BlockExplodeEvent e) {
        if (locks.isEmpty()) return;
        UUID who = triggers.get(key(e.getBlock().getLocation()));
        if (who == null && e.getExplodedBlockState() != null) who = triggers.get(key(e.getExplodedBlockState().getLocation()));
        if (who == null || !locked(who)) return;
        filterExplosion(who, e.blockList(), e.getBlock().getLocation());
    }

    private void filterExplosion(UUID who, List<Block> blocks, Location center) {
        String[] hit = {null};
        blocks.removeIf(b -> {
            String flag = blockingFlag(who, b, true);
            if (flag == null) flag = supportFlag(who, b);
            if (flag != null && hit[0] == null) hit[0] = flag;
            return flag != null;
        });
        if (hit[0] != null) deny(Bukkit.getPlayer(who), hit[0], center);
    }

    // ------------------------------------------------------------------ listeners: entities

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interactEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!locked(p.getUniqueId())) return;
        String c = entityCategory(e.getRightClicked());
        if (c != null && blocked(p.getUniqueId(), c, e.getRightClicked().getLocation())) { e.setCancelled(true); deny(p, c, e.getRightClicked().getLocation()); }
    }

    /** Armor stands and Paper's item-frame changes use their own handler lists. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void armorStand(PlayerArmorStandManipulateEvent e) {
        Player p = e.getPlayer();
        if (locked(p.getUniqueId()) && blocked(p.getUniqueId(), "decor", e.getRightClicked().getLocation())) { e.setCancelled(true); deny(p, "decor", e.getRightClicked().getLocation()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void itemFrame(io.papermc.paper.event.player.PlayerItemFrameChangeEvent e) {
        Player p = e.getPlayer();
        if (locked(p.getUniqueId()) && blocked(p.getUniqueId(), "decor", e.getItemFrame().getLocation())) { e.setCancelled(true); deny(p, "decor", e.getItemFrame().getLocation()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void damageEntity(EntityDamageByEntityEvent e) {
        if (locks.isEmpty()) return;
        String c = entityCategory(e.getEntity());
        if (c == null) return;
        UUID who = responsible(e.getDamager());
        if (who != null && locked(who) && blocked(who, c, e.getEntity().getLocation())) { e.setCancelled(true); deny(Bukkit.getPlayer(who), c, e.getEntity().getLocation()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void hangingBreak(HangingBreakByEntityEvent e) {
        if (locks.isEmpty()) return;
        UUID who = responsible(e.getRemover());
        if (who == null || !locked(who)) return;
        String flag = blocked(who, "decor", e.getEntity().getLocation()) ? "decor" : blocked(who, "build", e.getEntity().getLocation()) ? "build" : null;
        if (flag != null) { e.setCancelled(true); deny(Bukkit.getPlayer(who), flag, e.getEntity().getLocation()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void vehicleDamage(VehicleDamageEvent e) {
        if (locks.isEmpty()) return;
        String c = entityCategory(e.getVehicle());
        UUID who = responsible(e.getAttacker());
        if (c != null && who != null && locked(who) && blocked(who, c, e.getVehicle().getLocation())) { e.setCancelled(true); deny(Bukkit.getPlayer(who), c, e.getVehicle().getLocation()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void vehicleDestroy(VehicleDestroyEvent e) {
        if (locks.isEmpty()) return;
        String c = entityCategory(e.getVehicle());
        UUID who = responsible(e.getAttacker());
        if (c != null && who != null && locked(who) && blocked(who, c, e.getVehicle().getLocation())) { e.setCancelled(true); deny(Bukkit.getPlayer(who), c, e.getVehicle().getLocation()); }
    }

    /** Safety net: any container inventory a locked player would open (also via other plugins or mechanics). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void open(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !locked(p.getUniqueId())) return;
        String flag = null; Location at = null;
        if (e.getInventory().getType() == InventoryType.ENDER_CHEST) { flag = "enderchest"; at = e.getInventory().getLocation() != null ? e.getInventory().getLocation() : p.getLocation(); }
        else {
            InventoryHolder holder = e.getInventory().getHolder(false);
            if (holder instanceof DoubleChest chest) { at = chest.getLocation(); flag = "chest"; }
            else if (holder instanceof BlockInventoryHolder block) { at = block.getBlock().getLocation(); flag = category(block.getBlock().getType()); }
            else if (holder instanceof Entity entity) { at = entity.getLocation(); flag = entityCategory(entity); }
        }
        if (flag != null && at != null && blocked(p.getUniqueId(), flag, at)) { e.setCancelled(true); deny(p, flag, at); }
    }

    // ------------------------------------------------------------------ listeners: foreign item pickup

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tagBlockDrops(BlockDropItemEvent e) {
        if (!anyPickup) return;
        for (Item item : e.getItems()) tag(item, e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) {
        if (!anyPickup) return;
        Location l = e.getEntity().getLocation();
        synchronized (deaths) { deaths.add(new Object[]{e.getEntity().getUniqueId(), l.getWorld(), l.clone(), Bukkit.getCurrentTick()}); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(ItemSpawnEvent e) {
        if (!anyPickup) return;
        synchronized (deaths) {
            if (deaths.isEmpty()) return;
            Location at = e.getLocation();
            for (Object[] d : deaths) if (d[1] == at.getWorld() && ((Location) d[2]).distanceSquared(at) < 9 && Bukkit.getCurrentTick() - (int) d[3] <= 1) { tag(e.getEntity(), (UUID) d[0]); return; }
        }
    }

    private void tag(Item item, UUID owner) {
        var data = item.getPersistentDataContainer();
        if (!data.has(ownerKey, PersistentDataType.STRING)) data.set(ownerKey, PersistentDataType.STRING, owner.toString());
    }

    UUID dropOwner(Item item) {
        String tagged = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (tagged != null) try { return UUID.fromString(tagged); } catch (IllegalArgumentException ignored) { }
        return item.getThrower();
    }

    boolean valuableType(Material type) { return valuables.contains(type); }
    boolean valuable(ItemStack stack) {
        if (stack == null) return false;
        if (valuables.contains(stack.getType())) return true;
        if (!enchantedValuable || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta.hasEnchants() || meta instanceof EnchantmentStorageMeta stored && stored.hasStoredEnchants()) return true;
        return meta instanceof BlockStateMeta state && state.hasBlockState() && state.getBlockState() instanceof org.bukkit.block.ShulkerBox;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void pickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !anyPickup || !locked(p.getUniqueId())) return;
        UUID owner = dropOwner(e.getItem());
        if (owner == null || owner.equals(p.getUniqueId())) return;
        Location at = e.getItem().getLocation();
        String flag = blocked(p.getUniqueId(), "pickupall", at) ? "pickupall" : valuable(e.getItem().getItemStack()) && blocked(p.getUniqueId(), "pickup", at) ? "pickup" : null;
        if (flag != null) { e.setCancelled(true); deny(p, flag, at); }
    }

    private static String key(Location l) { return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ(); }

    // ------------------------------------------------------------------ changes (main thread)

    /** Parses 30m, 12h, 7d, 2w, 1mo or empty/permanent (0 = permanent). */
    static long duration(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("permanent") || v.equals("dauerhaft") || v.equals("-") || v.equals("0")) return 0L;
        var m = Pattern.compile("([0-9]{1,4})(mo|min|[smhdwy])").matcher(v);
        long total = 0; int end = 0;
        while (m.find()) {
            if (m.start() != end) throw new IllegalArgumentException("Dauer ungültig – z. B. 30m, 12h, 7d, 2w oder leer für dauerhaft");
            long n = Long.parseLong(m.group(1));
            total += n * switch (m.group(2)) { case "s" -> 1_000L; case "m", "min" -> 60_000L; case "h" -> 3_600_000L; case "d" -> 86_400_000L; case "w" -> 604_800_000L; case "mo" -> 2_592_000_000L; default -> 31_536_000_000L; };
            end = m.end();
        }
        if (end != v.length() || total <= 0) throw new IllegalArgumentException("Dauer ungültig – z. B. 30m, 12h, 7d, 2w oder leer für dauerhaft");
        if (total < 60_000L) throw new IllegalArgumentException("Mindestens eine Minute");
        if (total > 3_153_600_000_000L) throw new IllegalArgumentException("Höchstens 100 Jahre – sonst einfach dauerhaft wählen");
        return total;
    }

    static List<String> flagList(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : String.valueOf(raw == null ? "" : raw).split("[,; ]+")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            if (id.equals("all") || id.equals("alle")) { out.addAll(FLAGS.keySet()); continue; }
            if (!FLAGS.containsKey(id)) throw new IllegalArgumentException("Unbekannter Bereich: " + id + " (" + String.join(", ", FLAGS.keySet()) + ")");
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    private static String text(Map<String, Object> values, String key) { Object v = values.get(key); return v == null ? "" : String.valueOf(v).trim(); }

    /** Human-readable plan shown in app, web and Discord before the change is executed. */
    static String summary(Map<String, Object> values, String playerName) {
        String op = text(values, "operation");
        StringBuilder b = new StringBuilder("**Rechte-Sperre** · ").append(playerName).append('\n');
        switch (op) {
            case "set" -> {
                List<String> flags = flagList(text(values, "flags"));
                if (flags.isEmpty()) throw new IllegalArgumentException("Mindestens einen Bereich auswählen");
                String scope = scope(text(values, "scope"), null);
                long d = duration(text(values, "duration"));
                b.append("Sperren: ").append(String.join(", ", flags.stream().map(f -> FLAGS.get(f).title()).toList())).append('\n');
                b.append("Gilt: ").append(scope == null ? "Standardbereich je Sperre" : SCOPES.get(scope).title()).append('\n');
                b.append("Dauer: ").append(d == 0 ? "dauerhaft" : remaining(d, true));
            }
            case "preset" -> {
                Preset p = PRESETS.get(text(values, "preset"));
                if (p == null) throw new IllegalArgumentException("Unbekannte Vorlage");
                long d = duration(text(values, "duration"));
                b.append("Vorlage: ").append(p.title()).append(" – ").append(p.explain()).append('\n').append("Dauer: ").append(d == 0 ? "dauerhaft" : remaining(d, true));
            }
            case "remove" -> b.append("Aufheben: ").append(String.join(", ", flagList(text(values, "flags")).stream().map(f -> FLAGS.get(f).title()).toList()));
            case "clear" -> b.append("Alle Sperren dieses Spielers aufheben – Bereiche und Einzelrechte.");
            case "right-block" -> {
                String right = rightId(text(values, "right"));
                long d = duration(text(values, "duration"));
                b.append("Recht sperren: ").append(SimplexAdminSuite.rightName(right)).append('\n').append("Dauer: ").append(d == 0 ? "dauerhaft" : remaining(d, true));
            }
            case "right-allow" -> b.append("Recht wieder erlauben: ").append(SimplexAdminSuite.rightName(rightId(text(values, "right"))));
            default -> throw new IllegalArgumentException("Aktion: set, preset, remove, clear, right-block oder right-allow");
        }
        String reason = text(values, "reason");
        if (!reason.isEmpty()) b.append("\nGrund: ").append(reason);
        b.append("\nDer Spieler wird bei jedem gesperrten Versuch mit Grund und Restzeit informiert.");
        return b.toString();
    }

    /** A classic right id; areas replace the covered ones, so only the remaining rights can be newly blocked here. */
    static String rightId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SimplexAdminSuite.rightIds().contains(id)) throw new IllegalArgumentException("Unbekanntes Recht: " + id);
        return id;
    }

    private static String scope(String raw, String fallback) {
        if (raw == null || raw.isBlank() || raw.equals("default")) return fallback;
        if (!SCOPES.containsKey(raw)) throw new IllegalArgumentException("Geltungsbereich: everywhere, own oder own-wild");
        return raw;
    }

    UUID resolve(String raw) {
        String v = raw == null ? "" : raw.trim();
        try { return UUID.fromString(v); } catch (IllegalArgumentException notUuid) { /* name */ }
        Player online = Bukkit.getPlayerExact(v);
        if (online != null) return online.getUniqueId();
        UUID known = null;
        try { known = plugin.discordAdminResolvePlayer(v); } catch (RuntimeException databaseNotReady) { /* fall through */ }
        if (known != null) return known;
        if (v.matches("[A-Za-z0-9_.]{1,17}")) { OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(v); if (cached != null) return cached.getUniqueId(); }
        throw new IllegalArgumentException("Spieler nicht gefunden – war er schon einmal auf dem Server?");
    }

    String name(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String stored = names.get(id);
        if (stored != null && !stored.isBlank()) return stored;
        String known = null;
        try { known = plugin.discordAdminPlayerName(id); } catch (RuntimeException databaseNotReady) { /* fall through */ }
        return known == null || known.equals("Unknown player") ? id.toString().substring(0, 8) : known;
    }

    /** Applies a validated change. {@code values}: operation, player, flags, scope, duration, reason, preset. */
    Map<String, Object> apply(Map<String, Object> values, String actor) {
        UUID id = resolve(text(values, "player"));
        String playerName = name(id);
        summary(values, playerName); // validates everything first
        String op = text(values, "operation"), reason = text(values, "reason");
        if (reason.length() > 200 || reason.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Grund: höchstens 200 Zeichen");
        long now = System.currentTimeMillis();
        String title, detail;
        Map<String, Entry> map = locks.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        names.put(id, playerName);
        switch (op) {
            case "set" -> {
                long d = duration(text(values, "duration"));
                String chosen = scope(text(values, "scope"), null);
                List<String> flags = flagList(text(values, "flags"));
                for (String f : flags) map.put(f, new Entry(chosen == null ? FLAGS.get(f).defaultScope() : chosen, d == 0 ? 0 : now + d, reason, actor, now));
                title = "Rechte-Sperre gesetzt";
                detail = String.join(", ", flags.stream().map(f -> FLAGS.get(f).title()).toList()) + " · " + (d == 0 ? "dauerhaft" : remaining(d, true));
            }
            case "preset" -> {
                Preset p = PRESETS.get(text(values, "preset"));
                long d = duration(text(values, "duration"));
                p.flags().forEach((f, s) -> map.put(f, new Entry(s, d == 0 ? 0 : now + d, reason.isEmpty() ? p.title() : reason, actor, now)));
                title = "Vorlage „" + p.title() + "“ angewendet";
                detail = p.explain() + " · " + (d == 0 ? "dauerhaft" : remaining(d, true));
            }
            case "remove" -> {
                List<String> flags = flagList(text(values, "flags"));
                flags.forEach(map::remove);
                liftClassic(id, r -> flags.contains(COVERED.get(r)), actor);
                title = "Rechte-Sperre aufgehoben";
                detail = String.join(", ", flags.stream().map(f -> FLAGS.get(f).title()).toList());
            }
            case "right-block" -> {
                String right = rightId(text(values, "right"));
                if (COVERED.containsKey(right)) throw new IllegalArgumentException("„" + SimplexAdminSuite.rightName(right) + "“ wird über den Bereich „" + FLAGS.get(COVERED.get(right)).title() + "“ gesperrt.");
                long d = duration(text(values, "duration"));
                plugin.unifiedRight(id, right, true, d, text(values, "duration"), actor);
                title = "Recht gesperrt";
                detail = SimplexAdminSuite.rightName(right) + " · " + (d == 0 ? "dauerhaft" : remaining(d, true));
            }
            case "right-allow" -> {
                String right = rightId(text(values, "right"));
                plugin.unifiedRight(id, right, false, 0, "", actor);
                title = "Recht erlaubt";
                detail = SimplexAdminSuite.rightName(right);
            }
            default -> { map.clear(); liftClassic(id, r -> true, actor); title = "Alle Sperren aufgehoben"; detail = "Keine Bereiche und keine Einzelrechte mehr gesperrt"; }
        }
        if (map.isEmpty()) locks.remove(id);
        refreshPickup();
        save();
        plugin.suiteAudit(actor, title, playerName + " · " + detail);
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            if (op.equals("remove") || op.equals("clear")) online.sendMessage(plugin.prefix() + plugin.playerText(online, "§aEin Teammitglied hat Rechte-Sperren für dich aufgehoben: §f" + detail, "§aStaff lifted access locks for you."));
            else online.sendMessage(plugin.prefix() + plugin.playerText(online, "§cFür dich gelten jetzt Rechte-Sperren: §f" + detail + (reason.isEmpty() ? "" : " §7Grund: " + reason), "§cAccess locks now apply to you." + (reason.isEmpty() ? "" : " §7Reason: " + reason)));
        }
        return SimplexAdminSuite.appMap("ok", true, "title", title, "description", playerName + ": " + detail, "player", player(id));
    }

    /** Allows every currently blocked classic right that matches; players without a profile have none. */
    private void liftClassic(UUID id, java.util.function.Predicate<String> which, String actor) {
        Map<String, long[]> states = plugin.rightStates(id);
        if (states == null) return;
        states.forEach((right, st) -> { if (st[0] == 1 && which.test(right)) plugin.unifiedRight(id, right, false, 0, "", actor); });
    }

    // ------------------------------------------------------------------ views (main thread)

    Map<String, Object> catalog() {
        List<Map<String, Object>> flags = new ArrayList<>();
        FLAGS.values().forEach(f -> flags.add(SimplexAdminSuite.appMap("id", f.id(), "title", f.title(), "explain", f.explain(), "icon", f.icon().name().toLowerCase(Locale.ROOT), "defaultScope", f.defaultScope())));
        List<Map<String, Object>> scopes = new ArrayList<>();
        SCOPES.values().forEach(s -> scopes.add(SimplexAdminSuite.appMap("id", s.id(), "title", s.title(), "explain", s.explain())));
        List<Map<String, Object>> presets = new ArrayList<>();
        PRESETS.values().forEach(p -> presets.add(SimplexAdminSuite.appMap("id", p.id(), "title", p.title(), "explain", p.explain(), "flags", p.flags())));
        return SimplexAdminSuite.appMap("flags", flags, "scopes", scopes, "presets", presets, "durations", List.of("30m", "2h", "12h", "1d", "3d", "7d", "14d", "30d", ""),
            "lands", lands.lands() != null, "valuables", valuables.stream().map(m -> m.name().toLowerCase(Locale.ROOT)).sorted().toList(),
            "help", "Rechte-Sperren gelten zusätzlich zu Lands: Auch wenn ein Spieler in einem Land vertraut ist, kann er Gesperrtes weder benutzen noch abbauen noch sprengen. Bei jedem Versuch sieht er, was gesperrt ist, warum und wie lange.");
    }

    Map<String, Object> player(UUID id) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> active = new ArrayList<>();
        Map<String, Entry> map = locks.getOrDefault(id, Map.of());
        for (Flag f : FLAGS.values()) {
            Entry e = map.get(f.id());
            if (e == null || !e.active(now)) continue;
            active.add(SimplexAdminSuite.appMap("flag", f.id(), "title", f.title(), "scope", e.scope(), "scopeTitle", SCOPES.get(e.scope()).title(), "until", e.until(), "permanent", e.until() == 0,
                "remaining", e.until() == 0 ? "dauerhaft" : remaining(e.until() - now, true), "reason", e.reason(), "by", e.actor(), "since", e.since()));
        }
        Map<String, long[]> states = plugin.rightStates(id);
        List<Map<String, Object>> rights = new ArrayList<>();
        Map<String, List<Map<String, Object>>> legacy = new LinkedHashMap<>();
        if (states != null) states.forEach((right, st) -> {
            String state = st[0] == 0 ? "" : st[1] > 0 ? remaining(st[1] - now, true) : "dauerhaft";
            Map<String, Object> row = SimplexAdminSuite.appMap("id", right, "name", SimplexAdminSuite.rightName(right), "group", rightGroup(right), "blocked", st[0] == 1, "until", st[1], "remaining", state);
            if (COVERED.containsKey(right)) { if (st[0] == 1) legacy.computeIfAbsent(COVERED.get(right), k -> new ArrayList<>()).add(row); }
            else rights.add(row);
        });
        long blockedRights = rights.stream().filter(r -> Boolean.TRUE.equals(r.get("blocked"))).count() + legacy.values().stream().mapToInt(List::size).sum();
        return SimplexAdminSuite.appMap("uuid", id.toString(), "name", name(id), "online", Bukkit.getPlayer(id) != null, "locks", active,
            "rights", rights, "legacy", legacy, "profile", states != null, "blockedRights", blockedRights, "inherit", plugin.altInherit(id));
    }

    Map<String, Object> data(String query) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<UUID> ids = new LinkedHashSet<>(locks.keySet());
        ids.addAll(plugin.blockedRightPlayers());
        for (UUID id : ids) {
            Map<String, Object> row = player(id);
            if (((List<?>) row.get("locks")).isEmpty() && ((Number) row.get("blockedRights")).longValue() == 0) continue;
            if (!q.isEmpty() && !String.valueOf(row.get("name")).toLowerCase(Locale.ROOT).contains(q)) continue;
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> !Boolean.TRUE.equals(r.get("online"))).thenComparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> out = new LinkedHashMap<>(catalog());
        out.put("items", rows);
        return out;
    }

    /** Short status line for player profiles and Discord. */
    String describe(UUID id) {
        Map<String, Object> row = player(id);
        List<?> active = (List<?>) row.get("locks");
        List<String> parts = new ArrayList<>();
        for (Object o : active) { Map<?, ?> m = (Map<?, ?>) o; parts.add(m.get("title") + " (" + m.get("scopeTitle") + ", " + m.get("remaining") + ")"); }
        for (Object o : (List<?>) row.get("rights")) { Map<?, ?> m = (Map<?, ?>) o; if (Boolean.TRUE.equals(m.get("blocked"))) parts.add(m.get("name") + " (" + m.get("remaining") + ")"); }
        for (Object list : ((Map<?, ?>) row.get("legacy")).values()) for (Object o : (List<?>) list) { Map<?, ?> m = (Map<?, ?>) o; parts.add(m.get("name") + " (alte Einzelsperre, " + m.get("remaining") + ")"); }
        if (parts.isEmpty()) return "Keine Sperren";
        return String.join("\n", parts);
    }

    // ------------------------------------------------------------------ in-game menus (Java inventory + Floodgate forms)

    private AdminUpdateMenu.Button button(String title, String detail, Material icon, Runnable action) { return new AdminUpdateMenu.Button(title, detail, icon, action); }
    private boolean allowed(Player p) { return plugin.has(p, "simplexadminsuite.accesslocks") || plugin.has(p, "simplexadminsuite.restrictions") || plugin.has(p, "simplexadminsuite.rights"); }

    void menu(Player p) {
        if (!allowed(p)) { p.sendMessage(plugin.prefix() + "§cKeine Berechtigung für Rechte-Sperren."); return; }
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        items.add(button("Online-Spieler", "Spieler auswählen und Rechte ändern", Material.PLAYER_HEAD, () -> pick(p, 0)));
        items.add(button("Spieler suchen", "Teil des Namens eingeben – auch offline; Treffer zur Auswahl", Material.NAME_TAG, () -> plugin.adminUpdate.prompt(p, "Spielername (auch Teil)", "", value -> search(p, value))));
        for (Object o : (List<?>) data("").get("items")) {
            Map<?, ?> row = (Map<?, ?>) o; UUID target = UUID.fromString(String.valueOf(row.get("uuid")));
            items.add(button("§c" + row.get("name"), (Boolean.TRUE.equals(row.get("online")) ? "online" : "offline") + "\n" + describe(target), Material.IRON_BARS, () -> playerMenu(p, target)));
        }
        items.add(AdminUpdateMenu.info("So funktioniert es", "Sperren gelten zusätzlich zu Lands. Gesperrtes kann weder benutzt, abgebaut noch gesprengt werden. Der Spieler sieht Grund und Restzeit.", Material.BOOK));
        items.add(button("Zurück", "Admin-Zentrale", Material.OAK_DOOR, () -> plugin.unifiedMenu.open(p)));
        plugin.adminUpdate.show(p, "Rechte & Sperren", items, 0);
    }

    /** Name search over all known players (same matching as the Discord/app player autocomplete). */
    private void search(Player p, String query) {
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        try { for (var choice : plugin.discordAdminPlayerChoices(query, 25)) { UUID target = UUID.fromString(choice.value); items.add(button(choice.name.replaceFirst("^(Online|Offline) • ", ""), choice.name.startsWith("Online") ? "§aonline" : "offline", Material.PLAYER_HEAD, () -> playerMenu(p, target))); } }
        catch (RuntimeException unavailable) { /* database not ready */ }
        if (items.isEmpty()) items.add(AdminUpdateMenu.info("Keine Treffer", "Kein bekannter Spieler passt zu „" + query + "“.", Material.BARRIER));
        items.add(button("Zurück", "Rechte & Sperren", Material.OAK_DOOR, () -> menu(p)));
        plugin.adminUpdate.show(p, "Suche: " + query, items, 0);
    }

    private void pick(Player p, int page) {
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) items.add(button(online.getName(), locked(online.getUniqueId()) ? "§cHat Sperren" : "Keine Sperren", Material.PLAYER_HEAD, () -> playerMenu(p, online.getUniqueId())));
        items.add(button("Zurück", "Rechte & Sperren", Material.OAK_DOOR, () -> menu(p)));
        plugin.adminUpdate.show(p, "Spieler auswählen", items, page);
    }

    void playerMenu(Player p, UUID id) {
        if (!allowed(p)) return;
        String n = name(id);
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        Map<?, ?> legacy = (Map<?, ?>) player(id).get("legacy");
        for (Flag f : FLAGS.values()) {
            Entry e = entry(id, f.id());
            String state = e == null ? "§aErlaubt" : "§cGesperrt · " + SCOPES.get(e.scope()).title() + " · " + (e.until() == 0 ? "dauerhaft" : "noch " + remaining(e.until() - System.currentTimeMillis(), true));
            if (legacy.get(f.id()) instanceof List<?> old && !old.isEmpty()) state += "\n§6Alte Einzelsperre: " + String.join(", ", old.stream().map(o -> String.valueOf(((Map<?, ?>) o).get("name"))).toList()) + " – wird mit aufgehoben";
            items.add(button((e == null ? "§a" : "§c") + f.title(), state + "\n" + f.explain() + "\nKlick: " + (e == null ? "sperren" : "ändern oder aufheben"), e == null ? f.icon() : Material.BARRIER, () -> flagMenu(p, id, f.id())));
        }
        Map<String, Object> row = player(id);
        long blocked = ((Number) row.get("blockedRights")).longValue();
        items.add(button("Weitere Rechte", "Chat, Befehle, PvP, Nether, End, Elytra, Freeze, Teleport …\n" + (blocked > 0 ? "§c" + blocked + " gesperrt" : "§aAlle erlaubt") + "\n§7Sperren mit Dauer oder dauerhaft", Material.COMPARATOR, () -> rightsMenu(p, id, "")));
        items.add(button("Vorlage anwenden", "Klauer, Behälter komplett, Griefer oder alles sperren", Material.WRITABLE_BOOK, () -> presetMenu(p, id)));
        if (locked(id) || blocked > 0) items.add(button("§cAlle Sperren aufheben", "Bereiche und Einzelrechte von " + n + " sofort freigeben", Material.MILK_BUCKET, () -> run(p, id, SimplexAdminSuite.appMap("operation", "clear"))));
        items.add(button("Zurück", "Rechte & Sperren", Material.OAK_DOOR, () -> menu(p)));
        plugin.adminUpdate.show(p, "Rechte & Sperren · " + n, items, 0);
    }

    /** Remaining classic rights (chat, PvP, dimensions, …) with a duration picker – same data as before. */
    void rightsMenu(Player p, UUID id, String unused) {
        if (!allowed(p)) return;
        Map<String, Object> row = player(id);
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        if (!Boolean.TRUE.equals(row.get("profile"))) items.add(AdminUpdateMenu.info("Kein Spielerprofil", "Einzelrechte gibt es erst, wenn der Spieler einmal online war. Bereiche funktionieren trotzdem.", Material.BARRIER));
        for (Object o : (List<?>) row.get("rights")) {
            Map<?, ?> r = (Map<?, ?>) o; String right = String.valueOf(r.get("id")); boolean blocked = Boolean.TRUE.equals(r.get("blocked"));
            items.add(button((blocked ? "§c" : "§a") + r.get("name"), r.get("group") + "\n" + (blocked ? "§cGesperrt · " + r.get("remaining") + "\n§7Klick: wieder erlauben" : "§aErlaubt\n§7Klick: sperren (Dauer wählen)"), blocked ? Material.RED_DYE : Material.LIME_DYE,
                () -> { if (blocked) { run(p, id, SimplexAdminSuite.appMap("operation", "right-allow", "right", right)); rightsMenu(p, id, ""); } else rightDuration(p, id, right); }));
        }
        items.add(button("Zurück", name(id), Material.OAK_DOOR, () -> playerMenu(p, id)));
        plugin.adminUpdate.show(p, "Weitere Rechte · " + name(id), items, 0);
    }
    private void rightDuration(Player p, UUID id, String right) {
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        for (String d : List.of("30m", "2h", "12h", "1d", "3d", "7d", "30d", ""))
            items.add(button(d.isEmpty() ? "Dauerhaft" : remaining(duration(d), true), d.isEmpty() ? "Bis ein Teammitglied es wieder erlaubt" : "Läuft automatisch ab", d.isEmpty() ? Material.OBSIDIAN : Material.CLOCK,
                () -> { run(p, id, SimplexAdminSuite.appMap("operation", "right-block", "right", right, "duration", d)); rightsMenu(p, id, ""); }));
        items.add(button("Eigene Dauer", "z. B. 45m, 5d oder 2w", Material.NAME_TAG, () -> plugin.adminUpdate.prompt(p, "Dauer (z. B. 45m, 5d, 2w)", "", d -> { run(p, id, SimplexAdminSuite.appMap("operation", "right-block", "right", right, "duration", d)); rightsMenu(p, id, ""); })));
        items.add(button("Zurück", "Weitere Rechte", Material.OAK_DOOR, () -> rightsMenu(p, id, "")));
        plugin.adminUpdate.show(p, SimplexAdminSuite.rightName(right) + " · Dauer", items, 0);
    }

    private void flagMenu(Player p, UUID id, String flag) {
        Flag f = FLAGS.get(flag);
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        for (Scope s : SCOPES.values()) items.add(button(s.title(), s.explain() + (s.id().equals(f.defaultScope()) ? "\n§eEmpfohlen" : ""), s.id().equals("everywhere") ? Material.RED_CONCRETE : s.id().equals("own") ? Material.ORANGE_CONCRETE : Material.YELLOW_CONCRETE, () -> durationMenu(p, id, flag, s.id())));
        if (entry(id, flag) != null) items.add(button("§aSperre aufheben", f.title() + " wieder erlauben", Material.LIME_DYE, () -> run(p, id, SimplexAdminSuite.appMap("operation", "remove", "flags", flag))));
        items.add(AdminUpdateMenu.info(f.title(), f.explain(), f.icon()));
        items.add(button("Zurück", name(id), Material.OAK_DOOR, () -> playerMenu(p, id)));
        plugin.adminUpdate.show(p, f.title() + " · Bereich", items, 0);
    }

    private void durationMenu(Player p, UUID id, String flag, String scope) {
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        for (String d : List.of("30m", "2h", "12h", "1d", "3d", "7d", "30d", "")) {
            items.add(button(d.isEmpty() ? "Dauerhaft" : remaining(duration(d), true), d.isEmpty() ? "Bis ein Teammitglied die Sperre aufhebt" : "Läuft automatisch ab", d.isEmpty() ? Material.OBSIDIAN : Material.CLOCK,
                () -> plugin.adminUpdate.prompt(p, "Grund für den Spieler (\"-\" = kein Grund)", "", reason -> run(p, id, SimplexAdminSuite.appMap("operation", "set", "flags", flag, "scope", scope, "duration", d, "reason", reason.equals("-") ? "" : reason)))));
        }
        items.add(button("Eigene Dauer", "z. B. 45m, 5d oder 2w", Material.NAME_TAG, () -> plugin.adminUpdate.prompt(p, "Dauer (z. B. 45m, 5d, 2w)", "", d -> run(p, id, SimplexAdminSuite.appMap("operation", "set", "flags", flag, "scope", scope, "duration", d, "reason", "")))));
        items.add(button("Zurück", FLAGS.get(flag).title(), Material.OAK_DOOR, () -> flagMenu(p, id, flag)));
        plugin.adminUpdate.show(p, "Dauer wählen", items, 0);
    }

    private void presetMenu(Player p, UUID id) {
        List<AdminUpdateMenu.Button> items = new ArrayList<>();
        for (Preset preset : PRESETS.values()) items.add(button(preset.title(), preset.explain(), Material.WRITABLE_BOOK, () -> {
            List<AdminUpdateMenu.Button> durations = new ArrayList<>();
            for (String d : List.of("1d", "3d", "7d", "30d", "")) durations.add(button(d.isEmpty() ? "Dauerhaft" : remaining(duration(d), true), preset.title(), d.isEmpty() ? Material.OBSIDIAN : Material.CLOCK,
                () -> run(p, id, SimplexAdminSuite.appMap("operation", "preset", "preset", preset.id(), "duration", d))));
            durations.add(button("Zurück", "Vorlagen", Material.OAK_DOOR, () -> presetMenu(p, id)));
            plugin.adminUpdate.show(p, preset.title() + " · Dauer", durations, 0);
        }));
        items.add(button("Zurück", name(id), Material.OAK_DOOR, () -> playerMenu(p, id)));
        plugin.adminUpdate.show(p, "Vorlagen", items, 0);
    }

    private void run(Player p, UUID id, Map<String, Object> values) {
        if (!allowed(p)) return;
        Map<String, Object> v = new LinkedHashMap<>(values);
        v.put("player", id.toString());
        try { Map<String, Object> result = apply(v, p.getName()); p.sendMessage(plugin.prefix() + "§a" + result.get("title") + " §7· " + result.get("description")); }
        catch (IllegalArgumentException e) { p.sendMessage(plugin.prefix() + "§c" + e.getMessage()); }
        if (!String.valueOf(values.get("operation")).startsWith("right-")) playerMenu(p, id);
    }

    // ------------------------------------------------------------------ command /rechtesperre

    boolean command(CommandSender sender, String[] args) {
        if (sender instanceof Player p && args.length == 0) { menu(p); return true; }
        if (!(sender instanceof Player) && !sender.isOp() && !sender.hasPermission("simplexadminsuite.accesslocks")) return true;
        if (sender instanceof Player p && !allowed(p)) { sender.sendMessage(plugin.prefix() + "§cKeine Berechtigung."); return true; }
        try {
            if (args.length == 1 && sender instanceof Player p) { playerMenu(p, resolve(args[0])); return true; }
            if (args.length < 2) { usage(sender); return true; }
            String op = args[1].toLowerCase(Locale.ROOT);
            UUID id = resolve(args[0]);
            Map<String, Object> values = new LinkedHashMap<>(); values.put("player", id.toString());
            switch (op) {
                case "info", "status" -> { sender.sendMessage(plugin.prefix() + "§f" + name(id) + "§7:\n" + describe(id)); return true; }
                case "clear", "aufheben-alle" -> values.put("operation", "clear");
                case "remove", "aufheben" -> { values.put("operation", "remove"); values.put("flags", args.length > 2 ? args[2] : ""); }
                case "preset", "vorlage" -> { values.put("operation", "preset"); values.put("preset", args.length > 2 ? args[2] : ""); values.put("duration", args.length > 3 ? args[3] : ""); values.put("reason", args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : ""); }
                case "set", "sperren" -> { values.put("operation", "set"); values.put("flags", args.length > 2 ? args[2] : ""); values.put("scope", args.length > 3 ? args[3] : ""); values.put("duration", args.length > 4 ? args[4] : ""); values.put("reason", args.length > 5 ? String.join(" ", Arrays.copyOfRange(args, 5, args.length)) : ""); }
                default -> { usage(sender); return true; }
            }
            Map<String, Object> result = apply(values, sender.getName());
            sender.sendMessage(plugin.prefix() + "§a" + result.get("title") + " §7· " + result.get("description"));
        } catch (IllegalArgumentException e) { sender.sendMessage(plugin.prefix() + "§c" + e.getMessage()); }
        return true;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(plugin.prefix() + "§e/rechtesperre §7– Menü\n§e/rechtesperre <Spieler> sperren <Bereiche> [everywhere|own|own-wild] [Dauer] [Grund]\n§e/rechtesperre <Spieler> vorlage <thief|containers|griefer|lockdown> [Dauer] [Grund]\n§e/rechtesperre <Spieler> aufheben <Bereiche>\n§e/rechtesperre <Spieler> clear | info\n§7Bereiche: " + String.join(", ", FLAGS.keySet()));
    }

    List<String> tab(String[] args) {
        if (args.length == 1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2) return List.of("sperren", "vorlage", "aufheben", "clear", "info");
        String op = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && Set.of("sperren", "set", "aufheben", "remove").contains(op)) return new ArrayList<>(FLAGS.keySet());
        if (args.length == 3 && Set.of("vorlage", "preset").contains(op)) return new ArrayList<>(PRESETS.keySet());
        if (args.length == 4 && Set.of("sperren", "set").contains(op)) return new ArrayList<>(SCOPES.keySet());
        if (args.length == 4 && Set.of("vorlage", "preset").contains(op) || args.length == 5 && Set.of("sperren", "set").contains(op)) return List.of("30m", "2h", "1d", "7d", "30d", "dauerhaft");
        return List.of();
    }

    private static long number(Object v) { if (v instanceof Number n) return n.longValue(); try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0L; } }
    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
}
