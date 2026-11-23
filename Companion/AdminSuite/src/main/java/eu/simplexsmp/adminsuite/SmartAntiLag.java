package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 10.23.0 Smart Anti-Lag. Watches tick time and finds hotspots per chunk – mob crowds, item piles,
 * redstone/piston machines and hopper walls – and the players most likely responsible (who placed the
 * machine parts or bred the animals, otherwise who is nearby). Nothing is touched while the server
 * runs fine. Only when the server really suffers AND a hotspot is far above normal, the module acts
 * gently and step by step: (1) friendly notice to the responsible/nearby players, (2) no further
 * spawning/breeding in that chunk, (3) mobs above the limit stop their AI (they stay alive, nothing is
 * deleted) until the server recovers. Every step is visible and reversible in the app and web panel.
 */
final class SmartAntiLag implements Listener {
  static final String FEATURE = "smart-anti-lag-v1", VIEW = "data.anti-lag", MANAGE = "anti-lag.manage";
  static final List<String> MODES = List.of("off", "observe", "notify", "smart");
  private static final int SAMPLE_TICKS = 100, HISTORY = 120;
  private static final long NOTICE_MS = 10 * 60_000L, CAUSE_TTL_MS = 30L * 24 * 3600_000L;
  private static final Set<Material> MACHINE_PARTS = EnumSet.noneOf(Material.class);
  static {
    for (String name : List.of("HOPPER", "OBSERVER", "PISTON", "STICKY_PISTON", "REDSTONE_WIRE", "REDSTONE", "COMPARATOR", "REPEATER", "DISPENSER", "DROPPER",
        "CRAFTER", "REDSTONE_TORCH", "REDSTONE_BLOCK", "SLIME_BLOCK", "HONEY_BLOCK", "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL", "TNT", "SPAWNER",
        "TRIAL_SPAWNER", "CHEST_MINECART", "HOPPER_MINECART", "DAYLIGHT_DETECTOR", "TARGET", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR"))
      try { MACHINE_PARTS.add(Material.valueOf(name)); } catch (IllegalArgumentException ignored) { }
  }

  /** Live counters for one chunk; activity counters are reset every sample. */
  static final class Spot {
    final String world;
    final int x, z;
    int living, items, other, vehicles, redstone, pistons, hoppers = -1;
    String topType = "";
    int topCount;
    double score;
    int overSamples;
    boolean limited, throttled;
    long firstSeen, lastNotice;
    final Set<UUID> throttledMobs = new HashSet<>();
    List<String> nearby = List.of();
    Spot(String world, int x, int z) { this.world = world; this.x = x; this.z = z; }
    String key() { return world + ":" + x + ":" + z; }
  }

  private final SimplexAdminSuite plugin;
  private final Path file;
  private final NamespacedKey throttleKey;
  private final Map<String, Spot> spots = new HashMap<>();
  private final Map<String, Map<UUID, Integer>> causes = new HashMap<>();
  private final Map<String, Long> causeSeen = new HashMap<>();
  private final Map<String, int[]> activity = new HashMap<>();
  private final ConcurrentLinkedDeque<double[]> history = new ConcurrentLinkedDeque<>();
  private final ConcurrentLinkedDeque<Map<String, Object>> actions = new ConcurrentLinkedDeque<>();
  private BukkitTask task;
  private int stressedSamples, calmSamples;
  private volatile double tps = 20, mspt;
  private volatile boolean stressed;
  private long stressedSince;
  private boolean dirty;

  SmartAntiLag(SimplexAdminSuite plugin) throws IOException {
    this.plugin = plugin;
    this.file = plugin.getDataFolder().toPath().resolve("anti-lag.yml");
    this.throttleKey = new NamespacedKey(plugin, "antilag_throttled");
    load();
  }

  void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, SAMPLE_TICKS, SAMPLE_TICKS); }

  /** Every throttled mob gets its AI back when the plugin stops – nothing stays frozen. */
  void stop() {
    if (task != null) task.cancel();
    for (Spot spot : spots.values()) release(spot, false);
    save();
  }

  // ------------------------------------------------------------------ settings

  String mode() {
    String mode = plugin.getConfig().getString("anti-lag.mode", "smart");
    return MODES.contains(mode) ? mode : "smart";
  }
  private int cfg(String key, int fallback, int min, int max) { return Math.max(min, Math.min(max, plugin.getConfig().getInt("anti-lag." + key, fallback))); }
  private int mobLimit() { return cfg("mob-limit-per-chunk", 150, 40, 5000); }
  private int typeLimit() { return cfg("same-type-limit-per-chunk", 100, 30, 5000); }
  private int itemLimit() { return cfg("item-limit-per-chunk", 600, 100, 20000); }
  private int redstoneLimit() { return cfg("redstone-updates-per-second", 800, 50, 100000); }
  private int triggerMspt() { return cfg("trigger-mspt", 48, 30, 200); }
  private int notifyRadius() { return cfg("notify-radius", 96, 16, 512); }

  // ------------------------------------------------------------------ sampling (main thread)

  private void sample() {
    String mode = mode();
    if (mode.equals("off")) { if (!spots.isEmpty()) { for (Spot s : spots.values()) release(s, false); spots.clear(); } return; }
    double[] t = Bukkit.getTPS();
    tps = t.length > 0 ? Math.min(20, t[0]) : 20;
    mspt = Bukkit.getAverageTickTime();
    history.addLast(new double[] {System.currentTimeMillis(), tps, mspt, 0});
    while (history.size() > HISTORY) history.pollFirst();
    boolean bad = mspt >= triggerMspt() || tps < 18.0;
    if (bad) { stressedSamples++; calmSamples = 0; } else { calmSamples++; stressedSamples = 0; }
    if (!stressed && stressedSamples >= 3) { stressed = true; stressedSince = System.currentTimeMillis(); log("Server unter Last", String.format(Locale.ROOT, "%.1f TPS · %.1f ms je Tick", tps, mspt), "warn"); }
    if (stressed && calmSamples >= 6) { stressed = false; log("Server wieder flüssig", String.format(Locale.ROOT, "%.1f TPS · %.1f ms je Tick", tps, mspt), "ok"); }
    scan();
    long now = System.currentTimeMillis();
    for (Spot spot : spots.values()) {
      boolean over = spot.living > mobLimit() || spot.topCount > typeLimit() || spot.items > itemLimit() || spot.redstone > redstoneLimit() * (SAMPLE_TICKS / 20);
      spot.overSamples = over ? spot.overSamples + 1 : 0;
      if (!stressed || !over) {
        if ((spot.limited || spot.throttled) && (!over || calmSamples >= 12)) { release(spot, true); }
        continue;
      }
      if (spot.overSamples >= 2 && !mode.equals("observe") && now - spot.lastNotice >= NOTICE_MS) notice(spot);
      if (mode.equals("smart")) {
        if (spot.overSamples >= 3 && !spot.limited && (spot.living > mobLimit() || spot.topCount > typeLimit())) {
          spot.limited = true;
          log("Spawnbremse aktiv", where(spot) + " · keine weiteren Spawns/Zucht, bis es sich beruhigt", "limit");
        }
        if (spot.overSamples >= 12 && now - stressedSince > 120_000L && spot.living > mobLimit() * 2) throttle(spot);
      }
    }
    if (dirty) { dirty = false; save(); }
  }

  private void scan() {
    Map<String, Spot> next = new HashMap<>();
    long now = System.currentTimeMillis();
    for (World world : Bukkit.getWorlds()) {
      Map<Long, int[]> counts = new HashMap<>();
      Map<Long, Map<EntityType, Integer>> types = new HashMap<>();
      for (Entity e : world.getEntities()) {
        if (e instanceof Player) continue;
        long key = (((long) ((int) Math.floor(e.getX()) >> 4)) << 32) ^ (((int) Math.floor(e.getZ()) >> 4) & 0xffffffffL);
        int[] c = counts.computeIfAbsent(key, k -> new int[4]);
        if (e instanceof LivingEntity && !(e instanceof ArmorStand)) {
          c[0]++;
          types.computeIfAbsent(key, k -> new EnumMap<>(EntityType.class)).merge(e.getType(), 1, Integer::sum);
        } else if (e instanceof Item) c[1]++;
        else if (e instanceof Vehicle) c[3]++;
        else c[2]++;
      }
      for (var entry : counts.entrySet()) {
        int[] c = entry.getValue();
        int cx = (int) (entry.getKey() >> 32), cz = (int) entry.getKey().longValue();
        String key = world.getName() + ":" + cx + ":" + cz;
        int[] act = activity.getOrDefault(key, new int[2]);
        double score = c[0] + c[1] * 0.2 + c[2] * 0.3 + c[3] * 0.8 + act[0] * 0.02 + act[1] * 0.5;
        if (score < 40) continue;
        Spot spot = spots.getOrDefault(key, new Spot(world.getName(), cx, cz));
        spot.living = c[0]; spot.items = c[1]; spot.other = c[2]; spot.vehicles = c[3];
        spot.redstone = act[0]; spot.pistons = act[1];
        spot.score = score;
        spot.topType = ""; spot.topCount = 0;
        Map<EntityType, Integer> perType = types.get(entry.getKey());
        if (perType != null) for (var t : perType.entrySet()) if (t.getValue() > spot.topCount) { spot.topCount = t.getValue(); spot.topType = t.getKey().name(); }
        if (spot.firstSeen == 0) spot.firstSeen = now;
        next.put(key, spot);
      }
    }
    // Redstone machines without many entities.
    for (var entry : activity.entrySet()) {
      if (next.containsKey(entry.getKey())) continue;
      int[] act = entry.getValue();
      double score = act[0] * 0.02 + act[1] * 0.5;
      if (score < 40) continue;
      String[] p = entry.getKey().split(":");
      if (p.length != 3) continue;
      Spot spot = spots.getOrDefault(entry.getKey(), new Spot(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2])));
      spot.living = 0; spot.items = 0; spot.other = 0; spot.vehicles = 0; spot.topType = ""; spot.topCount = 0;
      spot.redstone = act[0]; spot.pistons = act[1]; spot.score = score;
      if (spot.firstSeen == 0) spot.firstSeen = now;
      next.put(entry.getKey(), spot);
    }
    activity.clear();
    for (Spot old : spots.values()) if (!next.containsKey(old.key())) release(old, false);
    spots.clear();
    spots.putAll(next);
    for (Spot spot : spots.values()) spot.nearby = nearby(spot, notifyRadius());
    for (var it = causeSeen.entrySet().iterator(); it.hasNext(); ) {
      var e = it.next();
      if (now - e.getValue() > CAUSE_TTL_MS) { causes.remove(e.getKey()); it.remove(); dirty = true; }
    }
  }

  private List<String> nearby(Spot spot, int radius) {
    World world = Bukkit.getWorld(spot.world);
    if (world == null) return List.of();
    double cx = spot.x * 16 + 8, cz = spot.z * 16 + 8;
    List<Player> list = new ArrayList<>();
    for (Player p : world.getPlayers()) {
      double dx = p.getX() - cx, dz = p.getZ() - cz;
      if (dx * dx + dz * dz <= (double) radius * radius) list.add(p);
    }
    list.sort(Comparator.comparingDouble(p -> (p.getX() - cx) * (p.getX() - cx) + (p.getZ() - cz) * (p.getZ() - cz)));
    return list.stream().map(Player::getName).limit(8).toList();
  }

  // ------------------------------------------------------------------ actions

  private void notice(Spot spot) {
    spot.lastNotice = System.currentTimeMillis();
    Set<Player> receivers = new LinkedHashSet<>();
    for (var cause : topCauses(spot, 3)) { Player p = Bukkit.getPlayer(cause.getKey()); if (p != null) receivers.add(p); }
    for (String name : spot.nearby) { Player p = Bukkit.getPlayerExact(name); if (p != null && receivers.size() < 4) receivers.add(p); }
    String what = describe(spot, true), whatEn = describe(spot, false);
    for (Player p : receivers) {
      boolean de = plugin.community != null ? plugin.community.german(p) : ClientProbeAccess.german(p.getLocale());
      String text = de
          ? "⚠ Bei " + spot.world + " (" + (spot.x * 16 + 8) + ", " + (spot.z * 16 + 8) + ") " + what + ". Das bremst gerade den ganzen Server – bitte reduziere es. Danke!"
          : "⚠ At " + spot.world + " (" + (spot.x * 16 + 8) + ", " + (spot.z * 16 + 8) + ") " + whatEn + ". It is slowing down the whole server – please reduce it. Thanks!";
      try {
        p.sendMessage(Component.text("[Anti-Lag] ", NamedTextColor.GOLD, TextDecoration.BOLD).append(Component.text(text, NamedTextColor.YELLOW)));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.MASTER, 0.6f, 0.8f);
      } catch (RuntimeException ignored) { }
    }
    String names = receivers.stream().map(Player::getName).reduce((a, b) -> a + ", " + b).orElse("niemand in der Nähe");
    log("Spieler informiert", where(spot) + " · " + what + " · an " + names, "notice");
    for (Player staff : Bukkit.getOnlinePlayers())
      if (!receivers.contains(staff) && plugin.has(staff, "simplexadminsuite.admin") && plugin.getConfig().getBoolean("anti-lag.notify-staff", true))
        staff.sendMessage(Component.text("[Anti-Lag] ", NamedTextColor.GOLD).append(Component.text(where(spot) + " · " + what + " ", NamedTextColor.GRAY))
            .append(Component.text("[Hin]", NamedTextColor.AQUA, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/tp " + staff.getName() + " " + (spot.x * 16 + 8) + " " + safeY(spot) + " " + (spot.z * 16 + 8)))
                .hoverEvent(HoverEvent.showText(Component.text("Zum Hotspot teleportieren")))));
  }

  /** Stops the AI of mobs above the limit (alive, untouched, reversible). Tagged so a crash cannot leave them frozen. */
  private void throttle(Spot spot) {
    World world = Bukkit.getWorld(spot.world);
    if (world == null || !world.isChunkLoaded(spot.x, spot.z)) return;
    int keep = mobLimit(), seen = 0, changed = 0;
    for (Entity e : world.getChunkAt(spot.x, spot.z).getEntities()) {
      if (!(e instanceof Mob mob) || mob.customName() != null || mob instanceof Tameable t && t.isTamed()) continue;
      if (++seen <= keep || !mob.isAware()) continue;
      mob.setAware(false);
      mob.getPersistentDataContainer().set(throttleKey, PersistentDataType.BYTE, (byte) 1);
      spot.throttledMobs.add(mob.getUniqueId());
      changed++;
    }
    if (changed > 0) {
      spot.throttled = true;
      log("KI gedrosselt", where(spot) + " · " + changed + " Mobs über dem Limit pausiert (bleiben erhalten)", "throttle");
    }
  }

  private void release(Spot spot, boolean announce) {
    boolean was = spot.limited || spot.throttled;
    if (!spot.throttledMobs.isEmpty()) {
      for (UUID id : spot.throttledMobs) if (Bukkit.getEntity(id) instanceof Mob mob) unthrottle(mob);
      spot.throttledMobs.clear();
    }
    spot.limited = false;
    spot.throttled = false;
    if (was && announce) log("Maßnahmen aufgehoben", where(spot) + " · Server wieder entspannt", "ok");
  }

  private void unthrottle(Mob mob) {
    try { mob.setAware(true); mob.getPersistentDataContainer().remove(throttleKey); } catch (RuntimeException ignored) { }
  }

  /** Safety net: any mob still tagged from an earlier run gets its AI back when its chunk loads. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void entitiesLoad(EntitiesLoadEvent e) {
    for (Entity entity : e.getEntities())
      if (entity instanceof Mob mob && mob.getPersistentDataContainer().has(throttleKey, PersistentDataType.BYTE) && !throttledAnywhere(mob.getUniqueId())) unthrottle(mob);
  }

  private boolean throttledAnywhere(UUID id) {
    for (Spot s : spots.values()) if (s.throttledMobs.contains(id)) return true;
    return false;
  }

  // ------------------------------------------------------------------ signals

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    if (!MACHINE_PARTS.contains(e.getBlockPlaced().getType())) return;
    cause(e.getBlockPlaced().getWorld().getName(), e.getBlockPlaced().getX() >> 4, e.getBlockPlaced().getZ() >> 4, e.getPlayer().getUniqueId(), 1);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void breed(EntityBreedEvent e) {
    if (e.getBreeder() instanceof Player p) cause(e.getEntity().getWorld().getName(), (int) Math.floor(e.getEntity().getX()) >> 4, (int) Math.floor(e.getEntity().getZ()) >> 4, p.getUniqueId(), 1);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void redstone(BlockRedstoneEvent e) {
    var b = e.getBlock();
    activity.computeIfAbsent(b.getWorld().getName() + ":" + (b.getX() >> 4) + ":" + (b.getZ() >> 4), k -> new int[2])[0]++;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void piston(BlockPistonExtendEvent e) {
    var b = e.getBlock();
    activity.computeIfAbsent(b.getWorld().getName() + ":" + (b.getX() >> 4) + ":" + (b.getZ() >> 4), k -> new int[2])[1]++;
  }

  /** Spawn brake: in limited chunks no new mobs from breeding, spawners, eggs or natural spawning. */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void spawn(CreatureSpawnEvent e) {
    if (spots.isEmpty()) return;
    Spot spot = spots.get(e.getLocation().getWorld().getName() + ":" + (e.getLocation().getBlockX() >> 4) + ":" + (e.getLocation().getBlockZ() >> 4));
    if (spot == null || !spot.limited) return;
    switch (e.getSpawnReason()) {
      case BREEDING, SPAWNER, NATURAL, EGG, DISPENSE_EGG, BUILD_IRONGOLEM, BUILD_SNOWMAN, SLIME_SPLIT, VILLAGE_DEFENSE, TRIAL_SPAWNER, DUPLICATION -> e.setCancelled(true);
      default -> { }
    }
  }

  private void cause(String world, int cx, int cz, UUID player, int weight) {
    String key = world + ":" + cx + ":" + cz;
    causes.computeIfAbsent(key, k -> new HashMap<>()).merge(player, weight, Integer::sum);
    causeSeen.put(key, System.currentTimeMillis());
    dirty = true;
  }

  /** Placed machine parts in the chunk and its 8 neighbours, strongest first. */
  private List<Map.Entry<UUID, Integer>> topCauses(Spot spot, int limit) {
    Map<UUID, Integer> sum = new HashMap<>();
    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
      Map<UUID, Integer> c = causes.get(spot.world + ":" + (spot.x + dx) + ":" + (spot.z + dz));
      if (c != null) c.forEach((k, v) -> sum.merge(k, v, Integer::sum));
    }
    return sum.entrySet().stream().sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).limit(limit).toList();
  }

  // ------------------------------------------------------------------ helpers

  private String describe(Spot s, boolean de) {
    List<String> parts = new ArrayList<>();
    if (s.topCount > typeLimit()) parts.add(s.topCount + " " + nice(s.topType) + (de ? " auf engem Raum" : " in one spot"));
    else if (s.living > mobLimit()) parts.add(s.living + (de ? " Mobs auf engem Raum" : " mobs in one spot"));
    if (s.items > itemLimit()) parts.add(s.items + (de ? " herumliegende Items" : " dropped items"));
    if (s.redstone > redstoneLimit() * (SAMPLE_TICKS / 20)) parts.add(de ? "eine sehr aktive Redstone-Maschine" : "a very busy redstone machine");
    if (s.pistons > 200) parts.add(s.pistons / 5 + (de ? " Kolbenbewegungen pro Sekunde" : " piston moves per second"));
    if (parts.isEmpty()) parts.add(de ? "sehr viel Aktivität" : "a lot of activity");
    return (de ? "sind " : "there are ") + String.join(de ? " und " : " and ", parts);
  }

  private static String nice(String type) {
    if (type == null || type.isBlank()) return "Mobs";
    String lower = type.toLowerCase(Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private static String where(Spot s) { return s.world + " " + (s.x * 16 + 8) + " / " + (s.z * 16 + 8); }

  private int safeY(Spot s) {
    World w = Bukkit.getWorld(s.world);
    if (w == null) return 100;
    try { return w.getHighestBlockYAt(s.x * 16 + 8, s.z * 16 + 8) + 1; } catch (RuntimeException e) { return 100; }
  }

  private void log(String title, String text, String kind) {
    actions.addFirst(SimplexAdminSuite.appMap("time", System.currentTimeMillis(), "title", title, "text", text, "kind", kind));
    while (actions.size() > 60) actions.pollLast();
    plugin.getLogger().info("[Anti-Lag] " + title + ": " + text);
  }

  // ------------------------------------------------------------------ app & web

  Map<String, Object> data(boolean manage) {
    List<Spot> sorted = new ArrayList<>(spots.values());
    sorted.sort(Comparator.comparingDouble((Spot s) -> s.score).reversed());
    List<Map<String, Object>> rows = new ArrayList<>();
    Map<UUID, double[]> playerScores = new HashMap<>();
    Map<UUID, Integer> playerSpots = new HashMap<>();
    for (Spot s : sorted) {
      List<Map<String, Object>> causeRows = new ArrayList<>();
      for (var c : topCauses(s, 5)) {
        Player online = Bukkit.getPlayer(c.getKey());
        causeRows.add(SimplexAdminSuite.appMap("uuid", c.getKey().toString(), "name", online != null ? online.getName() : plugin.discordAdminPlayerName(c.getKey()), "online", online != null, "placed", c.getValue()));
        playerScores.computeIfAbsent(c.getKey(), k -> new double[1])[0] += s.score;
        playerSpots.merge(c.getKey(), 1, Integer::sum);
      }
      if (causeRows.isEmpty() && !s.nearby.isEmpty()) {
        Player near = Bukkit.getPlayerExact(s.nearby.get(0));
        if (near != null) { playerScores.computeIfAbsent(near.getUniqueId(), k -> new double[1])[0] += s.score * 0.5; playerSpots.merge(near.getUniqueId(), 1, Integer::sum); }
      }
      boolean over = s.living > mobLimit() || s.topCount > typeLimit() || s.items > itemLimit() || s.redstone > redstoneLimit() * (SAMPLE_TICKS / 20);
      String status = s.throttled ? "throttled" : s.limited ? "limited" : s.lastNotice > 0 && over ? "warned" : over ? "high" : "watch";
      String kind = s.redstone * 0.02 + s.pistons * 0.5 > s.living ? "machine" : s.items > s.living ? "items" : "mobs";
      rows.add(SimplexAdminSuite.appMap("id", s.key(), "world", s.world, "chunkX", s.x, "chunkZ", s.z, "x", s.x * 16 + 8, "z", s.z * 16 + 8, "score", Math.round(s.score),
          "living", s.living, "items", s.items, "other", s.other, "vehicles", s.vehicles, "topType", nice(s.topType), "topCount", s.topCount,
          "redstonePerSecond", s.redstone / (SAMPLE_TICKS / 20), "pistonsPerSecond", s.pistons / (SAMPLE_TICKS / 20), "hoppers", s.hoppers,
          "status", status, "kind", kind, "over", over, "throttledMobs", s.throttledMobs.size(), "description", describe(s, true),
          "causes", causeRows, "nearby", s.nearby, "since", s.firstSeen, "lastNotice", s.lastNotice));
      if (rows.size() >= 40) break;
    }
    List<Map<String, Object>> players = new ArrayList<>();
    playerScores.entrySet().stream().sorted(Comparator.comparingDouble((Map.Entry<UUID, double[]> e) -> e.getValue()[0]).reversed()).limit(15).forEach(e -> {
      Player online = Bukkit.getPlayer(e.getKey());
      players.add(SimplexAdminSuite.appMap("uuid", e.getKey().toString(), "name", online != null ? online.getName() : plugin.discordAdminPlayerName(e.getKey()), "online", online != null,
          "score", Math.round(e.getValue()[0]), "hotspots", playerSpots.getOrDefault(e.getKey(), 0),
          "world", online != null ? online.getWorld().getName() : "", "x", online != null ? online.getLocation().getBlockX() : 0, "z", online != null ? online.getLocation().getBlockZ() : 0));
    });
    List<Map<String, Object>> chart = new ArrayList<>();
    for (double[] h : history) chart.add(SimplexAdminSuite.appMap("time", (long) h[0], "tps", Math.round(h[1] * 10) / 10.0, "mspt", Math.round(h[2] * 10) / 10.0));
    List<Map<String, Object>> staff = new ArrayList<>();
    for (Player p : Bukkit.getOnlinePlayers()) if (plugin.has(p, "simplexadminsuite.admin") || p.isOp()) staff.add(SimplexAdminSuite.appMap("name", p.getName(), "uuid", p.getUniqueId().toString()));
    return SimplexAdminSuite.appMap("feature", FEATURE, "manage", manage, "mode", mode(), "modes", MODES,
        "health", SimplexAdminSuite.appMap("tps", Math.round(tps * 10) / 10.0, "mspt", Math.round(mspt * 10) / 10.0, "stressed", stressed, "stressedSince", stressed ? stressedSince : 0,
            "entities", Bukkit.getWorlds().stream().mapToInt(w -> w.getEntityCount()).sum(), "chunks", Bukkit.getWorlds().stream().mapToInt(w -> w.getLoadedChunks().length).sum(), "players", Bukkit.getOnlinePlayers().size()),
        "limits", SimplexAdminSuite.appMap("mobsPerChunk", mobLimit(), "sameTypePerChunk", typeLimit(), "itemsPerChunk", itemLimit(), "redstonePerSecond", redstoneLimit(), "triggerMspt", triggerMspt(),
            "notifyRadius", notifyRadius(), "notifyStaff", plugin.getConfig().getBoolean("anti-lag.notify-staff", true)),
        "hotspots", rows, "players", players, "history", chart, "actions", new ArrayList<>(actions), "staffOnline", staff,
        "limited", spots.values().stream().filter(s -> s.limited).count(), "throttled", spots.values().stream().mapToInt(s -> s.throttledMobs.size()).sum());
  }

  private Spot spot(Map<String, Object> body) {
    Spot s = spots.get(String.valueOf(body.getOrDefault("id", "")));
    if (s == null) throw new IllegalArgumentException("Hotspot nicht mehr aktiv – Liste aktualisieren.");
    return s;
  }

  Map<String, Object> apply(String actor, Map<String, Object> body) {
    String operation = String.valueOf(body.getOrDefault("operation", ""));
    String notice;
    switch (operation) {
      case "settings" -> {
        if (body.containsKey("mode")) {
          String mode = String.valueOf(body.get("mode"));
          if (!MODES.contains(mode)) throw new IllegalArgumentException("Unbekannter Modus");
          plugin.getConfig().set("anti-lag.mode", mode);
        }
        for (String[] f : new String[][] {{"mobsPerChunk", "mob-limit-per-chunk"}, {"sameTypePerChunk", "same-type-limit-per-chunk"}, {"itemsPerChunk", "item-limit-per-chunk"},
            {"redstonePerSecond", "redstone-updates-per-second"}, {"triggerMspt", "trigger-mspt"}, {"notifyRadius", "notify-radius"}})
          if (body.get(f[0]) instanceof Number n) plugin.getConfig().set("anti-lag." + f[1], n.intValue());
        if (body.containsKey("notifyStaff")) plugin.getConfig().set("anti-lag.notify-staff", Boolean.TRUE.equals(body.get("notifyStaff")));
        plugin.saveConfig();
        plugin.suiteAudit(actor, "Smart Anti-Lag eingestellt", "Modus " + mode());
        notice = "Einstellungen gespeichert.";
      }
      case "teleport" -> {
        Spot s = spot(body);
        Player staff = Bukkit.getPlayerExact(String.valueOf(body.getOrDefault("player", "")));
        if (staff == null || !(staff.isOp() || plugin.has(staff, "simplexadminsuite.admin"))) throw new IllegalArgumentException("Teammitglied ist nicht online oder hat keine Admin-Rechte.");
        World w = Bukkit.getWorld(s.world);
        if (w == null) throw new IllegalArgumentException("Welt nicht geladen");
        staff.teleportAsync(new Location(w, s.x * 16 + 8.5, safeY(s), s.z * 16 + 8.5));
        plugin.suiteAudit(actor, "Zu Lag-Hotspot teleportiert", staff.getName() + " → " + where(s));
        notice = staff.getName() + " wird zu " + where(s) + " teleportiert.";
      }
      case "teleport-player" -> {
        Player staff = Bukkit.getPlayerExact(String.valueOf(body.getOrDefault("player", "")));
        Player target = Bukkit.getPlayerExact(String.valueOf(body.getOrDefault("target", "")));
        if (staff == null || !(staff.isOp() || plugin.has(staff, "simplexadminsuite.admin"))) throw new IllegalArgumentException("Teammitglied ist nicht online oder hat keine Admin-Rechte.");
        if (target == null) throw new IllegalArgumentException("Spieler ist nicht online.");
        staff.teleportAsync(target.getLocation());
        plugin.suiteAudit(actor, "Zu Spieler teleportiert (Anti-Lag)", staff.getName() + " → " + target.getName());
        notice = staff.getName() + " wird zu " + target.getName() + " teleportiert.";
      }
      case "notify" -> { Spot s = spot(body); s.lastNotice = 0; notice(s); notice = "Spieler am Hotspot informiert."; }
      case "release" -> { Spot s = spot(body); release(s, true); s.overSamples = 0; notice = "Maßnahmen für " + where(s) + " aufgehoben."; }
      case "limit" -> { Spot s = spot(body); s.limited = true; log("Spawnbremse manuell", where(s) + " · durch " + actor, "limit"); notice = "Spawnbremse aktiv."; }
      case "inspect" -> {
        Spot s = spot(body);
        World w = Bukkit.getWorld(s.world);
        if (w == null || !w.isChunkLoaded(s.x, s.z)) throw new IllegalArgumentException("Chunk ist nicht geladen.");
        int hoppers = 0;
        for (BlockState state : w.getChunkAt(s.x, s.z).getTileEntities()) if (state.getType() == Material.HOPPER) hoppers++;
        s.hoppers = hoppers;
        notice = hoppers + " Trichter im Chunk.";
      }
      case "clear-items" -> {
        Spot s = spot(body);
        World w = Bukkit.getWorld(s.world);
        if (w == null || !w.isChunkLoaded(s.x, s.z)) throw new IllegalArgumentException("Chunk ist nicht geladen.");
        int removed = 0;
        for (Entity e : w.getChunkAt(s.x, s.z).getEntities()) if (e instanceof Item item && item.getTicksLived() > 200) { item.remove(); removed++; }
        log("Items entfernt", where(s) + " · " + removed + " herumliegende Items durch " + actor, "action");
        plugin.suiteAudit(actor, "Anti-Lag: Items entfernt", where(s) + " · " + removed);
        notice = removed + " Items entfernt.";
      }
      default -> throw new IllegalArgumentException("Unbekannte Aktion");
    }
    Map<String, Object> out = data(true);
    out.put("notice", notice);
    return out;
  }

  boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
    if (!path.equals("/v1/anti-lag")) return false;
    api.requirePermission(key, VIEW);
    boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "manage");
    if (method.equals("GET")) AppApiManager.send(x, 200, api.onMain(rev, () -> data(manage)));
    else if (method.equals("POST")) {
      if (!manage) throw new AppApiManager.ApiError(403, "Ändern benötigt das Recht " + MANAGE);
      Map<String, Object> body = AppApiManager.body(x, 4096);
      if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
      String actor = api.actorName(key);
      AppApiManager.send(x, 200, api.onMain(rev, () -> apply(actor, body)));
    } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
    return true;
  }

  // ------------------------------------------------------------------ storage (who placed machine parts)

  private void load() throws IOException {
    if (!Files.isRegularFile(file)) return;
    YamlConfiguration y = new YamlConfiguration();
    try { y.load(file.toFile()); } catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("anti-lag.yml unlesbar; Datei bleibt unverändert", e); }
    for (Map<?, ?> row : y.getMapList("causes")) {
      String chunk = row.get("world") + ":" + row.get("x") + ":" + row.get("z");
      Map<UUID, Integer> map = new HashMap<>();
      if (row.get("players") instanceof Map<?, ?> players)
        for (var p : players.entrySet()) { try { map.put(UUID.fromString(String.valueOf(p.getKey())), ((Number) p.getValue()).intValue()); } catch (RuntimeException ignored) { } }
      if (map.isEmpty()) continue;
      causes.put(chunk, map);
      causeSeen.put(chunk, row.get("seen") instanceof Number n ? n.longValue() : System.currentTimeMillis());
    }
  }

  private void save() {
    YamlConfiguration y = new YamlConfiguration();
    y.set("schema-version", 1);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var e : causes.entrySet()) {
      int split = e.getKey().lastIndexOf(':'), split2 = e.getKey().lastIndexOf(':', split - 1);
      if (split2 <= 0) continue;
      Map<String, Object> players = new LinkedHashMap<>();
      e.getValue().forEach((id, count) -> players.put(id.toString(), count));
      rows.add(SimplexAdminSuite.appMap("world", e.getKey().substring(0, split2), "x", Integer.parseInt(e.getKey().substring(split2 + 1, split)), "z", Integer.parseInt(e.getKey().substring(split + 1)),
          "seen", causeSeen.getOrDefault(e.getKey(), System.currentTimeMillis()), "players", players));
    }
    y.set("causes", rows);
    try {
      Files.createDirectories(file.getParent());
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temp, y.saveToString());
      try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    } catch (IOException e) { plugin.getLogger().warning("Anti-Lag-Daten konnten nicht gespeichert werden: " + e.getMessage()); }
  }
}
