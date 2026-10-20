package eu.simplexsmp.adminsuite;

import java.util.Locale;

/** Native Bedrock surfaces: black headers/buttons, light body panels; white Java tooltips. */
final class MenuStyle {
  /** Applied only to navigation windows owned by LiveMenus, never to player inventories. */
  static void uniformInventory(org.bukkit.inventory.Inventory inventory) {
    for (int slot=0;slot<inventory.getSize();slot++) {
      var stack=inventory.getItem(slot);
      if(stack==null||!stack.hasItemMeta())continue;
      var meta=stack.getItemMeta();
      if(stack.getType().name().endsWith("STAINED_GLASS_PANE")&&plain(meta.getDisplayName()).isBlank()) {
        stack=stack.clone();stack.setType(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        meta=stack.getItemMeta();meta.setDisplayName(" ");meta.setLore(java.util.List.of());
        stack.setItemMeta(meta);inventory.setItem(slot,stack);
      }
    }
  }
  static String plain(String value) {
    return value == null ? "" : value.replaceAll("(?i)[§&][0-9a-fk-or]", "");
  }

  /** Short window header; complete labels stay in the menu body / item lore. */
  static String inventoryTitle(String value) {
    String text=plain(value).replaceAll("[\\p{Cntrl}]", " ").strip();
    int width=0,chars=0,end=0;
    for(int at=0;at<text.length();){int cp=text.codePointAt(at),next=at+Character.charCount(cp);int glyph=cp>0xffff?12:cp>0x7f?8:cp==' '?4:"il.,:!|".indexOf(cp)>=0?3:6;
      if(chars==30||next>30||next>29&&next<text.length()||width+glyph>144)break;width+=glyph;chars++;end=next;at=next;}
    return "§8"+(end==text.length()?text:text.substring(0,end).stripTrailing()+"…");
  }

  static String bedrock(String value) {
    return "§0" + plain(value).replace("\n", "\n§0");
  }

  static String javaText(String value) {
    String color = danger(value) ? "§c" : "§f";
    return color + plain(value).replace("\n", "\n" + color);
  }

  static boolean danger(String value) {
    String title = plain(value).split("\n", 2)[0].strip().toLowerCase(Locale.ROOT);
    return title.matches(
        ".*(?:\\b(?:löschen|delete|entfernen|zurücksetzen)\\b|sperre"
            + " hinzufügen|fehlgeschlagen|failed|nicht verfügbar|unavailable).*");
  }

  /** Dark content panel: white by default, retaining explicit success/error indicators. */
  static String body(String value) {
    String text = value == null ? "" : value;
    text = text.replaceAll("(?i)[§&]x(?:[§&][0-9a-f]){6}", "");
    var matcher = java.util.regex.Pattern.compile("(?i)[§&]([0-9a-fk-or])").matcher(text);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      char code = Character.toLowerCase(matcher.group(1).charAt(0));
      String replacement =
          switch (code) {
            case 'c', '4' -> "§c";
            case 'a', '2' -> "§a";
            case 'l', 'm', 'n', 'o' -> "§" + code;
            case 'k' -> "";
            case 'r' -> "§r§f";
            default -> "§f";
          };
      matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return "§f" + result;
  }

  /** Every line on light native buttons is black, including status and relative times. */
  static String button(String value) {
    return danger(value) ? "§c" + plain(value).replace("\n", "\n§c") : bedrock(value);
  }

  static String dimensionIcon(String environment) {
    return switch (environment) {
      case "NETHER" -> "textures/ui/NetherPortal";
      case "THE_END" -> "textures/blocks/end_stone";
      default -> "textures/blocks/grass_side_carried";
    };
  }

  static org.bukkit.Material dimensionMaterial(org.bukkit.World.Environment environment) {
    return switch (environment) {
      case NETHER -> org.bukkit.Material.NETHERRACK;
      case THE_END -> org.bukkit.Material.END_STONE;
      case NORMAL -> org.bukkit.Material.GRASS_BLOCK;
      default -> org.bukkit.Material.AMETHYST_BLOCK;
    };
  }

  static String icon(String value) {
    String title = plain(value).split("\n", 2)[0].toLowerCase(Locale.ROOT);
    if (title.contains("zurück")
        || title.contains("vorherige")
        || title.equals("back")
        || title.contains("previous page")) return "textures/ui/chevron_left";
    if (title.contains("nächste") || title.contains("next page"))
      return "textures/ui/chevron_right";
    if (title.contains("nether")) return dimensionIcon("NETHER");
    if (title.matches(".*\\b(end|ende|endwelt)\\b.*")) return dimensionIcon("THE_END");
    if (title.matches(".*(oberwelt|overworld).*")) return dimensionIcon("NORMAL");
    if (title.matches(".*(gewitter|donner).*")) return "textures/ui/weather_thunderstorm";
    if (title.contains("regen")) return "textures/ui/weather_rain";
    if (title.matches(".*(wetter|tageslicht|mittag).*")) return "textures/ui/weather_clear";
    if (title.contains("morgen")) return "textures/ui/time_1sunrise";
    if (title.contains("abend")) return "textures/ui/time_4sunset";
    if (title.matches(".*(nacht|schlaf).*")) return "textures/ui/time_5night";
    if (title.matches(".*(backup|sicherung|snapshot|wiederherstellen).*"))
      return "textures/ui/backup_replace";
    if (title.matches(".*(speichern|autosave).*")) return "textures/ui/auto_save";
    if (title.matches(".*(inventar|enderchest|endertruhe).*")) return "textures/ui/inventory_icon";
    if (title.matches(".*(suche|suchen|filter).*")) return "textures/ui/magnifyingGlass";
    if (title.matches(".*(heilen|heilung|gesundheit|heal).*"))
      return "textures/ui/health_boost_effect";
    if (title.matches(".*(notiz|buch|bücher|schild).*")) return "textures/ui/book_edit_default";
    if (title.matches(".*(neu laden|reload|refresh|aktualisieren|synchronisieren).*"))
      return "textures/ui/refresh";
    if (title.matches(".*(spielmodus|kreativ|creative).*")) return "textures/ui/creative_icon";
    if (title.matches(".*(pvp|kampf).*")) return "textures/items/iron_sword";
    if (title.matches(".*(teleport|spawn|warp).*")) return "textures/items/compass_item";
    if (title.matches(".*(klang|sound|rückmeldung|ton).*")) return "textures/blocks/noteblock";
    if (title.contains("gamerule")) return "textures/blocks/command_block";
    if (title.matches(".*(bestätigen|übernehmen|freigeben|entbannen|robot).*"))
      return "textures/ui/confirm";
    if (title.matches(".*(warn|ban|sperr|abbrechen|schließen|lösch).*"))
      return "textures/ui/Caution";
    if (title.contains("server")) return "textures/ui/servers";
    if (title.matches(".*(zeit|dauer|ablauf|timer).*")) return "textures/ui/clock";
    if (title.matches(".*(recht|rolle|berechtigung).*")) return "textures/ui/permissions_op_crown";
    if (title.contains("stummschalt")) return "textures/ui/mute_on";
    if (title.contains("chat")) return "textures/ui/chat_keyboard";
    if (title.contains("welt")) return "textures/ui/glyph_world_template";
    if (title.contains("spieler") || title.contains("konto") || title.contains("profil"))
      return "textures/ui/glyph_skin_pack";
    if (title.matches(".*(einstellung|gerät|betriebssystem|konfiguration|steuerung).*"))
      return "textures/ui/icon_setting";
    if (title.matches(".*(info|status|protokoll|chat|discord|ki).*")) return "textures/ui/infobulb";
    return "textures/ui/icon_setting";
  }

  /** Improve generic menu markers while preserving intentional heads, dyes and equipment. */
  static org.bukkit.Material actionMaterial(String value, org.bukkit.Material fallback) {
    if (fallback != org.bukkit.Material.PAPER
        && fallback != org.bukkit.Material.BOOK
        && fallback != org.bukkit.Material.MAP
        && fallback != org.bukkit.Material.COMPARATOR) return fallback;
    String title = plain(value).toLowerCase(Locale.ROOT);
    if (title.contains("nether")) return org.bukkit.Material.NETHERRACK;
    if (title.matches(".*\\b(end|endwelt)\\b.*")) return org.bukkit.Material.END_STONE;
    if (title.matches(".*(gewitter|donner).*")) return org.bukkit.Material.LIGHTNING_ROD;
    if (title.matches(".*(wetter|tageslicht).*")) return org.bukkit.Material.SUNFLOWER;
    if (title.contains("schlaf")) return org.bukkit.Material.RED_BED;
    if (title.matches(".*(backup|sicherung|snapshot).*")) return org.bukkit.Material.ENDER_CHEST;
    if (title.matches(".*(sound|rückmeldung|klang).*")) return org.bukkit.Material.NOTE_BLOCK;
    if (title.matches(".*(heilen|heilung|gesundheit).*")) return org.bukkit.Material.GOLDEN_APPLE;
    if (title.matches(".*(teleport|spawn|warp).*")) return org.bukkit.Material.COMPASS;
    if (title.contains("gamerule")) return org.bukkit.Material.COMMAND_BLOCK;
    return fallback;
  }
}
