package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/** Private, bounded transcripts. Only recent turns whose data rights still apply are reused. */
final class AiConversations {
    private static final int MAX_THREADS = 30, MAX_TURNS = 60;
    private static final long DAY = 86_400_000L;
    private final Path folder;
    private final Logger logger;

    AiConversations(Path folder, Logger logger) { this.folder = folder; this.logger = logger; }

    synchronized Map<String,Object> list(AiAdmin.Actor actor) throws IOException {
        return Map.of("items", owned(actor).stream().map(this::metadata).toList());
    }

    synchronized Map<String,Object> create(AiAdmin.Actor actor, String title, int days) throws IOException {
        if (owned(actor).size() >= MAX_THREADS) throw new AppApiManager.ApiError(409,"Maximal 30 Chats. Lösche zuerst einen Chat.");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID().toString()); row.put("owner", actor.id());
        row.put("title", title(title)); row.put("days", retention(days));
        row.put("at", System.currentTimeMillis()); row.put("turns", List.of());
        save(row); return metadata(row);
    }

    synchronized Map<String,Object> open(AiAdmin.Actor actor, String id) throws IOException {
        Map<String,Object> row = require(actor,id), out = new LinkedHashMap<>(metadata(row));
        out.put("items", turns(row).stream().map(turn -> {
            if (readable(actor,turn)) return Map.of("at", turn.get("at"), "job", turn.get("job"), "question", turn.get("question"), "answer", turn.get("answer"));
            return Map.of("at", turn.get("at"), "job", "", "question", turn.get("question"), "answer", "Diese Antwort benötigt inzwischen entzogene Datenrechte.");
        }).toList());
        return out;
    }

    synchronized Map<String,Object> update(AiAdmin.Actor actor, String id, String name, int days) throws IOException {
        Map<String,Object> row = require(actor,id);
        row.put("title",title(name)); row.put("days",retention(days));
        if (expired(row)) { Files.deleteIfExists(file(actor,id)); return Map.of("id",id,"expired",true); }
        save(row); return metadata(row);
    }

    synchronized void delete(AiAdmin.Actor actor, String id) throws IOException {
        require(actor,id); Files.delete(file(actor,id));
    }

    synchronized List<Map<String,String>> context(AiAdmin.Actor actor, String id, Set<String> inheritedRights) throws IOException {
        Map<String,Object> row = require(actor,id);
        List<Map<String,Object>> turns = turns(row);
        List<Map<String,String>> recent = new ArrayList<>();
        int used = 0;
        for (int i = turns.size()-1; i >= 0 && recent.size() < 6; i--) {
            Map<String,Object> turn = turns.get(i);
            if (!readable(actor,turn)) continue;
            String question = AiAdmin.clean(AiAdmin.text(turn,"question"),450), answer = AiAdmin.clean(AiAdmin.text(turn,"answer"),750);
            if (used + question.length() + answer.length() > 4500) break;
            used += question.length() + answer.length();
            recent.add(Map.of("question",question,"answer",answer));
            inheritedRights.addAll(rights(turn));
        }
        Collections.reverse(recent);
        return recent;
    }

    synchronized void touch(AiAdmin.Actor actor, String id) throws IOException {
        Map<String,Object> row = require(actor,id);
        row.put("at",System.currentTimeMillis()); save(row);
    }

    synchronized void append(AiAdmin.Actor actor, String id, String job, String question, String answer, Set<String> rights) throws IOException {
        Map<String,Object> row = require(actor,id);
        List<Map<String,Object>> turns = new ArrayList<>(turns(row));
        turns.add(Map.of("at",System.currentTimeMillis(),"job",job,"question",question,"answer",answer,"rights",rights.stream().sorted().toList()));
        row.put("at",System.currentTimeMillis());
        row.put("turns",turns.subList(Math.max(0,turns.size()-MAX_TURNS),turns.size()));
        if (turns.size() == 1 && AiAdmin.text(row,"title").equals("Neuer Chat")) row.put("title",title(AiAdmin.clean(question.replace('\n',' '),65)));
        save(row);
    }

    synchronized void cleanup() {
        if (!Files.isDirectory(folder)) return;
        try (var paths = Files.newDirectoryStream(folder,"*.properties")) {
            for (Path path : paths) {
                try { if (expired(load(path))) Files.deleteIfExists(path); }
                catch (IOException | RuntimeException ignored) { logger.warning("KI-Chat-Datei konnte nicht bereinigt werden."); }
            }
        } catch (IOException ignored) { logger.warning("KI-Chat-Bereinigung konnte nicht ausgeführt werden."); }
    }

    private List<Map<String,Object>> owned(AiAdmin.Actor actor) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        List<Map<String,Object>> rows = new ArrayList<>();
        // The account hash in filenames avoids reading other accounts' transcripts during listing.
        try (var paths = Files.newDirectoryStream(folder,ownerPrefix(actor)+"-*.properties")) {
            for (Path path : paths) {
                Map<String,Object> row = load(path);
                if (expired(row)) Files.deleteIfExists(path);
                else if (AiAdmin.text(row,"owner").equals(actor.id())) rows.add(row);
            }
        }
        rows.sort(Comparator.<Map<String,Object>>comparingLong(r -> AiAdmin.number(r.get("at"))).reversed());
        return rows;
    }

    private Map<String,Object> require(AiAdmin.Actor actor, String id) throws IOException {
        Path path = file(actor,id);
        if (Files.isRegularFile(path)) {
            Map<String,Object> row = load(path);
            if (expired(row)) Files.deleteIfExists(path);
            else if (AiAdmin.text(row,"owner").equals(actor.id())) return row;
        }
        throw new AppApiManager.ApiError(404,"Chat fehlt oder wurde automatisch gelöscht.");
    }

    private static List<Map<String,Object>> turns(Map<String,Object> row) { return AiAdmin.objects(row.get("turns"),MAX_TURNS); }
    private static List<String> rights(Map<String,Object> turn) { return turn.get("rights") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }
    private static boolean readable(AiAdmin.Actor actor, Map<String,Object> turn) { return rights(turn).stream().allMatch(actor::can); }
    private static boolean expired(Map<String,Object> row) {
        long days = AiAdmin.number(row.get("days"));
        return days > 0 && AiAdmin.number(row.get("at")) + days * DAY <= System.currentTimeMillis();
    }
    private Map<String,Object> metadata(Map<String,Object> row) { return Map.of("id",row.get("id"),"title",row.get("title"),"days",row.get("days"),"at",row.get("at")); }
    private static int retention(int days) {
        if (!Set.of(0,1,7,30).contains(days)) throw new IllegalArgumentException("Auto-Löschen: Aus, 1, 7 oder 30 Tage.");
        return days;
    }
    private static String title(String title) {
        String value = AiAdmin.redact(AiAdmin.clean(title.replace('\n',' '),65));
        return value.isBlank() ? "Neuer Chat" : value;
    }
    private static String ownerPrefix(AiAdmin.Actor actor) { return AppApiSecurity.hash(actor.id()); }
    private Path file(AiAdmin.Actor actor, String id) {
        try { if (UUID.fromString(id).toString().equals(id)) return folder.resolve(ownerPrefix(actor)+"-"+id+".properties"); }
        catch (IllegalArgumentException ignored) { }
        throw new AppApiManager.ApiError(404,"Chat fehlt.");
    }
    private Map<String,Object> load(Path file) throws IOException {
        Properties p = new Properties();
        try (var input = Files.newBufferedReader(file)) { p.load(input); }
        return new LinkedHashMap<>(AiAdmin.object(DiscordBotManager.Json.parse(p.getProperty("chat","{}"))));
    }
    private void save(Map<String,Object> row) throws IOException {
        String name = AppApiSecurity.hash(AiAdmin.text(row,"owner"))+"-"+AiAdmin.text(row,"id")+".properties";
        Path path = folder.resolve(name);
        Properties p = new Properties(); p.setProperty("chat",DiscordBotManager.Json.stringify(row));
        Files.createDirectories(folder);
        AppApiSecurity.protect(folder);
        AtomicState.save(path,p,"Private Simplex admin assistant conversation");
        AppApiSecurity.protect(path);
    }
}
