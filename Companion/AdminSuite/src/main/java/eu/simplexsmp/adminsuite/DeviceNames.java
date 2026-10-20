package eu.simplexsmp.adminsuite;

import java.util.*;
import java.util.regex.*;

/** Offline model catalog. Client labels remain self-reported; never infer unreported hardware. */
final class DeviceNames {
  private static final Map<String, String> APPLE = new HashMap<>();

  private static void add(String name, String... ids) {
    for (String id : ids) APPLE.put(id.toLowerCase(Locale.ROOT), name);
  }

  static {
    add("iPhone", "iPhone1,1");
    add("iPhone 3G", "iPhone1,2");
    add("iPhone 3GS", "iPhone2,1");
    add("iPhone 4", "iPhone3,1", "iPhone3,2", "iPhone3,3");
    add("iPhone 4s", "iPhone4,1");
    add("iPhone 5", "iPhone5,1", "iPhone5,2");
    add("iPhone 5c", "iPhone5,3", "iPhone5,4");
    add("iPhone 5s", "iPhone6,1", "iPhone6,2");
    add("iPhone 6", "iPhone7,2");
    add("iPhone 6 Plus", "iPhone7,1");
    add("iPhone 6s", "iPhone8,1");
    add("iPhone 6s Plus", "iPhone8,2");
    add("iPhone SE (1st generation)", "iPhone8,4");
    add("iPhone 7", "iPhone9,1", "iPhone9,3");
    add("iPhone 7 Plus", "iPhone9,2", "iPhone9,4");
    add("iPhone 8", "iPhone10,1", "iPhone10,4");
    add("iPhone 8 Plus", "iPhone10,2", "iPhone10,5");
    add("iPhone X", "iPhone10,3", "iPhone10,6");
    add("iPhone XS", "iPhone11,2");
    add("iPhone XS Max", "iPhone11,4", "iPhone11,6");
    add("iPhone XR", "iPhone11,8");
    add("iPhone 11", "iPhone12,1");
    add("iPhone 11 Pro", "iPhone12,3");
    add("iPhone 11 Pro Max", "iPhone12,5");
    add("iPhone SE (2nd generation)", "iPhone12,8");
    add("iPhone 12 mini", "iPhone13,1");
    add("iPhone 12", "iPhone13,2");
    add("iPhone 12 Pro", "iPhone13,3");
    add("iPhone 12 Pro Max", "iPhone13,4");
    add("iPhone 13 mini", "iPhone14,4");
    add("iPhone 13", "iPhone14,5");
    add("iPhone 13 Pro", "iPhone14,2");
    add("iPhone 13 Pro Max", "iPhone14,3");
    add("iPhone SE (3rd generation)", "iPhone14,6");
    add("iPhone 14", "iPhone14,7");
    add("iPhone 14 Plus", "iPhone14,8");
    add("iPhone 14 Pro", "iPhone15,2");
    add("iPhone 14 Pro Max", "iPhone15,3");
    add("iPhone 15", "iPhone15,4");
    add("iPhone 15 Plus", "iPhone15,5");
    add("iPhone 15 Pro", "iPhone16,1");
    add("iPhone 15 Pro Max", "iPhone16,2");
    add("iPhone 16", "iPhone17,3");
    add("iPhone 16 Plus", "iPhone17,4");
    add("iPhone 16 Pro", "iPhone17,1");
    add("iPhone 16 Pro Max", "iPhone17,2");
    add("iPhone 16e", "iPhone17,5");
    add("iPhone 17", "iPhone18,3");
    add("iPhone 17 Pro", "iPhone18,1");
    add("iPhone 17 Pro Max", "iPhone18,2");
    add("iPhone Air", "iPhone18,4");
    add("iPhone 17e", "iPhone18,5");
    add("iPhone 18 Pro", "iPhone19,2");
    add("iPhone 18 Pro Max", "iPhone19,3", "iPhone19,7");
    add("iPad (1st generation)", "iPad1,1");
    add("iPad (2nd generation)", "iPad2,1", "iPad2,2", "iPad2,3", "iPad2,4");
    add("iPad (3rd generation)", "iPad3,1", "iPad3,2", "iPad3,3");
    add("iPad (4th generation)", "iPad3,4", "iPad3,5", "iPad3,6");
    add("iPad (5th generation)", "iPad6,11", "iPad6,12");
    add("iPad (6th generation)", "iPad7,5", "iPad7,6");
    add("iPad (7th generation)", "iPad7,11", "iPad7,12");
    add("iPad (8th generation)", "iPad11,6", "iPad11,7");
    add("iPad (9th generation)", "iPad12,1", "iPad12,2");
    add("iPad (10th generation)", "iPad13,18", "iPad13,19");
    add("iPad (A16)", "iPad15,7", "iPad15,8");
    add("iPad Air (1st generation)", "iPad4,1", "iPad4,2", "iPad4,3");
    add("iPad Air (2nd generation)", "iPad5,3", "iPad5,4");
    add("iPad Air (3rd generation)", "iPad11,3", "iPad11,4");
    add("iPad Air (4th generation)", "iPad13,1", "iPad13,2");
    add("iPad Air (5th generation)", "iPad13,16", "iPad13,17");
    add("iPad Air 11-inch (M2)", "iPad14,8", "iPad14,9");
    add("iPad Air 13-inch (M2)", "iPad14,10", "iPad14,11");
    add("iPad Air 11-inch (M3)", "iPad15,3", "iPad15,4");
    add("iPad Air 13-inch (M3)", "iPad15,5", "iPad15,6");
    add("iPad Air 11-inch (M4)", "iPad16,8", "iPad16,9");
    add("iPad Air 13-inch (M4)", "iPad16,10", "iPad16,11");
    add("iPad mini (1st generation)", "iPad2,5", "iPad2,6", "iPad2,7");
    add("iPad mini (2nd generation)", "iPad4,4", "iPad4,5", "iPad4,6");
    add("iPad mini (3rd generation)", "iPad4,7", "iPad4,8", "iPad4,9");
    add("iPad mini (4th generation)", "iPad5,1", "iPad5,2");
    add("iPad mini (5th generation)", "iPad11,1", "iPad11,2");
    add("iPad mini (6th generation)", "iPad14,1", "iPad14,2");
    add("iPad mini (A17 Pro)", "iPad16,1", "iPad16,2");
    add("iPad Pro 9.7-inch", "iPad6,3", "iPad6,4");
    add("iPad Pro 12.9-inch (1st generation)", "iPad6,7", "iPad6,8");
    add("iPad Pro 12.9-inch (2nd generation)", "iPad7,1", "iPad7,2");
    add("iPad Pro 10.5-inch", "iPad7,3", "iPad7,4");
    add("iPad Pro 11-inch (1st generation)", "iPad8,1", "iPad8,2", "iPad8,3", "iPad8,4");
    add("iPad Pro 12.9-inch (3rd generation)", "iPad8,5", "iPad8,6", "iPad8,7", "iPad8,8");
    add("iPad Pro 11-inch (2nd generation)", "iPad8,9", "iPad8,10");
    add("iPad Pro 12.9-inch (4th generation)", "iPad8,11", "iPad8,12");
    add("iPad Pro 11-inch (3rd generation)", "iPad13,4", "iPad13,5", "iPad13,6", "iPad13,7");
    add("iPad Pro 12.9-inch (5th generation)", "iPad13,8", "iPad13,9", "iPad13,10", "iPad13,11");
    add("iPad Pro 11-inch (4th generation)", "iPad14,3", "iPad14,4");
    add("iPad Pro 12.9-inch (6th generation)", "iPad14,5", "iPad14,6");
    add("iPad Pro 11-inch (M4)", "iPad16,3", "iPad16,4");
    add("iPad Pro 13-inch (M4)", "iPad16,5", "iPad16,6");
    add("iPad Pro 11-inch (M5)", "iPad17,1", "iPad17,2");
    add("iPad Pro 13-inch (M5)", "iPad17,3", "iPad17,4");
  }

  static String display(String raw, String fallback, boolean de) {
    if (raw == null
        || raw.isBlank()
        || Set.of("unknown", "0", "n/a").contains(raw.toLowerCase(Locale.ROOT))) return fallback;
    String clean = PlayerCommunityManager.clean(raw, 100);
    Matcher match = Pattern.compile("(?i)(iPhone|iPad)[0-9]+[,.][0-9]+").matcher(clean);
    if (match.find()) {
      String id = match.group().replace('.', ',').toLowerCase(Locale.ROOT);
      String model = APPLE.get(id);
      if (model != null) return localize(model, de);
      return clean;
    }
    String key = clean.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    String suffix = "";
    if (key.endsWith(" emu")) {
      key = key.substring(0, key.length() - 4).trim();
      suffix = de ? " (Client-Modell: emu)" : " (client model: emu)";
    }
    String console =
        switch (key) {
          case "ps5", "playstation 5" -> "Sony PlayStation 5";
          case "ps4", "playstation 4" -> "Sony PlayStation 4";
          case "xbox series s" -> "Microsoft Xbox Series S";
          case "xbox series x" -> "Microsoft Xbox Series X";
          case "xbox one s" -> "Microsoft Xbox One S";
          case "xbox one x" -> "Microsoft Xbox One X";
          case "xbox one" -> "Microsoft Xbox One";
          case "switch", "nintendo switch" -> "Nintendo Switch";
          case "switch 2", "nintendo switch 2" -> "Nintendo Switch 2";
          case "switch lite", "nintendo switch lite" -> "Nintendo Switch Lite";
          case "switch oled", "nintendo switch oled" -> "Nintendo Switch OLED";
          default -> clean;
        };
    return console + suffix;
  }

  private static String localize(String name, boolean de) {
    if (!de) return name;
    return name.replaceAll("([0-9]+)(st|nd|rd|th) generation", "$1. Generation")
        .replace("-inch", " Zoll");
  }

  /** Public connection display: exact known Apple/console names, otherwise just the OS. */
  static String connectionDevice(String raw, String rawOs, boolean de) {
    String os = ServerStatistics.normalizeOs(rawOs);
    String model = raw == null ? "" : PlayerCommunityManager.clean(raw, 100);
    String resolved = display(model, "", de);
    boolean apple =
        model.matches("(?i).*(iPhone|iPad)[0-9]+[,.][0-9]+.*") && !resolved.equals(model)
            || model.matches("(?i)(iPhone|iPad)\\s+.+");
    if (apple && (os.equals("iOS") || os.equals("Unknown"))) return resolved;
    if (resolved.startsWith("Sony PlayStation")
        && (os.equals("PlayStation") || os.equals("Unknown"))) return resolved;
    if (resolved.startsWith("Microsoft Xbox") && (os.equals("Xbox") || os.equals("Unknown")))
      return resolved;
    if (resolved.startsWith("Nintendo Switch")
        && (os.equals("Nintendo Switch") || os.equals("Unknown"))) return resolved;
    if (os.equals("Unknown")) return "Bedrock";
    return os;
  }
}
