package eu.simplexsmp.adminsuite;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 10.18.0: simpler pairing. Works out the address the app should use, builds complete setup links without
 * editing the config and explains in plain words why the app cannot connect. No Bukkit types, so the
 * harness can check it directly.
 */
final class AppPairing {
    private AppPairing() {}

    /**
     * host or host:port, optionally with https:// and a trailing slash. A bare host gets the API port; a saved
     * https:// URL without port keeps the implicit 443 (e.g. a reverse proxy domain). Returns "" for empty input.
     */
    static String normalizeAddress(String input, int defaultPort) {
        String raw = input == null ? "" : input.trim();
        boolean url = raw.regionMatches(true, 0, "https://", 0, 8);
        String value = raw.replaceFirst("(?i)^https://", "").replaceAll("/+$", "");
        if (value.isEmpty()) return "";
        if (!value.matches("[A-Za-z0-9.-]{1,253}(:[0-9]{2,5})?")) throw new IllegalArgumentException("Format: Host oder Host:Port, z. B. 123.45.67.89:12000");
        int colon = value.lastIndexOf(':');
        if (colon < 0) return value.toLowerCase(Locale.ROOT) + (url ? "" : ":" + defaultPort);
        int port = Integer.parseInt(value.substring(colon + 1));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port 1–65535 erforderlich");
        return value.substring(0, colon).toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Pterodactyl and most Minecraft hosts expose the allocation IP as SERVER_IP; 0.0.0.0 tells nothing. */
    static String hostIp(String serverIpEnv) {
        String ip = serverIpEnv == null ? "" : serverIpEnv.trim();
        if (ip.isEmpty() || ip.equals("0.0.0.0") || ip.equals("::") || ip.startsWith("127.") || !ip.matches("[0-9.]{7,15}")) return "";
        return ip;
    }

    /** What to type into the app: the saved public address, the host IP with the API port, or a placeholder. */
    static String appAddress(String publicAddress, String serverIpEnv, int port) {
        String saved = normalizeAddress(publicAddress, port);
        if (!saved.isEmpty()) return "https://" + saved;
        String ip = hostIp(serverIpEnv);
        return "https://" + (ip.isEmpty() ? "<Server-IP>" : ip) + ":" + port;
    }

    /**
     * Address used inside a setup link. The direct address with the pinned certificate comes first because it does
     * not depend on a proxy; the web domain is the last resort (the app accepts its public certificate on its own).
     */
    static String setupEndpoint(String publicAddress, String serverIpEnv, int port, String webUrl, String transport) {
        String saved = normalizeAddress(publicAddress, port);
        if (!saved.isEmpty()) return "https://" + saved;
        // Proxy transport listens on loopback only: the direct address can never answer the app.
        String ip = "proxy".equals(transport) ? "" : hostIp(serverIpEnv);
        if (!ip.isEmpty()) return "https://" + ip + ":" + port;
        String web = webUrl == null ? "" : webUrl.trim().replaceAll("/+$", "");
        return web.startsWith("https://") ? web : "";
    }

    /** True when something accepts TCP connections on this port of the server itself. */
    static boolean listening(String bind, int port, int timeoutMillis) {
        String host = bind == null || bind.isBlank() || bind.equals("0.0.0.0") ? "127.0.0.1" : bind.equals("::") ? "::1" : bind;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The plain-words checklist shown by /sas app status. */
    static List<String> checklist(boolean enabled, boolean active, String transport, String address, int port, String fingerprint, String domainUrl) {
        List<String> lines = new ArrayList<>();
        if (!enabled) {
            lines.add("§c✗ App-API ist aus. §7Einschalten: §f/sas app enable <Port> §7(einen zusätzlichen Port vom Hoster nehmen, nicht den Minecraft-Port).");
            return lines;
        }
        if (!active) {
            lines.add("§c✗ App-API läuft nicht. §7Grund steht oben. Danach §f/sas app reload§7.");
            return lines;
        }
        boolean proxy = "proxy".equals(transport);
        lines.add("§a✓ App-API läuft auf Port §f" + port + (proxy ? " §7(nur lokal, hinter deinem HTTPS-Proxy)" : ""));
        lines.add("§7In der App eintragen:");
        if (proxy) {
            lines.add("§7  Adresse: §f" + (domainUrl.isBlank() ? "deine HTTPS-Domain" : domainUrl.replaceAll("/+$", "")) + " §7· Fingerabdruck §fleer lassen");
        } else {
            lines.add("§7  Adresse: §f" + address);
            lines.add("§7  Fingerabdruck: §foptional §7– die App zeigt ihn beim ersten Verbinden zum Bestätigen:");
            lines.add("§f  " + (fingerprint.isBlank() ? "(noch kein Zertifikat)" : fingerprint));
        }
        lines.add("§7Am einfachsten: §f/sas app setup <Name> §7und den Link auf dem iPhone öffnen.");
        if (!proxy) {
            lines.add("§eWichtig: §7Port §f" + port + " §7muss beim Hoster §fdiesem Server zugewiesen §7sein (Netzwerk / Ports / Allocations). Sonst meldet die App „nicht erreichbar“.");
            // 10.19.0: UltraServers filters non-game traffic (Gcore L7) unless the allocation is created for HTTP plugins.
            lines.add("§7UltraServers: Netzwerk → Allocation → §fCreate§7 und den Haken für §fHTTP-/Voice-/Votifier-Plugins§7 setzen, sonst blockt der DDoS-Schutz die App-Verbindung.");
            if (address.contains("<Server-IP>")) lines.add("§7IP fest eintragen (für vollständige Setup-Links): §f/sas app address <IP>:" + port);
        }
        if (!domainUrl.isBlank() && !proxy) lines.add("§7Domain " + domainUrl.replaceAll("/+$", "") + ": §7nur über einen Reverse-Proxy mit Ziel §fhttps://<Server-IP>:" + port + "§7. Meldet die App 502, zeigt der Proxy auf das falsche Ziel.");
        return lines;
    }
}
