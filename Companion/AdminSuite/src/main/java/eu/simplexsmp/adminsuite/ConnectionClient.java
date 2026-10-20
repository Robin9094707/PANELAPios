package eu.simplexsmp.adminsuite;

import java.util.Locale;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Current connection only. Never initialized from saved profiles or linked accounts. */
final class ConnectionClient {
  final Player player;
  final UUID uuid;
  final String name;
  final boolean bedrock;
  final long startedAt;
  private String os;
  private String model = "";
  private String brand = "";
  private String randomId = "";
  private long lastObservation;

  ConnectionClient(Player player, boolean bedrock, String os, long startedAt) {
    this.player = player;
    this.uuid = player.getUniqueId();
    this.name = player.getName();
    this.bedrock = bedrock;
    this.os = clean(os);
    this.startedAt = startedAt;
  }

  synchronized void refreshJava() {
    if (bedrock) return;
    try {
      Object value = ClientProbeDiagnostics.brand(player);
      if (value != null && !value.toString().isBlank()) brand = clean(value.toString());
    } catch (RuntimeException ignored) {
    }
  }

  synchronized boolean observeBedrock(
      UUID reportedUuid,
      String reportedName,
      String reportedOs,
      String reportedModel,
      String reportedRandomId,
      long observedAt,
      long receivedAt,
      boolean direct) {
    if (!bedrock) return false;
    if (reportedUuid != null
        ? !uuid.equals(reportedUuid)
        : reportedName == null || !name.equalsIgnoreCase(reportedName)) return false;
    // Direct observations come from connectionByUuid for this live connection.
    // Bridge packets must be recent, monotonic and from around this login, not the old cache.
    if (!direct
        && (observedAt <= 0
            || observedAt < startedAt - 5000
            || receivedAt < startedAt - 5000
            || observedAt > receivedAt + 180000)) return false;
    boolean older = observedAt > 0 && observedAt < lastObservation;
    if (older && !model.isBlank()) return false;
    String nextRandom = clean(reportedRandomId);
    if (!randomId.isBlank() && !nextRandom.isBlank() && !randomId.equals(nextRandom)) return false;
    if (!nextRandom.isBlank()) randomId = nextRandom;
    if (observedAt > 0) lastObservation = Math.max(lastObservation, observedAt);
    if (reportedOs != null
        && !reportedOs.isBlank()
        && (!older || "Unknown".equals(ServerStatistics.normalizeOs(os)))) os = clean(reportedOs);
    if (reportedModel != null && !reportedModel.isBlank()) model = clean(reportedModel);
    return true;
  }

  synchronized String model() {
    return bedrock ? model : "";
  }

  synchronized String os() {
    return bedrock ? os : "";
  }

  synchronized String brand() {
    return bedrock ? "" : brand;
  }

  synchronized String label(boolean german) {
    return bedrock ? DeviceNames.connectionDevice(model, os, german) : javaLabel(brand);
  }

  synchronized boolean ready() {
    return bedrock
        ? !model.isBlank() || "Android".equals(ServerStatistics.normalizeOs(os))
        : !brand.isBlank();
  }

  static String javaLabel(String raw) {
    String brand = clean(raw);
    String lower = brand.toLowerCase(Locale.ROOT);
    if (lower.contains("fabric")) return "Java • Fabric";
    if (lower.contains("neoforge")) return "Java • NeoForge";
    if (lower.contains("forge") || lower.contains("fml")) return "Java • Forge";
    if (lower.contains("quilt")) return "Java • Quilt";
    if (lower.equals("vanilla")) return "Java • Vanilla";
    return brand.isBlank() ? "Java" : "Java • " + brand;
  }

  private static String clean(String raw) {
    return PlayerCommunityManager.clean(raw, 120);
  }
}
