package eu.simplexsmp.adminsuite;

import java.net.ServerSocket;
import java.util.List;

/** 10.18.0: simpler pairing – addresses, setup-link endpoints, checklist and the local port self-test. */
public final class Release1180Harness {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    static boolean fails(Runnable r) { try { r.run(); return false; } catch (IllegalArgumentException e) { return true; } }

    public static void main(String[] args) throws Exception {
        check(AppPairing.normalizeAddress("", 12000).isEmpty(), "empty address stays empty");
        check(AppPairing.normalizeAddress("123.45.67.89", 12000).equals("123.45.67.89:12000"), "default port added");
        check(AppPairing.normalizeAddress("https://Play.Example.com:25601/", 12000).equals("play.example.com:25601"), "scheme, case and slash removed");
        check(fails(() -> AppPairing.normalizeAddress("http://x y", 1)), "spaces rejected");
        check(fails(() -> AppPairing.normalizeAddress("host:99999", 1)), "port range");
        check(fails(() -> AppPairing.normalizeAddress("host/path", 1)), "paths rejected");

        check(AppPairing.hostIp("0.0.0.0").isEmpty() && AppPairing.hostIp(null).isEmpty() && AppPairing.hostIp("127.0.0.1").isEmpty(), "useless SERVER_IP ignored");
        check(AppPairing.hostIp("88.99.10.11").equals("88.99.10.11"), "host IP used");
        check(AppPairing.hostIp("evil\nline").isEmpty(), "garbage ignored");

        check(AppPairing.appAddress("", "88.99.10.11", 12000).equals("https://88.99.10.11:12000"), "app address from host IP");
        check(AppPairing.appAddress("", "", 12000).equals("https://<Server-IP>:12000"), "placeholder without IP");
        check(AppPairing.appAddress("1.2.3.4:5000", "88.99.10.11", 12000).equals("https://1.2.3.4:5000"), "saved address wins");

        check(AppPairing.setupEndpoint("1.2.3.4:5000", "88.99.10.11", 12000, "https://admin.example.eu/", "tls").equals("https://1.2.3.4:5000"), "saved address first");
        check(AppPairing.setupEndpoint("", "88.99.10.11", 12000, "https://admin.example.eu/", "tls").equals("https://88.99.10.11:12000"), "direct address before domain");
        check(AppPairing.setupEndpoint("", "0.0.0.0", 12000, "https://admin.example.eu/", "tls").equals("https://admin.example.eu"), "domain as last resort");
        check(AppPairing.setupEndpoint("", "", 12000, "", "tls").isEmpty(), "no address: code only");
        check(AppPairing.setupEndpoint("", "88.99.10.11", 12000, "https://admin.example.eu/", "proxy").equals("https://admin.example.eu"), "proxy transport never offers the loopback backend");
        check(AppPairing.normalizeAddress("https://admin.example.eu", 39093).equals("admin.example.eu"), "saved https URL keeps implicit 443");
        check(AppPairing.setupEndpoint("https://admin.example.eu/", "", 39093, "", "tls").equals("https://admin.example.eu"), "existing domain setup address unchanged");
        check(AppPairing.normalizeAddress("admin.example.eu", 39093).equals("admin.example.eu:39093"), "bare host gets the API port");

        List<String> off = AppPairing.checklist(false, false, "tls", "https://<Server-IP>:12000", 12000, "", "");
        check(off.size() == 1 && off.get(0).contains("/sas app enable"), "disabled explains enable");
        List<String> on = AppPairing.checklist(true, true, "tls", "https://<Server-IP>:12000", 12000, "ab".repeat(32), "https://admin.example.eu/");
        String all = String.join("\n", on);
        check(all.contains("ab".repeat(32)) && all.contains("zugewiesen") && all.contains("/sas app address") && all.contains("502"), "checklist explains port, address and proxy");
        String proxy = String.join("\n", AppPairing.checklist(true, true, "proxy", "", 12000, "", "https://admin.example.eu/"));
        check(proxy.contains("https://admin.example.eu") && proxy.contains("leer lassen"), "proxy mode: domain with empty fingerprint");

        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            check(AppPairing.listening("0.0.0.0", socket.getLocalPort(), 1000), "self-test finds a listening port");
        }
        int closed; try (ServerSocket socket = new ServerSocket(0)) { closed = socket.getLocalPort(); }
        check(!AppPairing.listening("0.0.0.0", closed, 500), "self-test reports a closed port");
        System.out.println("PASS " + checks + " 10.18.0 pairing checks");
    }
}
