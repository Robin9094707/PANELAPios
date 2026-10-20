package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Additive fun/community module. Statistics are stored in config.yml only; no PlayerProfile
 * field is replaced or renamed. Player-facing text stays English while administration stays German.
 */
final class FunFeaturesManager implements Listener {
    enum FartLevel {
        TINY("tiny", "Tiny squeak", "let out a tiny squeaky fart", 0x57F287, 1),
        LIGHT("light", "Light fart", "let out a light fart", 0x57F287, 2),
        BUBBLY("bubbly", "Bubbly fart", "released a bubbly fart", 0x3498DB, 2),
        MEDIUM("medium", "Medium fart", "farted loudly", 0xFEE75C, 3),
        WET("wet", "Suspiciously wet fart", "produced a suspiciously wet fart", 0xE67E22, 3),
        LONG("long", "Ridiculously long fart", "let out a ridiculously long fart", 0x9B59B6, 4),
        TRUMPET("trumpet", "Trumpet fart", "blasted a trumpet fart", 0x1ABC9C, 4),
        THUNDER("thunder", "Thunder fart", "shook the server with a thunder fart", 0x5865F2, 5),
        ULTRA("ultra", "Ultra fart", "unleashed an ULTRA FART", 0xED4245, 6),
        NUCLEAR("nuclear", "Nuclear fart", "detonated a NUCLEAR FART", 0xED4245, 7),
        WHISTLE("whistle", "Whistling fart", "unleashed a Whistling fart", 0x8B5CF6, 2),
        POPCORN("popcorn", "Popcorn fart", "unleashed a Popcorn fart", 0x8B5CF6, 3),
        BUBBLEGUM("bubblegum", "Bubblegum fart", "unleashed a Bubblegum fart", 0x8B5CF6, 4),
        MOTOR("motor", "Motor fart", "unleashed a Motor fart", 0x8B5CF6, 5),
        FROG("frog", "Frog fart", "unleashed a Frog fart", 0x8B5CF6, 6),
        ROBOT("robot", "Robot fart", "unleashed a Robot fart", 0x8B5CF6, 2),
        BASS("bass", "Bass fart", "unleashed a Bass fart", 0x8B5CF6, 3),
        SNEAKY("sneaky", "Sneaky fart", "unleashed a Sneaky fart", 0x8B5CF6, 4),
        SQUEAKY("squeaky", "Squeaky fart", "unleashed a Squeaky fart", 0x8B5CF6, 5),
        ECHO("echo", "Echo fart", "unleashed a Echo fart", 0x8B5CF6, 6),
        ROCKET("rocket", "Rocket fart", "unleashed a Rocket fart", 0x8B5CF6, 2),
        BONGO("bongo", "Bongo fart", "unleashed a Bongo fart", 0x8B5CF6, 3),
        ALIEN("alien", "Alien fart", "unleashed a Alien fart", 0x8B5CF6, 4),
        TUBA("tuba", "Tuba fart", "unleashed a Tuba fart", 0x8B5CF6, 5),
        STORM("storm", "Storm fart", "unleashed a Storm fart", 0x8B5CF6, 6),
        JELLY("jelly", "Jelly fart", "unleashed a Jelly fart", 0x8B5CF6, 2),
        DRAGON("dragon", "Dragon fart", "unleashed a Dragon fart", 0x8B5CF6, 3),
        ULTRA_QUAKE("ultra_quake", "ULTRA EARTHQUAKE FART", "triggered the rare five-second ULTRA EARTHQUAKE FART", 0xED4245, 8),
        ULTRA_COSMIC("ultra_cosmic", "ULTRA COSMIC FART", "triggered the rare five-second ULTRA COSMIC FART", 0xED4245, 8),
        ULTRA_SUPERNOVA("ultra_supernova", "ULTRA SUPERNOVA FART", "triggered the rare five-second ULTRA SUPERNOVA FART", 0xED4245, 8);

        final String id;
        final String display;
        final String sentence;
        final int discordColor;
        final int strength;

        boolean rare() { return id.startsWith("ultra_"); }
        String germanDisplay() { return switch(id) {
            case "whistle" -> "Pfeiffurz";
            case "popcorn" -> "Popcorn-Furz";
            case "bubblegum" -> "Kaugummi-Furz";
            case "motor" -> "Motorfurz";
            case "frog" -> "Froschfurz";
            case "robot" -> "Roboterfurz";
            case "bass" -> "Bassfurz";
            case "sneaky" -> "Schleichfurz";
            case "squeaky" -> "Quietschfurz";
            case "echo" -> "Echofurz";
            case "rocket" -> "Raketenfurz";
            case "bongo" -> "Bongofurz";
            case "alien" -> "Alienfurz";
            case "tuba" -> "Tubafurz";
            case "storm" -> "Sturmfurz";
            case "jelly" -> "Wackelpudding-Furz";
            case "dragon" -> "Drachenfurz";
            case "ultra_quake" -> "ULTRA ERDBEBEN-FURZ";
            case "ultra_cosmic" -> "ULTRA KOSMOS-FURZ";
            case "ultra_supernova" -> "ULTRA SUPERNOVA-FURZ";
            default -> PlayerLanguages.translate(display);
        }; }

        FartLevel(String id, String display, String sentence, int discordColor, int strength) {
            this.id = id;
            this.display = display;
            this.sentence = sentence;
            this.discordColor = discordColor;
            this.strength = strength;
        }
    }

    enum BurpLevel {
        SMALL("small", "Small burp", "let out a small burp", 0x57F287, 1),
        LOUD("loud", "Loud burp", "burped loudly", 0xFEE75C, 2),
        DEEP("deep", "Deep burp", "released a deep rumbling burp", 0xE67E22, 3),
        MONSTER("monster", "Monster burp", "unleashed a MONSTER BURP", 0xED4245, 4),
        HICCUP("hiccup", "Hiccup burp", "unleashed a Hiccup burp", 0x8B5CF6, 2),
        SODA("soda", "Soda burp", "unleashed a Soda burp", 0x8B5CF6, 3),
        CROAK("croak", "Croaking burp", "unleashed a Croaking burp", 0x8B5CF6, 4),
        ECHO("echo", "Echo burp", "unleashed a Echo burp", 0x8B5CF6, 5),
        ROBOT("robot", "Robot burp", "unleashed a Robot burp", 0x8B5CF6, 6),
        BASS("bass", "Bass burp", "unleashed a Bass burp", 0x8B5CF6, 2),
        DRAGON("dragon", "Dragon burp", "unleashed a Dragon burp", 0x8B5CF6, 3),
        DINOSAUR("dinosaur", "Dinosaur burp", "unleashed a Dinosaur burp", 0x8B5CF6, 4),
        BUBBLE("bubble", "Bubbling burp", "unleashed a Bubbling burp", 0x8B5CF6, 5),
        OPERA("opera", "Opera burp", "unleashed a Opera burp", 0x8B5CF6, 6),
        GOBLIN("goblin", "Goblin burp", "unleashed a Goblin burp", 0x8B5CF6, 2),
        TUNNEL("tunnel", "Tunnel burp", "unleashed a Tunnel burp", 0x8B5CF6, 3),
        PIRATE("pirate", "Pirate burp", "unleashed a Pirate burp", 0x8B5CF6, 4),
        THUNDER("thunder", "Thunder burp", "unleashed a Thunder burp", 0x8B5CF6, 5),
        VOLCANO("volcano", "Volcano burp", "unleashed a Volcano burp", 0x8B5CF6, 6),
        WOBBLE("wobble", "Wobbling burp", "unleashed a Wobbling burp", 0x8B5CF6, 2),
        TURBO("turbo", "Turbo burp", "unleashed a Turbo burp", 0x8B5CF6, 3),
        ULTRA_TITAN("ultra_titan", "ULTRA TITAN BURP", "triggered the rare five-second ULTRA TITAN BURP", 0xED4245, 8),
        ULTRA_ABYSS("ultra_abyss", "ULTRA ABYSS BURP", "triggered the rare five-second ULTRA ABYSS BURP", 0xED4245, 8),
        ULTRA_GALAXY("ultra_galaxy", "ULTRA GALAXY BURP", "triggered the rare five-second ULTRA GALAXY BURP", 0xED4245, 8);

        final String id;
        final String display;
        final String sentence;
        final int discordColor;
        final int strength;

        boolean rare() { return id.startsWith("ultra_"); }
        String germanDisplay() { return switch(id) {
            case "hiccup" -> "Schluckauf-Rülpser";
            case "soda" -> "Brause-Rülpser";
            case "croak" -> "Quak-Rülpser";
            case "echo" -> "Echo-Rülpser";
            case "robot" -> "Roboter-Rülpser";
            case "bass" -> "Bass-Rülpser";
            case "dragon" -> "Drachen-Rülpser";
            case "dinosaur" -> "Dinosaurier-Rülpser";
            case "bubble" -> "Blubber-Rülpser";
            case "opera" -> "Opern-Rülpser";
            case "goblin" -> "Goblin-Rülpser";
            case "tunnel" -> "Tunnel-Rülpser";
            case "pirate" -> "Piraten-Rülpser";
            case "thunder" -> "Donner-Rülpser";
            case "volcano" -> "Vulkan-Rülpser";
            case "wobble" -> "Wackel-Rülpser";
            case "turbo" -> "Turbo-Rülpser";
            case "ultra_titan" -> "ULTRA TITAN-RÜLPSER";
            case "ultra_abyss" -> "ULTRA ABGRUND-RÜLPSER";
            case "ultra_galaxy" -> "ULTRA GALAXIE-RÜLPSER";
            default -> PlayerLanguages.translate(display);
        }; }

        BurpLevel(String id, String display, String sentence, int discordColor, int strength) {
            this.id = id;
            this.display = display;
            this.sentence = sentence;
            this.discordColor = discordColor;
            this.strength = strength;
        }
    }

    private static final class FunMenu implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final SimplexAdminSuite plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Long> fartCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> burpCooldowns = new ConcurrentHashMap<>();
    private final Map<FartLevel, Long> fartCounts = new EnumMap<>(FartLevel.class);
    private final Map<BurpLevel, Long> burpCounts = new EnumMap<>(BurpLevel.class);
    private final Set<UUID> mutedFarts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> mutedBurps = ConcurrentHashMap.newKeySet();
    private final File preferencesFile;
    private long totalFarts;
    private long totalBurps;

    FunFeaturesManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.preferencesFile = new File(plugin.getDataFolder(), "fun-preferences.yml");
    }

    void start() {
        loadPreferences();
        totalFarts = Math.max(0L, plugin.getConfig().getLong("fun.fart.statistics.total", 0L));
        for (FartLevel level : FartLevel.values()) {
            long fallback = switch (level) {
                case LIGHT -> plugin.getConfig().getLong("fun.fart.statistics.light", 0L);
                case MEDIUM -> plugin.getConfig().getLong("fun.fart.statistics.medium", 0L);
                case ULTRA -> plugin.getConfig().getLong("fun.fart.statistics.ultra", 0L);
                default -> 0L;
            };
            fartCounts.put(level, Math.max(0L, plugin.getConfig().getLong("fun.fart.statistics.variants." + level.id, fallback)));
        }
        totalBurps = Math.max(0L, plugin.getConfig().getLong("fun.burp.statistics.total", 0L));
        for (BurpLevel level : BurpLevel.values()) {
            burpCounts.put(level, Math.max(0L, plugin.getConfig().getLong("fun.burp.statistics." + level.id, 0L)));
        }
    }

    void stop() {
        saveStatistics();
        savePreferences();
        clearCooldowns();
    }

    void reload() {
        clearCooldowns();
        start();
    }

    boolean handleFart(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefix() + "This command can only be used by a player.");
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("mute"))) {
            boolean muted = togglePreference(player.getUniqueId(), false);
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + (muted ? "Fart messages and sounds from other players are now hidden." : "Fart messages and sounds from other players are visible again.")));
            return true;
        }
        if (!plugin.has(player, "simplexadminsuite.fart.use")) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "You do not have permission to use /fart."));
            return true;
        }
        if (!fartEnabled()) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + publicMessage("fart", "disabled-message", "The fart command is currently disabled by staff.")));
            return true;
        }
        long now = System.currentTimeMillis();
        long remaining = cooldownRemaining(cooldownMap(false), player.getUniqueId(), now);
        if (remaining > 0L && !plugin.has(player, "simplexadminsuite.fart.bypass")) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + publicMessage("fart", "cooldown-message", "Your next fart is ready in {seconds} seconds.")
                    .replace("{seconds}", Long.toString(remaining))));
            return true;
        }
        FartLevel requested = parseFart(args.length == 0 ? "random" : args[0]);
        if (requested == null) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Use /fart [tiny|light|bubbly|medium|wet|long|trumpet|thunder|ultra|nuclear|random]."));
            return true;
        }
        if (requested.rare() && args.length > 0 && !Set.of("random","zufall").contains(args[0].toLowerCase(Locale.ROOT))) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + plugin.playerText(player,"Diese seltene Variante wird nur zufällig ausgelöst.","This rare variant can only be triggered randomly."))); return true;
        }
        if (!plugin.getConfig().getBoolean("fun.fart.allow-player-variant-selection", true) && args.length > 0) requested = randomFart();
        performFart(player, requested, false);
        if (!plugin.has(player, "simplexadminsuite.fart.bypass")) putCooldown(player.getUniqueId(), now + fartCooldownMillis(), false);
        return true;
    }

    boolean handleBurp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefix() + "This command can only be used by a player.");
            return true;
        }
        if(args.length>0&&args[0].equalsIgnoreCase("soundtest")){
            if(plugin.has(player,"simplexadminsuite.fun.admin"))soundTest(player);
            else player.sendMessage(plugin.playerUiText(player,plugin.prefix()+"You do not have permission to use /burp."));
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("mute"))) {
            boolean muted = togglePreference(player.getUniqueId(), true);
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + (muted ? "Burp messages and sounds from other players are now hidden." : "Burp messages and sounds from other players are visible again.")));
            return true;
        }
        if (!plugin.has(player, "simplexadminsuite.burp.use")) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "You do not have permission to use /burp."));
            return true;
        }
        if (!burpEnabled()) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + publicMessage("burp", "disabled-message", "The burp command is currently disabled by staff.")));
            return true;
        }
        long now = System.currentTimeMillis();
        long remaining = cooldownRemaining(cooldownMap(true), player.getUniqueId(), now);
        if (remaining > 0L && !plugin.has(player, "simplexadminsuite.burp.bypass")) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + publicMessage("burp", "cooldown-message", "Your next burp is ready in {seconds} seconds.")
                    .replace("{seconds}", Long.toString(remaining))));
            return true;
        }
        BurpLevel requested = parseBurp(args.length == 0 ? "random" : args[0]);
        if (requested == null) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Use /burp [small|loud|deep|monster|random]."));
            return true;
        }
        if (requested.rare() && args.length > 0 && !Set.of("random","zufall").contains(args[0].toLowerCase(Locale.ROOT))) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + plugin.playerText(player,"Diese seltene Variante wird nur zufällig ausgelöst.","This rare variant can only be triggered randomly."))); return true;
        }
        if (!plugin.getConfig().getBoolean("fun.burp.allow-player-variant-selection", true) && args.length > 0) requested = randomBurp();
        performBurp(player, requested, false);
        if (!plugin.has(player, "simplexadminsuite.burp.bypass")) putCooldown(player.getUniqueId(), now + burpCooldownMillis(), true);
        return true;
    }

    void performFartPreview(Player admin, FartLevel level) {
        if (admin == null || !plugin.has(admin, "simplexadminsuite.fun.admin")) return;
        performFart(admin, level, true);
    }

    void performBurpPreview(Player admin, BurpLevel level) {
        if (admin == null || !plugin.has(admin, "simplexadminsuite.fun.admin")) return;
        performBurp(admin, level, true);
    }

    private void performFart(Player player, FartLevel level, boolean preview) {
        if (player == null || level == null) return;
        String message = "💨 " + player.getName() + " " + level.sentence + "!";
        if (preview) {
            player.sendMessage(plugin.color("&6Vorschau: &f" + localizedFun(player,message,false)));
            playFartEffects(player, level, false);
            return;
        }
        broadcastOrSelf(player, message, "fun.fart.minecraft-broadcast");
        playFartEffects(player, level, plugin.getConfig().getBoolean("fun.fart.global-sounds", true));
        if (plugin.getConfig().getBoolean("fun.fart.discord-relay", true)) {
            plugin.relayFart(player.getName(), level.display, message, level.discordColor);
        }
        totalFarts++;
        fartCounts.put(level, fartCount(level) + 1L);
        if ((totalFarts + totalBurps) % 10L == 0L) saveStatistics();
        plugin.recordFunAction(player, "fart", level.id);
    }

    private void performBurp(Player player, BurpLevel level, boolean preview) {
        if (player == null || level == null) return;
        String message = "🥤 " + player.getName() + " " + level.sentence + "!";
        if (preview) {
            player.sendMessage(plugin.color("&6Vorschau: &f" + localizedFun(player,message,true)));
            playBurpEffects(player, level, false);
            return;
        }
        broadcastOrSelf(player, message, "fun.burp.minecraft-broadcast");
        playBurpEffects(player, level, plugin.getConfig().getBoolean("fun.burp.global-sounds", true));
        if (plugin.getConfig().getBoolean("fun.burp.discord-relay", true)) {
            plugin.relayBurp(player.getName(), level.display, message, level.discordColor);
        }
        totalBurps++;
        burpCounts.put(level, burpCount(level) + 1L);
        if ((totalFarts + totalBurps) % 10L == 0L) saveStatistics();
        plugin.recordFunAction(player, "burp", level.id);
    }

    private void broadcastOrSelf(Player player, String message, String path) {
        boolean burp = path.contains("burp");
        if (plugin.getConfig().getBoolean(path, true)) {
            for (Player listener : Bukkit.getOnlinePlayers()) {
                if (!listener.getUniqueId().equals(player.getUniqueId()) && isMuted(listener.getUniqueId(), burp)) continue;
                listener.sendMessage(plugin.color("&6" + localizedFun(listener, message, burp)));
            }
        } else player.sendMessage(plugin.color("&6" + localizedFun(player, message, burp)));
    }

    private String localizedFun(Player viewer,String message,boolean burp) {
        if (!plugin.community.german(viewer)) return message;
        if(burp) for(BurpLevel l:BurpLevel.values()) if(message.endsWith(l.sentence+"!"))
            return message.substring(0,message.length()-l.sentence.length()-1)+(l.rare()?"hat einen seltenen fünfsekündigen ":"macht einen ")+l.germanDisplay()+"!";
        if(!burp) for(FartLevel l:FartLevel.values()) if(message.endsWith(l.sentence+"!"))
            return message.substring(0,message.length()-l.sentence.length()-1)+(l.rare()?"hat einen seltenen fünfsekündigen ":"macht einen ")+l.germanDisplay()+"!";
        return PlayerLanguages.translate(message);
    }
    private void extendedAudio(Player p,String id,int index,boolean burp,boolean global,boolean rare) {
        int pulses=rare?21:2+index%3;
        for(int n=0;n<pulses;n++) { final int pulse=n;
            Runnable sound=()->{
                if(!p.isOnline())return;String path="fun."+(burp?"burp":"fart");
                if(!plugin.getConfig().getBoolean(path+".sounds",plugin.getConfig().getBoolean(path+".effects",true)))return;
                float pitch=rare?0.65F+(pulse%4)*0.12F:0.65F+(index%6)*0.12F;
                for(Player hearer:global?Bukkit.getOnlinePlayers():List.of(p)) {
                    if(!hearer.getUniqueId().equals(p.getUniqueId())&&isMuted(hearer.getUniqueId(),burp))continue;
                    // Registered Java sounds are also translatable by Geyser; no custom resource pack.
                    CommunityAudio.play(hearer,hearer.getLocation(),burp?"minecraft:entity.player.burp":"minecraft:block.slime_block.place",rare?1.5F:1F,pitch);
                    if(!burp)CommunityAudio.play(hearer,hearer.getLocation(),"minecraft:entity.puffer_fish.blow_out",rare?1.1F:0.6F,pitch);
                    if(rare&&pulse%4==0)CommunityAudio.play(hearer,hearer.getLocation(),burp?"minecraft:entity.ravager.roar":"minecraft:entity.generic.explode",0.65F,0.8F);
                    if(rare&&pulse%3==0)CommunityAudio.play(hearer,hearer.getLocation(),"minecraft:block.note_block.bass",0.8F,0.6F+(index%4)*0.15F);
                }
                if(plugin.getConfig().getBoolean(path+".particles",true))try{p.getWorld().spawnParticle(Particle.CLOUD,p.getLocation().clone().add(0,burp?1.45:0.3,0),rare?12:3,0.3,0.15,0.3,0.01);}catch(IllegalArgumentException ignored){}
            };
            if(n==0)sound.run();else Bukkit.getScheduler().runTaskLater(plugin,sound,n*5L);
        }
    }
    void previews(Player admin,boolean burp) {
        if(!plugin.adminMenuAllowed(admin,"fun.previews")||!plugin.has(admin,"simplexadminsuite.fun.admin"))return;
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        if(burp)rows.add(new AdminUpdateMenu.Button("Rülpser-Hörtest","Rülpser, Atem und Glocke • nur für dich",Material.NOTE_BLOCK,()->soundTest(admin)));
        if(burp)for(BurpLevel l:BurpLevel.values())rows.add(new AdminUpdateMenu.Button(l.germanDisplay(),l.id+(l.rare()?" • selten • 5 Sekunden":"")+" • "+burpCount(l)+"x",Material.GOLDEN_APPLE,()->performBurpPreview(admin,l)));
        else for(FartLevel l:FartLevel.values())rows.add(new AdminUpdateMenu.Button(l.germanDisplay(),l.id+(l.rare()?" • selten • 5 Sekunden":"")+" • "+fartCount(l)+"x",Material.SLIME_BALL,()->performFartPreview(admin,l)));
        plugin.adminUpdate.show(admin,burp?"Rülps-Vorschauen":"Furz-Vorschauen",rows,0);
    }

    private void playFartEffects(Player player, FartLevel level, boolean globalSound) {
        boolean sounds = plugin.getConfig().getBoolean("fun.fart.sounds", plugin.getConfig().getBoolean("fun.fart.effects", true));
        boolean particles = plugin.getConfig().getBoolean("fun.fart.particles", plugin.getConfig().getBoolean("fun.fart.effects", true));
        Location location = player.getLocation().clone().add(0.0D, 0.65D, 0.0D);
        if (sounds) {
            float volume = 0.55F + (level.strength * 0.19F);
            float pitch = Math.max(0.12F, 0.92F - (level.strength * 0.095F));
            switch (level) {
                case TINY -> {
                    playSound(player, location, "minecraft:entity.puffer_fish.blow_out", volume * 0.65F, 1.75F, globalSound);
                    playSound(player, location, "minecraft:block.slime_block.place", volume * 0.35F, 1.55F, globalSound);
                }
                case LIGHT -> {
                    playSound(player, location, "minecraft:entity.puffer_fish.blow_out", volume, pitch, globalSound);
                    playSound(player, location, "minecraft:block.slime_block.place", volume * 0.60F, pitch * 0.90F, globalSound);
                }
                case BUBBLY -> {
                    playSound(player, location, "minecraft:block.bubble_column.bubble_pop", volume, 0.55F, globalSound);
                    playSound(player, location, "minecraft:entity.puffer_fish.blow_out", volume * 0.80F, 0.78F, globalSound);
                    playSound(player, location, "minecraft:block.slime_block.place", volume * 0.70F, 0.62F, globalSound);
                }
                case MEDIUM -> {
                    playSound(player, location, "minecraft:entity.horse.breathe", volume, pitch * 0.72F, globalSound);
                    playSound(player, location, "minecraft:block.slime_block.place", volume * 0.90F, pitch * 0.62F, globalSound);
                }
                case WET -> {
                    playSound(player, location, "minecraft:entity.player.splash", volume * 0.65F, 0.48F, globalSound);
                    playSound(player, location, "minecraft:block.slime_block.place", volume, 0.32F, globalSound);
                    playSound(player, location, "minecraft:entity.puffer_fish.blow_out", volume * 0.75F, 0.44F, globalSound);
                }
                case LONG -> {
                    for (int i = 0; i < 4; i++) playSound(player, location, "minecraft:entity.puffer_fish.blow_out", volume * (0.72F + i * 0.07F), 0.52F - i * 0.06F, globalSound);
                    playSound(player, location, "minecraft:entity.horse.breathe", volume * 0.65F, 0.25F, globalSound);
                }
                case TRUMPET -> {
                    playSound(player, location, "minecraft:item.goat_horn.sound.0", volume * 0.35F, 1.75F, globalSound);
                    playSound(player, location, "minecraft:entity.puffer_fish.blow_out", volume, 0.44F, globalSound);
                    playSound(player, location, "minecraft:block.slime_block.place", volume * 0.85F, 0.35F, globalSound);
                }
                case THUNDER -> {
                    playSound(player, location, "minecraft:entity.lightning_bolt.thunder", volume * 0.28F, 1.85F, globalSound);
                    playSound(player, location, "minecraft:entity.horse.breathe", volume, 0.18F, globalSound);
                    playSound(player, location, "minecraft:block.slime_block.place", volume, 0.20F, globalSound);
                }
                case ULTRA -> {
                    playSound(player, location, "minecraft:entity.generic.explode", 0.55F, 1.70F, globalSound);
                    playSound(player, location, "minecraft:entity.puffer_fish.blow_out", volume, 0.22F, globalSound);
                    playSound(player, location, "minecraft:entity.horse.breathe", volume, 0.16F, globalSound);
                }
                case NUCLEAR -> {
                    playSound(player, location, "minecraft:entity.generic.explode", 0.85F, 0.72F, globalSound);
                    playSound(player, location, "minecraft:entity.lightning_bolt.thunder", 0.42F, 0.55F, globalSound);
                    playSound(player, location, "minecraft:entity.warden.sonic_boom", 0.40F, 1.70F, globalSound);
                    playSound(player, location, "minecraft:entity.puffer_fish.blow_out", volume, 0.12F, globalSound);
                }
                default -> { extendedAudio(player,level.id,level.ordinal(),false,globalSound,level.rare()); return; }
            }
        }
        if (particles) {
            int cloud = 3 + level.strength * 4;
            player.getWorld().spawnParticle(Particle.CLOUD, location, cloud, 0.25D + level.strength * 0.025D, 0.16D, 0.25D + level.strength * 0.025D, 0.02D);
            if (level.strength >= 6) player.getWorld().spawnParticle(Particle.EXPLOSION, location, level == FartLevel.NUCLEAR ? 4 : 2, 0.15D, 0.15D, 0.15D, 0.0D);
        }
    }

    private void playBurpEffects(Player player, BurpLevel level, boolean globalSound) {
        boolean sounds = plugin.getConfig().getBoolean("fun.burp.sounds", plugin.getConfig().getBoolean("fun.burp.effects",true));
        boolean particles = plugin.getConfig().getBoolean("fun.burp.particles", plugin.getConfig().getBoolean("fun.burp.effects",true));
        Location location = player.getLocation().clone().add(0.0D, 1.45D, 0.0D);
        if (sounds) {
            float volume = 0.70F + level.strength * 0.25F;
            switch (level) {
                case SMALL -> playSound(player, location, "minecraft:entity.player.burp", volume, 1.45F + random.nextFloat() * 0.18F, globalSound,true);
                case LOUD -> {
                    playSound(player, location, "minecraft:entity.player.burp", volume, 0.82F, globalSound,true);
                    playSound(player, location, "minecraft:entity.goat.screaming.eat", volume * 0.35F, 0.70F, globalSound,true);
                }
                case DEEP -> {
                    playSound(player, location, "minecraft:entity.player.burp", volume, 0.65F, globalSound,true);
                    playSound(player, location, "minecraft:entity.horse.breathe", volume * 0.62F, 0.60F, globalSound,true);
                }
                case MONSTER -> {
                    playSound(player, location, "minecraft:entity.player.burp", volume, 0.55F, globalSound,true);
                    playSound(player, location, "minecraft:entity.ravager.roar", volume * 0.25F, 0.65F, globalSound,true);
                    playSound(player, location, "minecraft:entity.warden.heartbeat", volume * 0.30F, 0.75F, globalSound,true);
                }
                default -> { extendedAudio(player,level.id,level.ordinal(),true,globalSound,level.rare()); return; }
            }
        }
        if (particles) {
            int count = 2 + level.strength * 2;
            player.getWorld().spawnParticle(Particle.CLOUD, location, count, 0.18D, 0.12D, 0.18D, 0.01D);
        }
    }

    private void playSound(Player source, Location sourceLocation, String sound, float volume, float pitch, boolean global) {
        playSound(source,sourceLocation,sound,volume,pitch,global,false);
    }
    private void playSound(Player source, Location sourceLocation, String sound, float volume, float pitch, boolean global,boolean burp) {
        if (!global) {
            CommunityAudio.play(source,source.getLocation(), sound, volume, pitch);
            return;
        }
        for (Player listener : Bukkit.getOnlinePlayers()) {
            if (!listener.getUniqueId().equals(source.getUniqueId()) && isMuted(listener.getUniqueId(), burp)) continue;
            CommunityAudio.play(listener,listener.getLocation(), sound, volume, pitch);
        }
    }

    void soundTest(Player admin){
        if(!plugin.has(admin,"simplexadminsuite.fun.admin"))return;
        admin.sendMessage(plugin.prefix()+"Hörtest: Rülpser → Atem → Glocke (Master-Lautstärke). Rülps-System: "
                +(burpEnabled()?"an":"aus")+"; Sounds: "+(plugin.getConfig().getBoolean("fun.burp.sounds",plugin.getConfig().getBoolean("fun.burp.effects",true))?"an":"aus")
                +". Der Hörtest spielt lokal, auch bei ausgeschalteten Suite-Sounds. Sind alle drei still, prüfe die Master-Lautstärke im Client.");
        String[] keys={"minecraft:entity.player.burp","minecraft:entity.horse.breathe","minecraft:block.note_block.bell"};
        for(int i=0;i<keys.length;i++){final String key=keys[i];Runnable play=()->{if(admin.isOnline())CommunityAudio.play(admin,admin.getLocation(),key,1.5F,1F);};if(i==0)play.run();else Bukkit.getScheduler().runTaskLater(plugin,play,i*20L);}
    }

    void openJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "funfeaturesmanager.openjava")) return;
        if (admin == null || !plugin.has(admin, "simplexadminsuite.fun.admin")) {
            if (admin != null) admin.sendMessage(plugin.prefix() + "Dafür fehlt dir simplexadminsuite.fun.admin.");
            return;
        }
        Inventory inventory = LiveMenus.create(new FunMenu(), 54, plugin.color("&8Fun & Community 7.0"));
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, plugin.item(Material.GRAY_STAINED_GLASS_PANE, " "));

        inventory.setItem(10, toggleItem(fartEnabled(), Material.SLIME_BALL, "&a/fart-System", "&7Aktiviert den öffentlichen Furzbefehl."));
        inventory.setItem(11, toggleItem("fun.fart.minecraft-broadcast", true, Material.PAPER, "&eFurz im Minecraft-Chat", "&7Meldung in der Sprache des jeweiligen Spielers."));
        inventory.setItem(12, toggleItem("fun.fart.discord-relay", true, Material.NAME_TAG, "&9Furz im Discord", "&7Englischer Embed im Chat-Relay."));
        inventory.setItem(13, toggleItem("fun.fart.sounds", true, Material.FIREWORK_ROCKET, "&dGlobale Furz-Sounds", "&7Jeder Online-Spieler hört den Sound einmal."));
        inventory.setItem(14, toggleItem("fun.fart.particles", true, Material.BLAZE_ROD, "&bFurz-Partikel", "&7Partikel erscheinen nur am Auslöser."));
        inventory.setItem(15, toggleItem("fun.fart.allow-player-variant-selection", true, Material.COMPASS, "&fFurz-Variantenwahl", "&7Spieler dürfen eine Variante auswählen."));
        inventory.setItem(16, plugin.item(Material.CLOCK, "&6Furz-Cooldown: &f" + fartCooldownSeconds() + " Sekunden", "&7Klick: 60 / 180 / 300 / 600 Sekunden"));

        inventory.setItem(19, toggleItem(burpEnabled(), Material.GOLDEN_APPLE, "&a/burp-System", "&7Aktiviert den öffentlichen Rülpsbefehl."));
        inventory.setItem(20, toggleItem("fun.burp.minecraft-broadcast", true, Material.PAPER, "&eRülpser im Minecraft-Chat", "&7Meldung in der Sprache des jeweiligen Spielers."));
        inventory.setItem(21, toggleItem("fun.burp.discord-relay", true, Material.NAME_TAG, "&9Rülpser im Discord", "&7Englischer Embed im Chat-Relay."));
        inventory.setItem(22, toggleItem("fun.burp.sounds", true, Material.FIREWORK_ROCKET, "&dGlobale Rülps-Sounds", "&7Jeder Online-Spieler hört den Sound einmal."));
        inventory.setItem(23, toggleItem("fun.burp.particles", true, Material.BLAZE_ROD, "&bRülps-Partikel", "&7Partikel erscheinen am Mund des Spielers."));
        inventory.setItem(24, toggleItem("fun.burp.allow-player-variant-selection", true, Material.COMPASS, "&fRülps-Variantenwahl", "&7Spieler dürfen small, loud, deep oder monster wählen."));
        inventory.setItem(25, plugin.item(Material.CLOCK, "&6Rülps-Cooldown: &f" + burpCooldownSeconds() + " Sekunden", "&7Klick: 60 / 180 / 300 / 600 Sekunden"));

        inventory.setItem(28, toggleItem("fun.shared-cooldown", false, Material.REDSTONE, "&cGemeinsamer Fun-Cooldown", "&7/fart und /burp teilen sich denselben Cooldown."));
        inventory.setItem(29, plugin.item(Material.MILK_BUCKET, "&bAlle Fun-Cooldowns löschen", "&7Alle Spieler können beide Befehle sofort erneut nutzen."));
        inventory.setItem(30, plugin.item(Material.BOOK, "&6Gesamtstatistik", "&7Fürze: &f" + totalFarts, "&7Rülpser: &f" + totalBurps, "&7Aktionen gesamt: &f" + (totalFarts + totalBurps)));
        inventory.setItem(31, plugin.item(Material.PAPER, "&fSpielerbefehle", "&7/fart [Variante|random]", "&7/burp [small|loud|deep|monster|random]"));
        inventory.setItem(32, toggleItem("fun.fart.global-sounds", true, Material.COMPASS, "&6Furz-Soundreichweite", "&7Aktiv: serverweit • Inaktiv: nur Auslöser"));
        inventory.setItem(33, toggleItem("fun.burp.global-sounds", true, Material.COMPASS, "&eRülps-Soundreichweite", "&7Aktiv: serverweit • Inaktiv: nur Auslöser"));

        int slot = 36;
        Material[] fartMaterials = {Material.FEATHER, Material.FEATHER, Material.SLIME_BALL, Material.SLIME_BALL, Material.WATER_BUCKET, Material.CLOCK, Material.FIREWORK_ROCKET, Material.TNT, Material.TNT, Material.NETHER_STAR};
        for (int i = 0; i < 10; i++) {
            FartLevel level = FartLevel.values()[i];
            inventory.setItem(slot + i, plugin.item(fartMaterials[i], "&6/fart " + level.id + " testen", "&7" + level.germanDisplay(), "&7Bisher: &f" + fartCount(level), "&8Nur lokale Vorschau"));
        }
        inventory.setItem(46, plugin.item(Material.GOLDEN_APPLE, "&a/burp small testen", "&7Bisher: &f" + burpCount(BurpLevel.SMALL), "&8Nur lokale Vorschau"));
        inventory.setItem(47, plugin.item(Material.GOLDEN_APPLE, "&e/burp loud testen", "&7Bisher: &f" + burpCount(BurpLevel.LOUD), "&8Nur lokale Vorschau"));
        inventory.setItem(48, plugin.item(Material.TOTEM_OF_UNDYING, "&6/burp deep testen", "&7Bisher: &f" + burpCount(BurpLevel.DEEP), "&8Nur lokale Vorschau"));
        inventory.setItem(49, plugin.item(Material.NETHER_STAR, "&c/burp monster testen", "&7Bisher: &f" + burpCount(BurpLevel.MONSTER), "&8Nur lokale Vorschau"));
        inventory.setItem(50,plugin.item(Material.SLIME_BALL,"&eAlle Furz-Vorschauen","&7Alle 30 Varianten, inklusive seltener Effekte"));
        inventory.setItem(51,plugin.item(Material.GOLDEN_APPLE,"&eAlle Rülps-Vorschauen","&7Alle 24 Varianten, inklusive seltener Effekte"));
        inventory.setItem(53, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zum Hauptmenü."));
        LiveMenus.show(admin,inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        plugin.asAdmin(event.getWhoClicked().getName(),()->handleAuditedInventoryClick(event));
    }
    private void handleAuditedInventoryClick(InventoryClickEvent event) {
        if (!(LiveMenus.holder(event.getInventory()) instanceof FunMenu) || !(event.getWhoClicked() instanceof Player admin)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if(slot==50){previews(admin,false);return;}
        if(slot==51){previews(admin,true);return;}
        if (slot == 10) toggle("fun.fart.enabled", true);
        else if (slot == 11) toggle("fun.fart.minecraft-broadcast", true);
        else if (slot == 12) toggle("fun.fart.discord-relay", true);
        else if (slot == 13) toggle("fun.fart.sounds", true);
        else if (slot == 14) toggle("fun.fart.particles", true);
        else if (slot == 15) toggle("fun.fart.allow-player-variant-selection", true);
        else if (slot == 16) cycleCooldown(false);
        else if (slot == 19) toggle("fun.burp.enabled", true);
        else if (slot == 20) toggle("fun.burp.minecraft-broadcast", true);
        else if (slot == 21) toggle("fun.burp.discord-relay", true);
        else if (slot == 22) toggle("fun.burp.sounds", true);
        else if (slot == 23) toggle("fun.burp.particles", true);
        else if (slot == 24) toggle("fun.burp.allow-player-variant-selection", true);
        else if (slot == 25) cycleCooldown(true);
        else if (slot == 28) toggle("fun.shared-cooldown", false);
        else if (slot == 32) toggle("fun.fart.global-sounds", true);
        else if (slot == 33) toggle("fun.burp.global-sounds", true);
        else if (slot == 29) { clearCooldowns(); plugin.menuNotice(admin,plugin.prefix() + "Alle /fart- und /burp-Cooldowns wurden gelöscht."); }
        else if (slot >= 36 && slot <= 45) performFartPreview(admin, FartLevel.values()[slot - 36]);
        else if (slot >= 46 && slot <= 49) performBurpPreview(admin, BurpLevel.values()[slot - 46]);
        else if (slot == 53) { plugin.openMainFromModule(admin); return; }
        else return;
        plugin.saveConfig();
        openJava(admin);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (LiveMenus.holder(event.getInventory()) instanceof FunMenu) event.setCancelled(true);
    }

    List<String> tabCompleteFart(String value) {
        List<String> options = new ArrayList<>();
        for (FartLevel level : FartLevel.values()) if(!level.rare()) options.add(level.id);
        options.add("random");
        options.add("toggle");
        return matching(options, value);
    }

    List<String> tabCompleteBurp(String value) {
        List<String> options = new ArrayList<>();
        for (BurpLevel level : BurpLevel.values()) if(!level.rare()) options.add(level.id);
        options.add("random");
        options.add("toggle");
        return matching(options, value);
    }

    private boolean togglePreference(UUID uuid, boolean burp) {
        Set<UUID> set = burp ? mutedBurps : mutedFarts;
        boolean muted;
        if (set.remove(uuid)) muted = false;
        else { set.add(uuid); muted = true; }
        savePreferences();
        return muted;
    }

    private boolean isMuted(UUID uuid, boolean burp) {
        return (burp ? mutedBurps : mutedFarts).contains(uuid);
    }

    private synchronized void loadPreferences() {
        mutedFarts.clear(); mutedBurps.clear();
        if (!preferencesFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(preferencesFile);
        for (String value : yaml.getStringList("muted-farts")) try { mutedFarts.add(UUID.fromString(value)); } catch (Exception ignored) { }
        for (String value : yaml.getStringList("muted-burps")) try { mutedBurps.add(UUID.fromString(value)); } catch (Exception ignored) { }
    }

    private synchronized void savePreferences() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", 1);
            yaml.set("muted-farts", mutedFarts.stream().map(UUID::toString).sorted().toList());
            yaml.set("muted-burps", mutedBurps.stream().map(UUID::toString).sorted().toList());
            File temporary = new File(preferencesFile.getParentFile(), preferencesFile.getName() + ".tmp");
            yaml.save(temporary);
            try { Files.move(temporary.toPath(), preferencesFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception unavailable) { Files.move(temporary.toPath(), preferencesFile.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception error) {
            plugin.getLogger().warning("Fun-Einstellungen der Spieler konnten nicht gespeichert werden: " + error.getMessage());
        }
    }

    private List<String> matching(List<String> options, String value) {
        String needle = value == null ? "" : value.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) if (option.startsWith(needle)) result.add(option);
        return result;
    }

    boolean fartEnabled() { return plugin.getConfig().getBoolean("fun.fart.enabled", true); }
    boolean burpEnabled() { return plugin.getConfig().getBoolean("fun.burp.enabled", true); }
    int fartCooldownSeconds() { return Math.max(1, plugin.getConfig().getInt("fun.fart.cooldown-seconds", 300)); }
    int burpCooldownSeconds() { return Math.max(1, plugin.getConfig().getInt("fun.burp.cooldown-seconds", 300)); }
    boolean sharedCooldown() { return plugin.getConfig().getBoolean("fun.shared-cooldown", false); }
    long totalFarts() { return totalFarts; }
    long totalBurps() { return totalBurps; }
    long fartCount(FartLevel level) { return fartCounts.getOrDefault(level, 0L); }
    long burpCount(BurpLevel level) { return burpCounts.getOrDefault(level, 0L); }

    // Compatibility helpers used by existing menu/test code.
    boolean enabled() { return fartEnabled(); }
    int cooldownSeconds() { return fartCooldownSeconds(); }
    long lightFarts() { return fartCount(FartLevel.LIGHT); }
    long mediumFarts() { return fartCount(FartLevel.MEDIUM); }
    long ultraFarts() { return fartCount(FartLevel.ULTRA); }
    void performPreview(Player admin, FartLevel level) { performFartPreview(admin, level); }

    void toggleSetting(String path, boolean fallback) { toggle(path, fallback); plugin.saveConfig(); }
    void clearCooldowns() { fartCooldowns.clear(); burpCooldowns.clear(); }
    void cycleCooldownSetting() { cycleCooldown(false); plugin.saveConfig(); }
    void cycleBurpCooldownSetting() { cycleCooldown(true); plugin.saveConfig(); }

    private Map<UUID, Long> cooldownMap(boolean burp) {
        if (sharedCooldown()) return fartCooldowns;
        return burp ? burpCooldowns : fartCooldowns;
    }

    private void putCooldown(UUID uuid, long until, boolean burp) {
        cooldownMap(burp).put(uuid, until);
    }

    private long cooldownRemaining(Map<UUID, Long> map, UUID uuid, long now) {
        Long until = map.get(uuid);
        if (until == null || until <= now) { map.remove(uuid); return 0L; }
        return Math.max(1L, (until - now + 999L) / 1000L);
    }

    private long fartCooldownMillis() { return fartCooldownSeconds() * 1000L; }
    private long burpCooldownMillis() { return burpCooldownSeconds() * 1000L; }

    private boolean toggle(String path, boolean fallback) {
        boolean value = !plugin.getConfig().getBoolean(path, fallback);
        plugin.getConfig().set(path, value);
        return value;
    }

    private void cycleCooldown(boolean burp) {
        String path = burp ? "fun.burp.cooldown-seconds" : "fun.fart.cooldown-seconds";
        int current = burp ? burpCooldownSeconds() : fartCooldownSeconds();
        int next = current < 60 ? 60 : current < 180 ? 180 : current < 300 ? 300 : current < 600 ? 600 : 60;
        plugin.getConfig().set(path, next);
    }

    private org.bukkit.inventory.ItemStack toggleItem(boolean value, Material active, String name, String... lore) {
        List<String> lines = new ArrayList<>(List.of(lore));
        lines.add("&7Status: " + (value ? "&aAktiv" : "&cInaktiv"));
        return plugin.item(value ? active : Material.GRAY_DYE, name, lines.toArray(new String[0]));
    }

    private org.bukkit.inventory.ItemStack toggleItem(String path, boolean fallback, Material active, String name, String... lore) {
        return toggleItem(plugin.getConfig().getBoolean(path, fallback), active, name, lore);
    }

    private FartLevel parseFart(String value) {
        String normalized = value == null ? "random" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("random") || normalized.equals("zufall")) return randomFart();
        for (FartLevel level : FartLevel.values()) if (level.id.equals(normalized)) return level;
        return null;
    }

    private BurpLevel parseBurp(String value) {
        String normalized = value == null ? "random" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("random") || normalized.equals("zufall")) return randomBurp();
        for (BurpLevel level : BurpLevel.values()) if (level.id.equals(normalized)) return level;
        return null;
    }

    private FartLevel randomFart() {
        FartLevel[] rare=java.util.Arrays.stream(FartLevel.values()).filter(FartLevel::rare).toArray(FartLevel[]::new);
        FartLevel[] normal=java.util.Arrays.stream(FartLevel.values()).filter(l->!l.rare()).toArray(FartLevel[]::new);
        // Total rare probability = 1%, three equally likely rare variants.
        return random.nextInt(1000)<10?rare[random.nextInt(rare.length)]:normal[random.nextInt(normal.length)];
    }

    private BurpLevel randomBurp() {
        BurpLevel[] rare=java.util.Arrays.stream(BurpLevel.values()).filter(BurpLevel::rare).toArray(BurpLevel[]::new);
        BurpLevel[] normal=java.util.Arrays.stream(BurpLevel.values()).filter(l->!l.rare()).toArray(BurpLevel[]::new);
        // Total rare probability = 1%, three equally likely rare variants.
        return random.nextInt(1000)<10?rare[random.nextInt(rare.length)]:normal[random.nextInt(normal.length)];
    }

    private String publicMessage(String group, String key, String fallback) {
        String value = plugin.getConfig().getString("fun." + group + "." + key, fallback);
        return value == null || value.isBlank() ? fallback : value;
    }

    private void saveStatistics() {
        plugin.getConfig().set("fun.fart.statistics.total", totalFarts);
        plugin.getConfig().set("fun.fart.statistics.light", fartCount(FartLevel.LIGHT));
        plugin.getConfig().set("fun.fart.statistics.medium", fartCount(FartLevel.MEDIUM));
        plugin.getConfig().set("fun.fart.statistics.ultra", fartCount(FartLevel.ULTRA));
        for (FartLevel level : FartLevel.values()) plugin.getConfig().set("fun.fart.statistics.variants." + level.id, fartCount(level));
        plugin.getConfig().set("fun.burp.statistics.total", totalBurps);
        for (BurpLevel level : BurpLevel.values()) plugin.getConfig().set("fun.burp.statistics." + level.id, burpCount(level));
        plugin.saveConfig();
    }
}
