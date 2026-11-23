package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** 10.23.0: server sound modes, personal mute data, versioned plugin files, server updater checks and Discord modal format. */
public final class Release1230Harness {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        // Sound modes and persisted state (old chat-silence.yml entries stay valid: no "sounds" key = "off").
        check(ChatSilence.normalizeSoundMode("alle").equals("all") && ChatSilence.normalizeSoundMode("Stille").equals("all"), "german silence");
        check(ChatSilence.normalizeSoundMode("chat").equals("chat") && ChatSilence.normalizeSoundMode("plugins").equals("plugins"), "chat/plugins");
        check(ChatSilence.normalizeSoundMode(null).equals("off") && ChatSilence.normalizeSoundMode("quatsch").equals("off"), "unknown = off");
        var legacy = new ChatSilence.State(true, 0, false, false, "Steve", "self", 1);
        check(legacy.sounds().equals("off") && legacy.soundLevel() == 0, "legacy constructor keeps sounds off");
        var quiet = ChatSilence.State.defaults("Steve").withSounds("plugins");
        check(quiet.worthKeeping() && !quiet.chat() && quiet.soundLevel() == 2, "sound mode alone is stored");
        check(quiet.muted(0, "self", 1, "Steve").sounds().equals("plugins") && quiet.options(true, true).sounds().equals("plugins") && quiet.unmuted("x", 2, "Steve").sounds().equals("plugins"), "sound mode survives chat changes");
        check(quiet.renamed("Alex").name().equals("Alex") && quiet.renamed("Alex").sounds().equals("plugins"), "rename keeps sounds");

        ChatSilence.Gate gate = new ChatSilence.Gate();
        long now = 1_000_000;
        check(ChatSilence.allowSound(gate, 0, false, "minecraft:block.note_block.pling", "master", now), "mode off lets everything through");
        check(!ChatSilence.allowSound(gate, 3, false, "minecraft:entity.cow.ambient", "neutral", now), "silence drops world sounds");
        check(!ChatSilence.allowSound(gate, 2, false, "minecraft:block.note_block.pling", "records", now), "plugins mode drops notification sounds");
        check(!ChatSilence.allowSound(gate, 2, false, "minecraft:entity.generic.explode", "master", now), "plugins mode drops MASTER sounds");
        check(ChatSilence.allowSound(gate, 2, false, "minecraft:entity.zombie.ambient", "hostile", now), "plugins mode keeps the world audible");
        gate.lastChat = now - 200;
        check(!ChatSilence.allowSound(gate, 1, false, "minecraft:entity.experience_orb.pickup", "master", now), "chat mode drops a ping right after chat");
        gate.lastChat = now - 5_000;
        check(ChatSilence.allowSound(gate, 1, false, "minecraft:entity.experience_orb.pickup", "players", now), "chat mode keeps pings without chat");
        check(ChatSilence.allowSound(gate, 1, false, "minecraft:entity.zombie.ambient", "hostile", now - 4_900), "chat mode never drops world sounds");
        check(ChatSilence.soundLabelDe("all").startsWith("Alle Server-Töne"), "german label");

        // Versioned plugin file names (Paper adopts the staged name).
        check(PluginFileNaming.versionedName("LuckPerms", "5.4.150").equals("LuckPerms-5.4.150.jar"), "simple versioned name");
        check(PluginFileNaming.versionedName("Geyser Spigot", "2.12.1-SNAPSHOT (b900)").equals("Geyser-Spigot-2.12.1-SNAPSHOT-b900.jar"), "unsafe characters");
        check(PluginFileNaming.versionedName("", "").equals("Plugin.jar"), "fallback");
        check(PluginFileNaming.versionedName("../evil", "1/2").equals("evil-1-2.jar"), "no path traversal");
        check(PluginFileNaming.carriesVersion("LuckPerms-5.4.150.jar", "5.4.150") && !PluginFileNaming.carriesVersion("LuckPerms.jar", "5.4.150"), "version detection");

        // Server updater: only real, startable Paper JARs are accepted.
        Path dir = Files.createTempDirectory("sas-1230");
        Path good = dir.resolve("paper.jar"), bad = dir.resolve("bad.jar"), notJar = dir.resolve("text.jar");
        writeJar(good, "io.papermc.paperclip.Main", true);
        writeJar(bad, null, false);
        Files.writeString(notJar, "kein jar");
        ServerUpdater.verifyJar(good);
        check(rejects(bad) && rejects(notJar), "invalid server jars are rejected");
        check(ServerUpdater.hash(good).equals(AppPluginFiles.hash(good)) && ServerUpdater.hash(good).length() == 64, "sha-256");

        // Discord: Label component wraps text inputs; values are still found; obfuscated channels are skipped.
        Map<String, Object> input = new LinkedHashMap<>(Map.of("type", 4, "custom_id", "command", "label", "Serverbefehl", "style", 1));
        Map<String, Object> field = DiscordBotManager.modalField(input);
        check(Integer.valueOf(18).equals(field.get("type")) && "Serverbefehl".equals(field.get("label")), "label wrapper");
        check(field.get("component") instanceof Map<?, ?> child && !child.containsKey("label") && "command".equals(child.get("custom_id")), "input without label inside");
        check(!DiscordBotManager.visibleChannel(Map.of("name", "___hidden___", "flags", 131072)) && DiscordBotManager.visibleChannel(Map.of("name", "general", "flags", 0)), "obfuscated channels");
        check(DiscordBotManager.roleColor(Map.of("colors", Map.of("primary_color", 255), "color", 1)) == 255 && DiscordBotManager.roleColor(Map.of("color", 7)) == 7, "role colors");

        // Web panel ships the new module and navigation.
        String index = Files.readString(Path.of("src/main/resources/web/index.html"), StandardCharsets.UTF_8);
        String app = Files.readString(Path.of("src/main/resources/web/app.js"), StandardCharsets.UTF_8);
        String worker = Files.readString(Path.of("src/main/resources/web/sw.js"), StandardCharsets.UTF_8);
        check(index.contains("/assets/upkeep.js") && worker.contains("/assets/upkeep.js"), "upkeep.js loaded and cached");
        check(app.contains("\"anti-lag\"") && app.contains("\"server-updater\""), "navigation entries");
        String config = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        check(config.contains("allow-minecraft-upgrade: false") && config.contains("mode: smart"), "safe defaults");
        System.out.println("PASS " + checks + " 10.23.0 sounds, mutes, plugin names, updater and Discord checks");
    }

    static boolean rejects(Path path) {
        try { ServerUpdater.verifyJar(path); return false; } catch (IOException expected) { return true; }
    }

    static void writeJar(Path path, String main, boolean versionJson) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (main != null) manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (versionJson) { out.putNextEntry(new JarEntry("version.json")); out.write("{\"id\":\"26.3\"}".getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
    }
}
