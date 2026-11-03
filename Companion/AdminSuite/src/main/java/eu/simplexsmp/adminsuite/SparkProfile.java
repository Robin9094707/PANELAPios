package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 10.2.0: reads a raw spark sampler profile (protobuf {@code SamplerData}, as stored by spark's bytebin) without the
 * spark library. The file is streamed twice from disk, so profiles of several hundred megabytes need only a few MB of
 * heap: pass one reads metadata, window statistics and the class → plugin map; pass two walks the call tree.
 * <p>
 * Attribution: idle time (park/sleep/wait below the server thread) is removed first. The remaining self time of every
 * frame is credited to the deepest plugin frame on its stack – the plugin whose code caused the work, even when the
 * time is spent inside Paper or the JDK. Pure Java; no Bukkit access.
 */
final class SparkProfile {
    static final long MAX_BYTES = 400L * 1024 * 1024;
    static final int MAX_NODES = 3_500_000;
    private static final Set<String> IDLE = Set.of(
        "jdk.internal.misc.Unsafe.park", "sun.misc.Unsafe.park", "java.lang.Thread.sleep", "java.lang.Thread.sleep0", "java.lang.Object.wait", "java.lang.Object.wait0",
        "java.util.concurrent.locks.LockSupport.park", "java.util.concurrent.locks.LockSupport.parkNanos", "java.util.concurrent.locks.LockSupport.parkUntil",
        "sun.nio.ch.EPoll.wait", "sun.nio.ch.KQueue.poll", "sun.nio.ch.WEPoll.wait", "java.lang.Thread.onSpinWait", "java.lang.VirtualThread.park");

    private SparkProfile() {}

    /** Spark viewer link or bare code → code (letters/digits only). */
    static String code(String input) {
        String s = input == null ? "" : input.trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        int hash = s.indexOf('#'); if (hash >= 0) s = s.substring(0, hash);
        if (!s.matches("[A-Za-z0-9]{4,32}")) throw new IllegalArgumentException("Spark-Link oder Code ungültig (z. B. https://spark.lucko.me/AbCdEf1234)");
        return s;
    }

    // ------------------------------------------------------------------ protobuf primitives
    /** Sequential protobuf reader over a stream with a position counter. */
    static final class Reader {
        private final InputStream in;
        long pos;
        Reader(InputStream in, long start) { this.in = in; this.pos = start; }
        int read() throws IOException { int b = in.read(); if (b < 0) throw new EOFException(); pos++; return b; }
        long varint() throws IOException {
            long r = 0; int shift = 0;
            while (true) { int b = read(); r |= (long) (b & 0x7f) << shift; if (b < 0x80) return r; shift += 7; if (shift > 63) throw new IOException("Ungültige Varint"); }
        }
        double fixed64() throws IOException { long v = 0; for (int i = 0; i < 8; i++) v |= (long) read() << (8 * i); return Double.longBitsToDouble(v); }
        byte[] bytes(int n) throws IOException { byte[] b = in.readNBytes(n); if (b.length != n) throw new EOFException(); pos += n; return b; }
        void skipField(int wire) throws IOException {
            switch (wire) { case 0 -> varint(); case 1 -> skipRaw(8); case 2 -> skipRaw(varint()); case 5 -> skipRaw(4); default -> throw new IOException("Unbekannter Feldtyp " + wire); }
        }
        void skipRaw(long n) throws IOException { long left = n; while (left > 0) { long s = in.skip(left); if (s <= 0) { read(); left--; continue; } left -= s; pos += s; } }
    }

    /** In-memory protobuf message view. */
    static final class Msg {
        final byte[] b; int i; final int end;
        Msg(byte[] b) { this(b, 0, b.length); }
        Msg(byte[] b, int start, int end) { this.b = b; this.i = start; this.end = end; }
        boolean more() { return i < end; }
        long varint() { long r = 0; int shift = 0; while (true) { int c = b[i++] & 0xff; r |= (long) (c & 0x7f) << shift; if (c < 0x80) return r; shift += 7; if (shift > 63) throw new IllegalArgumentException("varint"); } }
        double fixed64() { long v = 0; for (int k = 0; k < 8; k++) v |= (long) (b[i++] & 0xff) << (8 * k); return Double.longBitsToDouble(v); }
        Msg sub() { int len = (int) varint(); Msg m = new Msg(b, i, i + len); i += len; return m; }
        String str() { int len = (int) varint(); String s = new String(b, i, len, StandardCharsets.UTF_8); i += len; return s; }
        void skip(int wire) { switch (wire) { case 0 -> varint(); case 1 -> i += 8; case 2 -> { int len = (int) varint(); i += len; } case 5 -> i += 4; default -> throw new IllegalArgumentException("wire " + wire); } }
        double sumDoubles(int wire) { if (wire == 1) return fixed64(); Msg m = sub(); double s = 0; while (m.more()) s += m.fixed64(); return s; }
        double[] doubles(int wire) { if (wire == 1) return new double[]{fixed64()}; Msg m = sub(); double[] out = new double[(m.end - m.i) / 8]; for (int k = 0; k < out.length; k++) out[k] = m.fixed64(); return out; }
        void ints(int wire, IntList into) { if (wire == 0) { into.add((int) varint()); return; } Msg m = sub(); while (m.more()) into.add((int) m.varint()); }
    }

    static final class IntList {
        int[] a = new int[1024]; int n;
        void add(int v) { if (n == a.length) a = Arrays.copyOf(a, a.length * 2); a[n++] = v; }
        void clear() { n = 0; }
    }

    // ------------------------------------------------------------------ analysis
    /** Analyses a raw spark profile file; returns a bounded JSON-ready summary with findings in plain German. */
    static Map<String, Object> analyze(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("Profil zu groß (" + (size >> 20) + " MB)");
        Map<String, String> classSources = new HashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        List<long[]> threadRegions = new ArrayList<>();
        Map<Integer, Map<Integer, Double>> windows = new TreeMap<>();
        byte[] metadata = null;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            Reader r = new Reader(new BufferedInputStream(Channels.newInputStream(ch), 1 << 16), 0);
            while (r.pos < size) {
                long key = r.varint(); int field = (int) (key >>> 3), wire = (int) (key & 7);
                if (wire != 2) { r.skipField(wire); continue; }
                long len = r.varint();
                switch (field) {
                    case 1 -> { if (len > 16 << 20) throw new IOException("Metadaten zu groß"); metadata = r.bytes((int) len); }
                    case 2 -> { threadRegions.add(new long[]{r.pos, len}); r.skipRaw(len); }
                    case 3 -> { if (len > 1 << 20) { r.skipRaw(len); break; } Msg m = new Msg(r.bytes((int) len)); String k = "", v = "";
                        while (m.more()) { long kk = m.varint(); int f = (int) (kk >>> 3); if (f == 1) k = m.str(); else if (f == 2) v = m.str(); else m.skip((int) (kk & 7)); }
                        if (classSources.size() < 500_000) classSources.put(k, v); }
                    case 7 -> { if (len > 1 << 20) { r.skipRaw(len); break; } Msg m = new Msg(r.bytes((int) len)); int k = 0; Map<Integer, Double> stats = new HashMap<>();
                        while (m.more()) { long kk = m.varint(); int f = (int) (kk >>> 3), w = (int) (kk & 7);
                            if (f == 1 && w == 0) k = (int) m.varint();
                            else if (f == 2 && w == 2) { Msg s = m.sub(); while (s.more()) { long sk = s.varint(); int sf = (int) (sk >>> 3), sw = (int) (sk & 7); if (sw == 0) stats.put(sf, (double) s.varint()); else if (sw == 1) stats.put(sf, s.fixed64()); else s.skip(sw); } }
                            else m.skip(w); }
                        if (windows.size() < 10_000) windows.put(k, stats); }
                    default -> r.skipRaw(len);
                }
            }
        }
        Map<String, Object> meta = metadata == null ? Map.of() : metadata(new Msg(metadata));
        out.put("metadata", meta);
        out.put("windows", windowSummary(windows));
        // pass 2: call trees
        Map<String, double[]> self = new HashMap<>();
        Map<String, double[]> plugins = new HashMap<>();
        Map<String, double[]> pluginFrames = new HashMap<>();
        List<Map<String, Object>> threads = new ArrayList<>();
        double allTotal = 0, allIdle = 0;
        int nodesSeen = 0;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            for (long[] region : threadRegions) {
                ch.position(region[0]);
                Reader r = new Reader(new BufferedInputStream(Channels.newInputStream(ch), 1 << 16), region[0]);
                Tree t = new Tree();
                long end = region[0] + region[1];
                String name = "";
                IntList roots = new IntList();
                while (r.pos < end) {
                    long key = r.varint(); int field = (int) (key >>> 3), wire = (int) (key & 7);
                    if (field == 1 && wire == 2) name = new String(r.bytes((int) r.varint()), StandardCharsets.UTF_8);
                    else if (field == 3 && wire == 2) {
                        long len = r.varint(); if (len > 8 << 20) throw new IOException("Stackknoten zu groß");
                        t.node(new Msg(r.bytes((int) len)));
                        if (++nodesSeen > MAX_NODES) throw new IOException("Profil hat zu viele Knoten (über " + MAX_NODES + ")");
                    } else if (field == 5) {
                        if (wire == 0) roots.add((int) r.varint());
                        else { Msg m = new Msg(r.bytes((int) r.varint())); while (m.more()) roots.add((int) m.varint()); }
                    } else r.skipField(wire);
                }
                double[] res = t.walk(roots, classSources, self, plugins, pluginFrames);
                allTotal += res[0]; allIdle += res[1];
                threads.add(SimplexAdminSuite.appMap("name", clip(name, 80), "totalMs", Math.round(res[0]), "idleMs", Math.round(res[1]), "busyMs", Math.round(res[0] - res[1]), "nodes", t.count));
            }
        }
        threads.sort((a, b) -> Long.compare(((Number) b.get("busyMs")).longValue(), ((Number) a.get("busyMs")).longValue()));
        double busy = Math.max(1, allTotal - allIdle);
        out.put("threads", threads.size() > 12 ? new ArrayList<>(threads.subList(0, 12)) : threads);
        out.put("totalMs", Math.round(allTotal)); out.put("idleMs", Math.round(allIdle)); out.put("busyMs", Math.round(allTotal - allIdle));
        out.put("idleShare", allTotal > 0 ? round(allIdle / allTotal * 100, 1) : 0);
        out.put("nodes", nodesSeen);
        Map<String, String> pluginVersions = new HashMap<>();
        if (meta.get("sources") instanceof List<?> sources) for (Object s : sources) if (s instanceof Map<?, ?> m) pluginVersions.put(String.valueOf(m.get("name")), String.valueOf(m.get("version")));
        out.put("plugins", top(plugins, 20, busy, k -> SimplexAdminSuite.appMap("name", k, "version", pluginVersions.getOrDefault(k, ""))));
        out.put("pluginFrames", top(pluginFrames, 25, busy, k -> { int bar = k.indexOf('|'); return SimplexAdminSuite.appMap("plugin", k.substring(0, bar), "frame", shortFrame(k.substring(bar + 1))); }));
        out.put("methods", top(self, 30, busy, k -> SimplexAdminSuite.appMap("frame", shortFrame(k), "source", sourceOf(k, classSources))));
        double serverShare = 0; for (var e : plugins.entrySet()) serverShare += e.getValue()[0];
        out.put("vanillaShare", round(Math.max(0, busy - serverShare) / busy * 100, 1));
        out.put("findings", findings(out, self, busy));
        return out;
    }

    private static String sourceOf(String frame, Map<String, String> sources) {
        int dot = frame.lastIndexOf('.'); String cls = dot > 0 ? frame.substring(0, dot) : frame;
        String s = sources.get(cls); if (s == null && cls.contains("$")) s = sources.get(cls.substring(0, cls.indexOf('$')));
        return s == null ? "" : s;
    }

    /** Fully qualified frame → readable short form (package abbreviated, method kept). */
    static String shortFrame(String frame) {
        if (frame.startsWith("native.")) return "native " + frame.substring(7).replaceAll(".*/", "");
        int dot = frame.lastIndexOf('.'); if (dot < 0) return clip(frame, 160);
        String cls = frame.substring(0, dot), method = frame.substring(dot + 1);
        String[] parts = cls.split("\\.");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) b.append(parts[i].isEmpty() ? "" : parts[i].substring(0, 1)).append('.');
        b.append(parts[parts.length - 1]).append('.').append(method);
        return clip(b.toString(), 160);
    }

    private interface Label { Map<String, Object> of(String key); }
    private static List<Map<String, Object>> top(Map<String, double[]> values, int limit, double busy, Label label) {
        List<Map.Entry<String, double[]>> rows = new ArrayList<>(values.entrySet());
        rows.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : rows) { if (out.size() >= limit) break; Map<String, Object> m = new LinkedHashMap<>(label.of(e.getKey())); m.put("ms", Math.round(e.getValue()[0])); m.put("share", round(e.getValue()[0] / busy * 100, 2)); out.add(m); }
        return out;
    }

    /** One thread's flattened tree. Children always precede their parents (spark writes post-order). */
    static final class Tree {
        String[] cls = new String[4096], method = new String[4096];
        double[] total = new double[4096];
        int[] childStart = new int[4096], childCount = new int[4096];
        final IntList refs = new IntList();
        final IntList scratch = new IntList();
        int count;
        private final Map<String, String> intern = new HashMap<>();

        void node(Msg m) {
            if (count == total.length) grow();
            String c = "", me = ""; double time = 0; scratch.clear();
            while (m.more()) {
                long key = m.varint(); int f = (int) (key >>> 3), w = (int) (key & 7);
                if (f == 3 && w == 2) c = intern(m.str());
                else if (f == 4 && w == 2) me = intern(m.str());
                else if (f == 8) time += m.sumDoubles(w);
                else if (f == 9) m.ints(w, scratch);
                else m.skip(w);
            }
            cls[count] = c; method[count] = me; total[count] = time;
            childStart[count] = refs.n; childCount[count] = scratch.n;
            for (int k = 0; k < scratch.n; k++) refs.add(scratch.a[k]);
            count++;
        }
        private String intern(String s) { String x = intern.putIfAbsent(s, s); return x == null ? s : x; }
        private void grow() {
            int n = total.length * 2;
            cls = Arrays.copyOf(cls, n); method = Arrays.copyOf(method, n); total = Arrays.copyOf(total, n);
            childStart = Arrays.copyOf(childStart, n); childCount = Arrays.copyOf(childCount, n);
        }
        /** Top-down walk: returns {total, idle}; fills self time per frame, per plugin and per deepest plugin frame. */
        double[] walk(IntList roots, Map<String, String> sources, Map<String, double[]> self, Map<String, double[]> plugins, Map<String, double[]> pluginFrames) {
            double sum = 0, idle = 0;
            int[] stack = new int[256]; String[] plug = new String[256], frame = new String[256]; int sp = 0;
            Map<String, String> pluginOfClass = new HashMap<>();
            for (int ri = 0; ri < roots.n; ri++) {
                int root = roots.a[ri]; if (root < 0 || root >= count) continue;
                sum += total[root];
                stack[0] = root; plug[0] = null; frame[0] = null; sp = 1;
                while (sp > 0) {
                    sp--; int n = stack[sp]; String p = plug[sp], fr = frame[sp];
                    String key = cls[n] + "." + method[n];
                    if (IDLE.contains(key)) { idle += total[n]; continue; }
                    String src = pluginOfClass.computeIfAbsent(cls[n], c -> { String s = sources.get(c); if (s == null && c.contains("$")) s = sources.get(c.substring(0, c.indexOf('$'))); return s == null ? "" : s; });
                    if (!src.isEmpty()) { p = src; fr = key; }
                    double childSum = 0;
                    int start = childStart[n], cc = childCount[n];
                    for (int k = 0; k < cc; k++) {
                        int c = refs.a[start + k]; if (c < 0 || c >= count) continue;
                        childSum += total[c];
                        if (sp == stack.length) { stack = Arrays.copyOf(stack, sp * 2); plug = Arrays.copyOf(plug, sp * 2); frame = Arrays.copyOf(frame, sp * 2); }
                        stack[sp] = c; plug[sp] = p; frame[sp] = fr; sp++;
                    }
                    double own = Math.max(0, total[n] - childSum);
                    if (own <= 0) continue;
                    self.computeIfAbsent(key, x -> new double[1])[0] += own;
                    if (p != null) { plugins.computeIfAbsent(p, x -> new double[1])[0] += own; pluginFrames.computeIfAbsent(p + "|" + fr, x -> new double[1])[0] += own; }
                }
            }
            return new double[]{sum, idle};
        }
    }

    // ------------------------------------------------------------------ metadata
    private static Map<String, Object> metadata(Msg m) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        Map<String, String> configs = new LinkedHashMap<>();
        while (m.more()) {
            long key = m.varint(); int f = (int) (key >>> 3), w = (int) (key & 7);
            switch (f) {
                case 1 -> { if (w != 2) { m.skip(w); break; } Msg c = m.sub(); while (c.more()) { long k = c.varint(); if ((k >>> 3) == 2 && (k & 7) == 2) out.put("creator", clip(c.str(), 40)); else c.skip((int) (k & 7)); } }
                case 2 -> out.put("startTime", m.varint());
                case 3 -> out.put("intervalMicros", m.varint());
                case 6 -> out.put("comment", clip(m.str(), 200));
                case 7 -> out.put("platform", platform(m.sub()));
                case 8 -> out.put("platformStatistics", platformStats(m.sub()));
                case 9 -> out.put("system", systemStats(m.sub()));
                case 10 -> { Msg e = m.sub(); String k = "", v = ""; while (e.more()) { long kk = e.varint(); if ((kk >>> 3) == 1) k = e.str(); else if ((kk >>> 3) == 2) v = e.str(); else e.skip((int) (kk & 7)); } if (configs.size() < 40 && v.length() < 400_000) configs.put(k, v); }
                case 11 -> out.put("endTime", m.varint());
                case 12 -> out.put("ticks", m.varint());
                case 13 -> { Msg e = m.sub(); String name = ""; boolean builtin = false; String version = "";
                    while (e.more()) { long kk = e.varint(); int ef = (int) (kk >>> 3), ew = (int) (kk & 7);
                        if (ef == 2 && ew == 2) { Msg p = e.sub(); while (p.more()) { long pk = p.varint(); int pf = (int) (pk >>> 3), pw = (int) (pk & 7); if (pf == 1 && pw == 2) name = p.str(); else if (pf == 2 && pw == 2) version = p.str(); else if (pf == 5 && pw == 0) builtin = p.varint() != 0; else p.skip(pw); } }
                        else e.skip(ew); }
                    if (sources.size() < 300) sources.add(SimplexAdminSuite.appMap("name", clip(name, 60), "version", clip(version, 40), "builtin", builtin)); }
                case 15 -> out.put("mode", switch ((int) m.varint()) { case 1 -> "Speicherzuweisung"; case 2 -> "Sperren"; default -> "Ausführung"; });
                case 16 -> out.put("engine", m.varint() == 1 ? "async-profiler" : "Java");
                default -> m.skip(w);
            }
        }
        out.put("sources", sources);
        out.put("config", configSummary(configs));
        long start = out.get("startTime") instanceof Number n ? n.longValue() : 0, end = out.get("endTime") instanceof Number n2 ? n2.longValue() : 0;
        if (start > 0 && end > start) out.put("durationSeconds", (end - start) / 1000);
        return out;
    }

    private static Map<String, Object> platform(Msg m) {
        Map<String, Object> out = new LinkedHashMap<>();
        while (m.more()) { long key = m.varint(); int f = (int) (key >>> 3), w = (int) (key & 7);
            if (w == 2 && f == 2) out.put("name", clip(m.str(), 40)); else if (w == 2 && f == 3) out.put("version", clip(m.str(), 80)); else if (w == 2 && f == 4) out.put("minecraft", clip(m.str(), 20));
            else if (w == 2 && f == 8) out.put("brand", clip(m.str(), 40)); else if (w == 2 && f == 9) out.put("spark", clip(m.str(), 40)); else m.skip(w); }
        return out;
    }

    /** RollingAverageValues → {mean,max,min,median,p95}. */
    private static Map<String, Object> rolling(Msg m) {
        double[] v = new double[6];
        while (m.more()) { long key = m.varint(); int f = (int) (key >>> 3), w = (int) (key & 7); if (w == 1 && f >= 1 && f <= 5) v[f] = m.fixed64(); else m.skip(w); }
        return SimplexAdminSuite.appMap("mean", round(v[1], 2), "max", round(v[2], 2), "min", round(v[3], 2), "median", round(v[4], 2), "p95", round(v[5], 2));
    }

    private static Map<String, Object> platformStats(Msg m) {
        Map<String, Object> out = new LinkedHashMap<>();
        while (m.more()) {
            long key = m.varint(); int f = (int) (key >>> 3), w = (int) (key & 7);
            switch (f) {
                case 1 -> { Msg mem = m.sub(); while (mem.more()) { long k = mem.varint(); int mf = (int) (k >>> 3), mw = (int) (k & 7); if (mf == 1 && mw == 2) out.put("heap", usage(mem.sub())); else mem.skip(mw); } }
                case 3 -> out.put("uptimeMs", m.varint());
                case 4 -> { Msg t = m.sub(); double[] v = new double[4]; while (t.more()) { long k = t.varint(); int tf = (int) (k >>> 3), tw = (int) (k & 7); if (tw == 1 && tf <= 3) v[tf] = t.fixed64(); else t.skip(tw); } out.put("tps", List.of(round(v[1], 2), round(v[2], 2), round(v[3], 2))); }
                case 5 -> { Msg t = m.sub(); while (t.more()) { long k = t.varint(); int tf = (int) (k >>> 3), tw = (int) (k & 7); if (tf == 1 && tw == 2) out.put("mspt1m", rolling(t.sub())); else if (tf == 2 && tw == 2) out.put("mspt5m", rolling(t.sub())); else t.skip(tw); } }
                case 6 -> { Msg t = m.sub(); while (t.more()) { long k = t.varint(); int tf = (int) (k >>> 3), tw = (int) (k & 7); if (tf == 1 && tw == 2) out.put("ping", rolling(t.sub())); else t.skip(tw); } }
                case 7 -> out.put("players", m.varint());
                case 8 -> out.put("world", world(m.sub()));
                default -> m.skip(w);
            }
        }
        return out;
    }

    private static Map<String, Object> usage(Msg m) {
        long[] v = new long[5];
        while (m.more()) { long key = m.varint(); int f = (int) (key >>> 3), w = (int) (key & 7); if (w == 0 && f <= 4) v[f] = m.varint(); else m.skip(w); }
        return SimplexAdminSuite.appMap("used", v[1], "committed", v[2], "max", v[4]);
    }

    private static Map<String, Object> world(Msg m) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> types = new HashMap<>();
        List<Map<String, Object>> chunks = new ArrayList<>();
        List<Map<String, Object>> worlds = new ArrayList<>();
        while (m.more()) {
            long key = m.varint(); int f = (int) (key >>> 3), w = (int) (key & 7);
            if (f == 1 && w == 0) out.put("entities", m.varint());
            else if (f == 2 && w == 2) { Msg e = m.sub(); String k = ""; long v = 0; while (e.more()) { long kk = e.varint(); if ((kk >>> 3) == 1) k = e.str(); else if ((kk >>> 3) == 2) v = e.varint(); else e.skip((int) (kk & 7)); } types.merge(k, v, Long::sum); }
            else if (f == 3 && w == 2) {
                Msg wm = m.sub(); String name = ""; long total = 0;
                while (wm.more()) { long kk = wm.varint(); int wf = (int) (kk >>> 3), ww = (int) (kk & 7);
                    if (wf == 1 && ww == 2) name = wm.str();
                    else if (wf == 2 && ww == 0) total = wm.varint();
                    else if (wf == 3 && ww == 2) { Msg region = wm.sub(); while (region.more()) { long rk = region.varint(); int rf = (int) (rk >>> 3), rw = (int) (rk & 7);
                        if (rf == 2 && rw == 2) { Msg c = region.sub(); long x = 0, z = 0, t = 0; Map<String, Long> ct = new HashMap<>();
                            while (c.more()) { long ck = c.varint(); int cf = (int) (ck >>> 3), cw = (int) (ck & 7);
                                if (cf == 1 && cw == 0) x = (int) c.varint(); else if (cf == 2 && cw == 0) z = (int) c.varint(); else if (cf == 3 && cw == 0) t = c.varint();
                                else if (cf == 4 && cw == 2) { Msg e = c.sub(); String k = ""; long v = 0; while (e.more()) { long ek = e.varint(); if ((ek >>> 3) == 1) k = e.str(); else if ((ek >>> 3) == 2) v = e.varint(); else e.skip((int) (ek & 7)); } ct.merge(k, v, Long::sum); }
                                else c.skip(cw); }
                            if (t >= 20) { final String wn = name; chunks.add(SimplexAdminSuite.appMap("world", wn, "x", (int) x, "z", (int) z, "blockX", (int) x * 16 + 8, "blockZ", (int) z * 16 + 8, "entities", t, "types", topCounts(ct, 4))); }
                        } else region.skip(rw); } }
                    else wm.skip(ww); }
                worlds.add(SimplexAdminSuite.appMap("name", clip(name, 60), "entities", total));
            } else m.skip(w);
        }
        chunks.sort((a, b) -> Long.compare(((Number) b.get("entities")).longValue(), ((Number) a.get("entities")).longValue()));
        out.put("types", topCounts(types, 15));
        out.put("worlds", worlds);
        out.put("hotChunks", chunks.size() > 15 ? new ArrayList<>(chunks.subList(0, 15)) : chunks);
        return out;
    }

    static List<Map<String, Object>> topCounts(Map<String, Long> counts, int limit) {
        List<Map.Entry<String, Long>> rows = new ArrayList<>(counts.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : rows) { if (out.size() >= limit) break; out.add(SimplexAdminSuite.appMap("type", e.getKey().replace("minecraft:", ""), "count", e.getValue())); }
        return out;
    }

    private static Map<String, Object> systemStats(Msg m) {
        Map<String, Object> out = new LinkedHashMap<>();
        while (m.more()) {
            long key = m.varint(); int f = (int) (key >>> 3), w = (int) (key & 7);
            switch (f) {
                case 1 -> { Msg c = m.sub(); while (c.more()) { long k = c.varint(); int cf = (int) (k >>> 3), cw = (int) (k & 7);
                    if (cf == 1 && cw == 0) out.put("cpuThreads", c.varint());
                    else if ((cf == 2 || cf == 3) && cw == 2) { Msg u = c.sub(); double l1 = 0, l15 = 0; while (u.more()) { long uk = u.varint(); int uf = (int) (uk >>> 3), uw = (int) (uk & 7); if (uw == 1 && uf == 1) l1 = u.fixed64(); else if (uw == 1 && uf == 2) l15 = u.fixed64(); else u.skip(uw); }
                        out.put(cf == 2 ? "cpuProcess" : "cpuSystem", SimplexAdminSuite.appMap("last1m", round(l1 * 100, 1), "last15m", round(l15 * 100, 1))); }
                    else if (cf == 4 && cw == 2) out.put("cpuModel", clip(c.str(), 80)); else c.skip(cw); } }
                case 2 -> { Msg mem = m.sub(); while (mem.more()) { long k = mem.varint(); int mf = (int) (k >>> 3), mw = (int) (k & 7); if ((mf == 1 || mf == 2) && mw == 2) { Msg p = mem.sub(); long used = 0, total = 0; while (p.more()) { long pk = p.varint(); int pf = (int) (pk >>> 3), pw = (int) (pk & 7); if (pf == 1 && pw == 0) used = p.varint(); else if (pf == 2 && pw == 0) total = p.varint(); else p.skip(pw); } out.put(mf == 1 ? "physical" : "swap", SimplexAdminSuite.appMap("used", used, "total", total)); } else mem.skip(mw); } }
                case 3 -> { Msg e = m.sub(); String k = ""; Map<String, Object> gc = null; while (e.more()) { long kk = e.varint(); int ef = (int) (kk >>> 3), ew = (int) (kk & 7); if (ef == 1 && ew == 2) k = e.str(); else if (ef == 2 && ew == 2) { Msg g = e.sub(); long total = 0; double avg = 0, freq = 0; while (g.more()) { long gk = g.varint(); int gf = (int) (gk >>> 3), gw = (int) (gk & 7); if (gf == 1 && gw == 0) total = g.varint(); else if (gf == 2 && gw == 1) avg = g.fixed64(); else if (gf == 3 && gw == 1) freq = g.fixed64(); else g.skip(gw); } gc = SimplexAdminSuite.appMap("total", total, "avgMs", round(avg, 1), "everyMs", Math.round(freq)); } else e.skip(ew); }
                    if (gc != null) { @SuppressWarnings("unchecked") Map<String, Object> all = (Map<String, Object>) out.computeIfAbsent("gc", x -> new LinkedHashMap<String, Object>()); if (all.size() < 8) all.put(clip(k, 40), gc); } }
                case 4 -> { Msg d = m.sub(); long used = 0, total = 0; while (d.more()) { long k = d.varint(); int df = (int) (k >>> 3), dw = (int) (k & 7); if (df == 1 && dw == 0) used = d.varint(); else if (df == 2 && dw == 0) total = d.varint(); else d.skip(dw); } out.put("disk", SimplexAdminSuite.appMap("used", used, "total", total)); }
                case 5 -> { Msg o = m.sub(); StringBuilder b = new StringBuilder(); while (o.more()) { long k = o.varint(); int of = (int) (k >>> 3), ow = (int) (k & 7); if (ow == 2 && of <= 3) b.append(b.length() > 0 ? " " : "").append(o.str()); else o.skip(ow); } out.put("os", clip(b.toString(), 80)); }
                case 6 -> { Msg j = m.sub(); while (j.more()) { long k = j.varint(); int jf = (int) (k >>> 3), jw = (int) (k & 7); if (jf == 2 && jw == 2) out.put("java", clip(j.str(), 40)); else if (jf == 1 && jw == 2) out.put("javaVendor", clip(j.str(), 40)); else if (jf == 4 && jw == 2) out.put("vmArgs", clip(j.str(), 1200)); else j.skip(jw); } }
                case 7 -> out.put("uptimeMs", m.varint());
                default -> m.skip(w);
            }
        }
        return out;
    }

    /** Picks the performance-relevant values out of the JSON configuration snapshots spark attaches. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> configSummary(Map<String, String> configs) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Object props = DiscordBotManager.Json.parse(configs.getOrDefault("server.properties", "{}"));
            if (props instanceof Map<?, ?> p) for (String k : List.of("view-distance", "simulation-distance", "network-compression-threshold", "max-tick-time", "sync-chunk-writes", "entity-broadcast-range-percentage", "online-mode", "max-players"))
                if (p.get(k) != null) out.put(k, String.valueOf(p.get(k)));
        } catch (RuntimeException ignored) { /* optional */ }
        try {
            Object spigot = DiscordBotManager.Json.parse(configs.getOrDefault("spigot.yml", "{}"));
            Object ws = spigot instanceof Map<?, ?> s ? s.get("world-settings") : null;
            Object def = ws instanceof Map<?, ?> w ? w.get("default") : null;
            if (def instanceof Map<?, ?> d) {
                if (d.get("entity-activation-range") instanceof Map<?, ?> ear) out.put("spigot.entity-activation-range", String.valueOf(ear.get("monsters")) + "/" + ear.get("animals") + "/" + ear.get("villagers"));
                if (d.get("mob-spawn-range") != null) out.put("spigot.mob-spawn-range", String.valueOf(d.get("mob-spawn-range")));
                if (d.get("merge-radius") instanceof Map<?, ?> mr) out.put("spigot.merge-radius", String.valueOf(mr.get("item")) + "/" + mr.get("exp"));
            }
        } catch (RuntimeException ignored) { /* optional */ }
        try {
            Object bukkit = DiscordBotManager.Json.parse(configs.getOrDefault("bukkit.yml", "{}"));
            if (bukkit instanceof Map<?, ?> b && b.get("spawn-limits") instanceof Map<?, ?> sl) out.put("bukkit.spawn-limits", String.valueOf(sl.get("monsters")) + "/" + sl.get("animals") + "/" + sl.get("water-animals"));
        } catch (RuntimeException ignored) { /* optional */ }
        return out;
    }

    private static Map<String, Object> windowSummary(Map<Integer, Map<Integer, Double>> windows) {
        List<Double> tps = new ArrayList<>(), median = new ArrayList<>(), max = new ArrayList<>();
        double players = 0, entities = 0, tiles = 0, chunks = 0, cpu = 0; int n = 0; double minTps = 99, peak = 0;
        for (Map<Integer, Double> w : windows.values()) {
            double t = w.getOrDefault(4, 0d), md = w.getOrDefault(5, 0d), mx = w.getOrDefault(6, 0d);
            tps.add(round(t, 2)); median.add(round(md, 1)); max.add(round(mx, 1));
            minTps = Math.min(minTps, t); peak = Math.max(peak, mx);
            players += w.getOrDefault(7, 0d); entities += w.getOrDefault(8, 0d); tiles += w.getOrDefault(9, 0d); chunks += w.getOrDefault(10, 0d); cpu += w.getOrDefault(2, 0d); n++;
        }
        if (n == 0) return SimplexAdminSuite.appMap("count", 0);
        double avgTps = tps.stream().mapToDouble(Double::doubleValue).average().orElse(0), avgMedian = median.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return SimplexAdminSuite.appMap("count", n, "tpsAvg", round(avgTps, 2), "tpsMin", round(minTps, 2), "msptMedianAvg", round(avgMedian, 1), "msptPeak", round(peak, 1),
            "playersAvg", round(players / n, 1), "entitiesAvg", Math.round(entities / n), "tileEntitiesAvg", Math.round(tiles / n), "chunksAvg", Math.round(chunks / n), "cpuProcessAvg", round(cpu / n * 100, 1),
            "tps", tail(tps, 120), "msptMedian", tail(median, 120), "msptMax", tail(max, 120));
    }
    private static List<Double> tail(List<Double> list, int n) { return list.size() <= n ? list : new ArrayList<>(list.subList(list.size() - n, list.size())); }

    // ------------------------------------------------------------------ findings
    private record Rule(String[] needles, String title, String hint) {}
    private static final List<Rule> RULES = List.of(
        new Rule(new String[]{"NaturalSpawner", "SpawnUtil", "spawnForChunk"}, "Mob-Spawning ist teuer", "spawn-limits und ticks-per (bukkit.yml) senken, Paper „per-player-mob-spawns“ aktiv lassen, mob-spawn-range verkleinern."),
        new Rule(new String[]{"tickBlockEntities", "HopperBlockEntity", "Hopper"}, "Viele Block-Entities (Trichter, Öfen, Truhen)", "Trichter-Ketten und Sortieranlagen prüfen; Paper „hopper.disable-move-event“/„hopper.cooldown-when-full“ nutzen; Lag-Karte zeigt die Chunks."),
        new Rule(new String[]{"PathNavigation", "PathFinder", "pathfind", "GoalSelector", "Brain.tick"}, "Mob-KI/Pathfinding", "Villager-/Mob-Farmen begrenzen (Paper: entity-per-chunk-save-limit, Villager „tick-inactive-villagers: false“), Entity-Activation-Range verkleinern."),
        new Rule(new String[]{"Villager"}, "Villager rechnen viel", "Große Handelshallen/Villagerfarmen verkleinern oder in Blöcke einsperren; Paper villager-Optimierungen aktivieren."),
        new Rule(new String[]{"EntityLookup", "TrackedEntity", "EntityTracker", "getEntities", "ChunkEntitySlices"}, "Sehr viele Entities", "Item-/Mob-Ansammlungen räumen (Lag-Karte), merge-radius erhöhen, entity-broadcast-range und Tracking-Range senken."),
        new Rule(new String[]{"NoiseBasedChunkGenerator", "ChunkGenerator", "WorldGen", "ChunkStatus", "StructureStart", "generate"}, "Chunk-Generierung", "Welt mit Chunky vorgenerieren und eine Weltgrenze setzen; Elytra-/Erkundungsflüge erzeugen sonst ständig neue Chunks."),
        new Rule(new String[]{"RedstoneWire", "Redstone", "PistonBase", "Observer"}, "Redstone/Kolben", "Große Redstone-Uhren oder Kolbenmaschinen suchen; Paper „redstone-implementation: ALTERNATE_CURRENT“ verwenden."),
        new Rule(new String[]{"ChunkMap.saveAllChunks", "RegionFile", "ChunkSerializer", "IOWorker"}, "Speichern von Chunks", "Autosave-Intervall/max-auto-save-chunks-per-tick prüfen; langsamer Datenträger beim Hoster möglich."),
        new Rule(new String[]{"LightEngine", "Starlight", "LightEngine"}, "Lichtberechnung", "Massive Blockänderungen (WorldEdit) verursachen Lichtupdates – zu Spitzenzeiten vermeiden."),
        new Rule(new String[]{"yaml", "snakeyaml", "YamlConfiguration.save", "saveToString"}, "Dateien werden im Server-Thread gespeichert", "Ein Plugin speichert YAML synchron im Tick (siehe Plugin-Frames) – Plugin aktualisieren oder Speicherintervall erhöhen."),
        new Rule(new String[]{"java.util.regex", "Pattern.matcher"}, "Reguläre Ausdrücke im Tick", "Ein Plugin wertet viele Regex im Server-Thread aus (Chat-/Schildfilter?)."),
        new Rule(new String[]{"ScriptLoader", "ch.njol.skript"}, "Skript-Code", "Skripte mit „every tick“/„periodical“-Ereignissen prüfen."));

    private static List<Map<String, Object>> findings(Map<String, Object> out, Map<String, double[]> self, double busy) {
        List<Map<String, Object>> list = new ArrayList<>();
        Object w = out.get("windows");
        if (w instanceof Map<?, ?> win && win.get("count") instanceof Number c && c.intValue() > 0) {
            double tps = ((Number) win.get("tpsAvg")).doubleValue(), median = ((Number) win.get("msptMedianAvg")).doubleValue(), peak = ((Number) win.get("msptPeak")).doubleValue();
            if (tps < 18) list.add(finding("crit", "TPS unter 18 (Ø " + fmt(tps) + ")", "Der Server schafft seine 20 Ticks nicht – Spieler merken Verzögerungen."));
            else list.add(finding("ok", "TPS stabil (Ø " + fmt(tps) + ")", "Der Server hält seine Tickrate."));
            if (median > 40) list.add(finding("crit", "Tick-Auslastung hoch: Ø Median " + fmt(median) + " ms von 50 ms", "Wenig Reserve – kleine Spitzen führen zu TPS-Einbrüchen."));
            else if (median > 30) list.add(finding("warn", "Tick-Auslastung erhöht: Ø Median " + fmt(median) + " ms", "Reserve wird knapp, vor allem bei mehr Spielern."));
            if (peak > 1000) list.add(finding("crit", "Hänger bis " + Math.round(peak) + " ms in einem Tick", "Einzelne Ticks dauern über eine Sekunde – Clients können dabei in Timeouts laufen."));
            else if (peak > 150) list.add(finding("warn", "Lag-Spitzen bis " + Math.round(peak) + " ms", "Kurze Ruckler; die Methoden unten zeigen die Ursachen."));
        }
        // rule-based hotspot hints from self time
        Map<Rule, Double> hits = new LinkedHashMap<>();
        for (var e : self.entrySet()) for (Rule r : RULES) for (String needle : r.needles) if (e.getKey().contains(needle)) { hits.merge(r, e.getValue()[0], Double::sum); break; }
        hits.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(5).forEach(e -> {
            double share = e.getValue() / busy * 100;
            if (share >= 3) list.add(finding(share >= 10 ? "warn" : "info", e.getKey().title + " (" + fmt(share) + " % der Rechenzeit)", e.getKey().hint));
        });
        if (out.get("plugins") instanceof List<?> plugins) for (Object o : plugins) if (o instanceof Map<?, ?> p && ((Number) p.get("share")).doubleValue() >= 5)
            list.add(finding(((Number) p.get("share")).doubleValue() >= 12 ? "warn" : "info", "Plugin " + p.get("name") + " verursacht " + fmt(((Number) p.get("share")).doubleValue()) + " % der Rechenzeit", "Plugin-Frames zeigen, welche Funktion es ist – Plugin aktualisieren oder seine Einstellungen prüfen."));
        if (out.get("metadata") instanceof Map<?, ?> meta) {
            if (meta.get("config") instanceof Map<?, ?> cfg) {
                int view = intOf(cfg.get("view-distance")), sim = intOf(cfg.get("simulation-distance"));
                if (view > 10) list.add(finding("info", "view-distance " + view, "Für viele Spieler sind 8–10 üblich; Paper sendet Chunks darüber hinaus effizient (no-tick view distance)."));
                if (sim > 8) list.add(finding("warn", "simulation-distance " + sim, "Jeder Spieler hält einen großen Bereich aktiv. 4–6 senkt die Tick-Last deutlich."));
            }
            if (meta.get("system") instanceof Map<?, ?> sys) {
                String args = String.valueOf(sys.get("vmArgs"));
                if (!args.contains("UseG1GC") && !args.contains("UseZGC") && !args.contains("UseShenandoahGC")) list.add(finding("info", "Kein empfohlener Garbage Collector gesetzt", "Aikar-Flags (G1GC) oder ZGC für kürzere Pausen."));
                if (sys.get("physical") instanceof Map<?, ?> phys && ((Number) phys.get("total")).longValue() > 0 && ((Number) phys.get("used")).doubleValue() / ((Number) phys.get("total")).doubleValue() > 0.95)
                    list.add(finding("warn", "Arbeitsspeicher des Hosts fast voll", "Der Container/Host nutzt über 95 % RAM – Gefahr von Abstürzen durch den OOM-Killer."));
            }
            if (meta.get("platformStatistics") instanceof Map<?, ?> ps && ps.get("world") instanceof Map<?, ?> world && world.get("hotChunks") instanceof List<?> hot && !hot.isEmpty() && hot.get(0) instanceof Map<?, ?> first && ((Number) first.get("entities")).longValue() >= 150)
                list.add(finding("warn", "Entity-Hotspot: " + first.get("entities") + " Entities in einem Chunk", "Welt " + first.get("world") + " bei X " + first.get("blockX") + " Z " + first.get("blockZ") + " – vermutlich eine Farm oder liegengebliebene Items."));
        }
        return list;
    }

    static Map<String, Object> finding(String level, String title, String hint) { return SimplexAdminSuite.appMap("level", level, "title", title, "hint", hint); }
    private static int intOf(Object o) { try { return Integer.parseInt(String.valueOf(o).trim()); } catch (RuntimeException e) { return 0; } }
    static double round(double v, int digits) { if (!Double.isFinite(v)) return 0; double f = Math.pow(10, digits); return Math.round(v * f) / f; }
    static String fmt(double v) { return String.format(Locale.GERMANY, v >= 100 ? "%.0f" : "%.1f", v); }
    static String clip(String s, int n) { if (s == null) return ""; String c = s.replaceAll("[\\p{Cc}]", " "); return c.length() <= n ? c : c.substring(0, n); }
}
