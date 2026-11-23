package eu.simplexsmp.adminsuite;


/**
 * 10.23.0: plugin updates keep their version in the file name. Paper matches a JAR in the update
 * folder to the installed plugin by its plugin name, replaces it and adopts the staged file name.
 * Staging as {@code <Name>-<Version>.jar} therefore leaves {@code plugins/<Name>-<Version>.jar}
 * after the next start – visible in every file manager.
 */
final class PluginFileNaming {
  private PluginFileNaming() { }

  /** {@code LuckPerms} + {@code 5.4.150} → {@code LuckPerms-5.4.150.jar}; unsafe characters become '-'. */
  static String versionedName(String name, String version) {
    String n = clean(name), v = clean(version);
    if (n.isEmpty()) n = "Plugin";
    return v.isEmpty() ? n + ".jar" : n + "-" + v + ".jar";
  }

  private static String clean(String value) {
    if (value == null) return "";
    String out = value.strip().replaceAll("[^A-Za-z0-9._+-]+", "-").replaceAll("-{2,}", "-").replaceAll("^[-.]+|[-.]+$", "");
    return out.length() > 80 ? out.substring(0, 80) : out;
  }

  /** True when the file name already carries the plugin's version. */
  static boolean carriesVersion(String filename, String version) {
    return version != null && !version.isBlank() && filename != null && filename.contains(clean(version));
  }

  /** True when this is a pending update file name Paper will adopt (versioned or the legacy installed name). */
  static boolean sameUpdate(String candidate, String versioned, String installed) {
    return candidate.equalsIgnoreCase(versioned) || candidate.equalsIgnoreCase(installed);
  }
}
