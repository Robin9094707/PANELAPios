package eu.simplexsmp.adminsuite;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import javax.imageio.ImageIO;

/**
 * 10.15.0: item icons like the game shows them. Order: entity items (chests, beds, banners, shields, heads) drawn
 * from their entity textures, then direct item/block textures, then the item model chain of the resource pack
 * (items/*.json → models/*.json → parents → textures). Animated textures are cut to their first frame and
 * grey foliage textures are tinted. `asset` returns bytes of a path from the custom pack first, then vanilla.
 */
final class ItemIconRenderer {
    private static final String ROOT = "assets/minecraft/";
    private static final Set<String> COLORS = Set.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
        "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final List<String> TEXTURE_KEYS = List.of("layer0", "front", "side", "all", "top", "texture", "particle", "end", "cross", "plant",
        "crop", "pattern", "wall", "fan", "flowerpot", "torch", "lantern", "bars", "pane", "edge", "inside", "rail", "up", "north", "south", "east", "west", "bottom");
    private final Function<String, byte[]> asset;

    ItemIconRenderer(Function<String, byte[]> asset) { this.asset = asset; }

    /** PNG bytes of the icon, or null when nothing suitable exists. */
    byte[] render(String item) {
        try {
            BufferedImage image = special(item);
            if (image == null) image = direct(item);
            if (image == null) image = modelChain(item);
            return image == null ? null : png(tint(item, image));
        } catch (IOException | RuntimeException broken) { return null; }
    }

    // ------------------------------------------------------------------ entity items

    private BufferedImage special(String item) throws IOException {
        String base = item.startsWith("waxed_") ? item.substring(6) : item;
        switch (base) {
            case "chest": return chest("entity/chest/normal");
            case "trapped_chest": return chest("entity/chest/trapped");
            case "ender_chest": return chest("entity/chest/ender");
            case "copper_chest": return chest("entity/chest/copper");
            case "exposed_copper_chest": return chest("entity/chest/copper_exposed");
            case "weathered_copper_chest": return chest("entity/chest/copper_weathered");
            case "oxidized_copper_chest": return chest("entity/chest/copper_oxidized");
            case "shield": return crop(texture("entity/shield_base_nopattern"), 1, 1, 12, 22, 64);
            case "player_head": return head("entity/player/wide/steve", true);
            case "zombie_head": return head("entity/zombie/zombie", true);
            case "skeleton_skull": return head("entity/skeleton/skeleton", false);
            case "wither_skeleton_skull": return head("entity/skeleton/wither_skeleton", false);
            case "creeper_head": return head("entity/creeper/creeper", false);
            case "piglin_head": return head("entity/piglin/piglin", false);
            default: break;
        }
        if (base.endsWith("_bed") && COLORS.contains(base.substring(0, base.length() - 4))) return crop(texture("entity/bed/" + base.substring(0, base.length() - 4)), 6, 6, 16, 16, 64);
        if (base.endsWith("_banner") && COLORS.contains(base.substring(0, base.length() - 7))) return banner(base.substring(0, base.length() - 7));
        return null;
    }

    private BufferedImage chest(String path) throws IOException {
        BufferedImage atlas = texture(path);
        if (atlas == null) return null;
        int s = Math.max(1, atlas.getWidth() / 64);
        BufferedImage out = new BufferedImage(16 * s, 16 * s, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        // lid front, body front, then the latch on top of the seam
        g.drawImage(atlas.getSubimage(14 * s, 14 * s, 14 * s, 5 * s), s, s, null);
        g.drawImage(atlas.getSubimage(14 * s, 33 * s, 14 * s, 10 * s), s, 6 * s, null);
        g.drawImage(atlas.getSubimage(s, s, 2 * s, 4 * s), 7 * s, 4 * s, null);
        g.dispose();
        return out;
    }

    private BufferedImage head(String path, boolean hat) throws IOException {
        BufferedImage skin = texture(path);
        if (skin == null) return null;
        int s = Math.max(1, skin.getWidth() / 64);
        BufferedImage out = new BufferedImage(16 * s, 16 * s, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.drawImage(skin.getSubimage(8 * s, 8 * s, 8 * s, 8 * s), 0, 0, 16 * s, 16 * s, null);
        if (hat && skin.getHeight() >= 16 * s) g.drawImage(skin.getSubimage(40 * s, 8 * s, 8 * s, 8 * s), 0, 0, 16 * s, 16 * s, null);
        g.dispose();
        return out;
    }

    private BufferedImage banner(String color) throws IOException {
        BufferedImage wool = texture("block/" + color + "_wool");
        if (wool == null) return null;
        wool = firstFrame(wool);
        int s = Math.max(1, wool.getWidth() / 16);
        BufferedImage out = new BufferedImage(16 * s, 16 * s, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.drawImage(wool.getSubimage(3 * s, 0, 10 * s, 13 * s), 3 * s, s, null);
        g.setColor(new java.awt.Color(0x6b, 0x51, 0x30));
        g.fillRect(2 * s, 0, 12 * s, s);
        g.fillRect(7 * s, 14 * s, 2 * s, 2 * s);
        g.dispose();
        return out;
    }

    private static BufferedImage crop(BufferedImage image, int x, int y, int w, int h, int base) {
        if (image == null) return null;
        int s = Math.max(1, image.getWidth() / base);
        if ((x + w) * s > image.getWidth() || (y + h) * s > image.getHeight()) return null;
        BufferedImage part = image.getSubimage(x * s, y * s, w * s, h * s);
        int size = Math.max(w, h) * s;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.drawImage(part, (size - w * s) / 2, (size - h * s) / 2, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ direct textures

    private BufferedImage direct(String item) throws IOException {
        for (String candidate : new String[]{item, item + "_top", item + "_front", item + "_side"})
            for (String folder : List.of("item/", "block/")) {
                BufferedImage image = texture(folder + candidate);
                if (image != null) return firstFrame(image);
            }
        return null;
    }

    // ------------------------------------------------------------------ model chain

    private BufferedImage modelChain(String item) throws IOException {
        String model = null;
        byte[] definition = asset.apply(ROOT + "items/" + item + ".json");
        if (definition != null) model = firstModel(DiscordBotManager.Json.parse(new String(definition, java.nio.charset.StandardCharsets.UTF_8)));
        if (model == null) model = "item/" + item;
        Map<String, String> textures = new LinkedHashMap<>();
        String current = model;
        for (int depth = 0; depth < 12 && current != null; depth++) {
            byte[] json = asset.apply(ROOT + "models/" + strip(current) + ".json");
            if (json == null) break;
            Object parsed = DiscordBotManager.Json.parse(new String(json, java.nio.charset.StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> map)) break;
            if (map.get("textures") instanceof Map<?, ?> found) found.forEach((k, v) -> textures.putIfAbsent(String.valueOf(k), String.valueOf(v)));
            current = map.get("parent") instanceof String parent && !parent.startsWith("builtin/") ? parent : null;
        }
        if (textures.isEmpty()) return null;
        List<String> keys = new ArrayList<>(TEXTURE_KEYS);
        textures.keySet().forEach(k -> { if (!keys.contains(k)) keys.add(k); });
        for (String key : keys) {
            String value = resolve(textures, textures.get(key));
            if (value == null) continue;
            BufferedImage image = texture(strip(value));
            if (image != null) return firstFrame(image);
        }
        return null;
    }

    static String firstModel(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (String key : List.of("base", "model")) if (map.get(key) instanceof String value && value.contains("/")) return value;
            for (Object value : map.values()) { String found = firstModel(value); if (found != null) return found; }
        } else if (node instanceof List<?> list) for (Object value : list) { String found = firstModel(value); if (found != null) return found; }
        return null;
    }

    private static String resolve(Map<String, String> textures, String value) {
        for (int i = 0; i < 8 && value != null && value.startsWith("#"); i++) value = textures.get(value.substring(1));
        return value == null || value.startsWith("#") ? null : value;
    }

    private static String strip(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    // ------------------------------------------------------------------ images

    private BufferedImage texture(String path) throws IOException {
        byte[] bytes = asset.apply(ROOT + "textures/" + path + ".png");
        if (bytes == null || bytes.length > 1024 * 1024) return null;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() < 1 || image.getWidth() > 512 || image.getHeight() > 8192) return null;
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = argb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return argb;
    }

    /** Animated textures are vertical strips; the icon uses the first frame. */
    static BufferedImage firstFrame(BufferedImage image) {
        return image.getHeight() > image.getWidth() ? image.getSubimage(0, 0, image.getWidth(), image.getWidth()) : image;
    }

    private static final Set<String> FOLIAGE_EXCEPTIONS = Set.of("cherry_leaves", "azalea_leaves", "flowering_azalea_leaves", "pale_oak_leaves");

    /** Grass and leaves are stored grey and tinted by the biome in game; use the default plains colours. */
    static BufferedImage tint(String item, BufferedImage image) {
        int color;
        if (item.endsWith("_leaves") && !FOLIAGE_EXCEPTIONS.contains(item)) color = item.startsWith("spruce") ? 0x619961 : item.startsWith("birch") ? 0x80A755 : 0x48B518;
        else if (Set.of("grass_block", "short_grass", "tall_grass", "fern", "large_fern", "vine", "lily_pad", "bush").contains(item)) color = 0x7CBD6B;
        else return image;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int r = color >> 16 & 0xff, gr = color >> 8 & 0xff, b = color & 0xff;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int p = image.getRGB(x, y), a = p >>> 24;
            int pr = p >> 16 & 0xff, pg = p >> 8 & 0xff, pb = p & 0xff;
            // Only tint grey pixels; coloured overlay pixels (e.g. dirt on the grass block side) stay as they are.
            boolean grey = Math.abs(pr - pg) < 12 && Math.abs(pg - pb) < 12;
            out.setRGB(x, y, grey ? a << 24 | (pr * r / 255) << 16 | (pg * gr / 255) << 8 | (pb * b / 255) : p);
        }
        return out;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
