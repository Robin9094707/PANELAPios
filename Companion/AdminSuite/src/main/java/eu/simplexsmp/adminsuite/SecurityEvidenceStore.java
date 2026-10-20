package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Additive storage, separate from player profiles, bans and account correlation. */
final class SecurityEvidenceStore implements AutoCloseable {
    record Entry(long at,String detector,String code,String detail) { }
    record Snapshot(long at,String brand,String version,List<String> channels) {
        Snapshot { channels=List.copyOf(channels); }
    }
    private final Connection database;
    private final Consumer<String> error;
    private final int maximum;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Simplex-security-history");t.setDaemon(true);return t;});
    private final Map<UUID,Deque<Entry>> entries=new LinkedHashMap<>();
    private final Map<UUID,Snapshot> snapshots=new LinkedHashMap<>();
    SecurityEvidenceStore(Path file,int maximum,Consumer<String> error)throws Exception {
        this.maximum=Math.max(20,Math.min(4096,maximum));this.error=error;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");database=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());
        try(Statement s=database.createStatement()) {
            try(ResultSet version=s.executeQuery("PRAGMA user_version")){if(version.next()&&version.getInt(1)>1)throw new SQLException("Newer security history format; not rewriting it");}
            s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS security_events (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, at INTEGER NOT NULL, detector TEXT NOT NULL, code TEXT NOT NULL, detail TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS security_events_player ON security_events(player,id)");
            s.execute("CREATE TABLE IF NOT EXISTS security_snapshots (player TEXT PRIMARY KEY, at INTEGER NOT NULL, brand TEXT NOT NULL, version TEXT NOT NULL, channels TEXT NOT NULL)");
            s.execute("PRAGMA user_version=1");
            try(ResultSet rows=s.executeQuery("SELECT player,at,detector,code,detail FROM security_events ORDER BY id DESC")) {
                while(rows.next()) {UUID id=UUID.fromString(rows.getString(1));Deque<Entry> history=entries.computeIfAbsent(id,k->new ArrayDeque<>());if(history.size()<this.maximum)history.addFirst(new Entry(rows.getLong(2),rows.getString(3),rows.getString(4),rows.getString(5)));}
            }
            try(ResultSet rows=s.executeQuery("SELECT player,at,brand,version,channels FROM security_snapshots")) {
                while(rows.next()) {Object value=DiscordBotManager.Json.parse(rows.getString(5));List<String> channels=new ArrayList<>();if(value instanceof List<?> list)for(Object channel:list)channels.add(Objects.toString(channel,""));snapshots.put(UUID.fromString(rows.getString(1)),new Snapshot(rows.getLong(2),rows.getString(3),rows.getString(4),channels));}
            }
        }catch(Exception failure){database.close();writer.shutdown();throw failure;}
    }
    List<Entry> entries(UUID id){List<Entry> result=new ArrayList<>(entries.getOrDefault(id,new ArrayDeque<>()));Collections.reverse(result);return List.copyOf(result);}
    Set<UUID> players(){Set<UUID> result=new LinkedHashSet<>(snapshots.keySet());result.addAll(entries.keySet());return Set.copyOf(result);}
    Snapshot snapshot(UUID id){return snapshots.get(id);}
    void snapshot(UUID id,Snapshot next) {
        Snapshot old=snapshots.get(id);if(old!=null&&next.at-old.at<60000&&old.brand.equals(next.brand)&&old.version.equals(next.version)&&old.channels.equals(next.channels))return;
        snapshots.put(id,next);
        submit(()->{try(PreparedStatement s=database.prepareStatement("INSERT INTO security_snapshots VALUES (?,?,?,?,?) ON CONFLICT(player) DO UPDATE SET at=excluded.at,brand=excluded.brand,version=excluded.version,channels=excluded.channels")){
            s.setString(1,id.toString());s.setLong(2,next.at);s.setString(3,next.brand);s.setString(4,next.version);s.setString(5,DiscordBotManager.Json.stringify(next.channels));s.executeUpdate();
        }});
    }
    void append(UUID id,Entry value) {
        Deque<Entry> history=entries.computeIfAbsent(id,k->new ArrayDeque<>());history.addLast(value);while(history.size()>maximum)history.removeFirst();
        submit(()->{database.setAutoCommit(false);try {
            try(PreparedStatement s=database.prepareStatement("INSERT INTO security_events(player,at,detector,code,detail) VALUES (?,?,?,?,?)")){
                s.setString(1,id.toString());s.setLong(2,value.at);s.setString(3,value.detector);s.setString(4,value.code);s.setString(5,value.detail);s.executeUpdate();
            }
            try(PreparedStatement s=database.prepareStatement("DELETE FROM security_events WHERE player=? AND id NOT IN (SELECT id FROM security_events WHERE player=? ORDER BY id DESC LIMIT ?)")){
                s.setString(1,id.toString());s.setString(2,id.toString());s.setInt(3,maximum);s.executeUpdate();
            }
            database.commit();
        }catch(SQLException failed){database.rollback();throw failed;}finally{database.setAutoCommit(true);}});
    }
    void clear(UUID id)throws Exception {
        writer.submit(()->{try {
            database.setAutoCommit(false);
            for(String table:List.of("security_events","security_snapshots"))try(PreparedStatement s=database.prepareStatement("DELETE FROM "+table+" WHERE player=?")){s.setString(1,id.toString());s.executeUpdate();}
            database.commit();
        }catch(SQLException e){try{database.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw new CompletionException(e);}finally{try{database.setAutoCommit(true);}catch(SQLException e){error.accept("Security history transaction: "+e.getMessage());}}}).get(6,TimeUnit.SECONDS);
        entries.remove(id);snapshots.remove(id);
    }
    void flush()throws Exception{writer.submit(()->{}).get(6,TimeUnit.SECONDS);}
    private interface Write {void run()throws SQLException;}
    private void submit(Write work){writer.execute(()->{try{work.run();}catch(SQLException e){error.accept("Security history write failed: "+e.getMessage());}});}
    @Override public void close() {
        writer.execute(()->{try{database.close();}catch(SQLException e){error.accept("Security history close failed: "+e.getMessage());}});
        writer.shutdown();try{if(!writer.awaitTermination(6,TimeUnit.SECONDS))error.accept("Security history writes are still finishing.");}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
