package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Durable queue. Failed delivery never deletes evidence; repeated pending observations coalesce. */
final class SecurityLogOutbox implements AutoCloseable {
    record Message(String channel,String title,String summary,String dossier){ }
    private static final int MAX_ROWS=8192, MAX_DOSSIER=2000000;
    private static final long MAX_BYTES=64L*1024*1024;
    private final Connection db;
    private final Function<Message,Boolean> send;
    private final Consumer<String> errors;
    private final Predicate<String> enabled;
    private long inFlight=-1, lastWarning;
    private volatile String storageError="", deliveryState="Bereit";
    private volatile long lastSuccess, nextAttempt;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Simplex-private-dossiers");t.setDaemon(true);return t;});
    SecurityLogOutbox(Path file,Function<Message,Boolean> send,Consumer<String> errors)throws SQLException{this(file,send,errors,c->true);}
    SecurityLogOutbox(Path file,Function<Message,Boolean> send,Consumer<String> errors,Predicate<String> enabled)throws SQLException{
        this.send=send;this.errors=errors;this.enabled=enabled;
        try{Class.forName("org.sqlite.JDBC");}catch(ClassNotFoundException missing){throw new SQLException("Bundled SQLite driver missing",missing);}
        db=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(Statement s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=1000");
            s.execute("CREATE TABLE IF NOT EXISTS pending(id INTEGER PRIMARY KEY,channel TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,dossier TEXT NOT NULL,due INTEGER NOT NULL,attempt INTEGER NOT NULL DEFAULT 0)");
            Set<String> columns=new HashSet<>();try(ResultSet r=s.executeQuery("PRAGMA table_info(pending)")){while(r.next())columns.add(r.getString("name"));}
            if(!columns.contains("event_key"))s.execute("ALTER TABLE pending ADD COLUMN event_key TEXT NOT NULL DEFAULT ''");
            if(!columns.contains("observations"))s.execute("ALTER TABLE pending ADD COLUMN observations INTEGER NOT NULL DEFAULT 1");
            s.execute("CREATE INDEX IF NOT EXISTS pending_due ON pending(due,id)");
            s.execute("CREATE INDEX IF NOT EXISTS pending_key ON pending(channel,event_key)");
        }
        worker.scheduleWithFixedDelay(this::tick,2,2,TimeUnit.SECONDS);
    }
    private synchronized void failure(String message){storageError=message;long now=System.currentTimeMillis();if(now-lastWarning>=900000){lastWarning=now;errors.accept(message+"; Ereignisse bleiben gespeichert. Details im App-Protokollstatus.");}}
    synchronized boolean enqueue(Message m){
        if(m.dossier.length()>MAX_DOSSIER){failure("Einzelnes Discord-Dossier überschreitet 2000000 Zeichen");return false;}
        String key="";try{Object data=DiscordBotManager.Json.parse(m.dossier);if(data instanceof Map<?,?> map&&map.get("uuid")!=null)key=map.get("uuid").toString()+":"+m.title.replace(" • gesammelt","");}catch(RuntimeException ignored){}
        try{
            // Never replace the row being transmitted: its ACK must not remove a newer observation.
            long replace=-1;
            if(!key.isEmpty())try(PreparedStatement s=db.prepareStatement("SELECT id FROM pending WHERE channel=? AND event_key=? AND id<>? ORDER BY id DESC LIMIT 1")){s.setString(1,m.channel);s.setString(2,key);s.setLong(3,inFlight);try(ResultSet r=s.executeQuery()){if(r.next())replace=r.getLong(1);}}
            long used;int rows;
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT count(*),coalesce(sum(length(CAST(dossier AS BLOB))+length(CAST(summary AS BLOB))),0) FROM pending")){r.next();rows=r.getInt(1);used=r.getLong(2);}
            long size=m.dossier.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+m.summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(replace<0&&(rows>=MAX_ROWS||used+size>MAX_BYTES)){failure("Discord-Protokollspeicher ausgelastet ("+rows+" Einträge); Versand/Kanalrechte prüfen");return false;}
            if(replace>=0){long oldSize=0;try(PreparedStatement s=db.prepareStatement("SELECT length(CAST(dossier AS BLOB))+length(CAST(summary AS BLOB)) FROM pending WHERE id=?")){s.setLong(1,replace);try(ResultSet r=s.executeQuery()){if(r.next())oldSize=r.getLong(1);}}if(used-oldSize+size>MAX_BYTES){failure("Discord-Protokollspeicher ausgelastet");return false;}}
            try(PreparedStatement s=db.prepareStatement(replace>=0?"UPDATE pending SET title=?,summary=?,dossier=?,observations=min(1000000,observations+1) WHERE id=?":"INSERT INTO pending(title,summary,dossier,channel,event_key,due) VALUES(?,?,?,?,?,?)")){
                s.setString(1,m.title);s.setString(2,m.summary);s.setString(3,m.dossier);
                if(replace>=0)s.setLong(4,replace);else{s.setString(4,m.channel);s.setString(5,key);s.setLong(6,System.currentTimeMillis());}s.executeUpdate();
            }storageError="";return true;
        }catch(SQLException e){failure("Discord-Protokollspeicher: SQLite-Fehler "+e.getErrorCode());return false;}
    }
    private void tick(){
        for(int batch=0;batch<8&&!Thread.currentThread().isInterrupted();batch++)try{
            long id=-1;int attempt=0;Message message=null;
            synchronized(this){
                try(PreparedStatement s=db.prepareStatement("SELECT * FROM pending WHERE due<=? ORDER BY due,id")){
                    s.setLong(1,System.currentTimeMillis());try(ResultSet r=s.executeQuery()){while(r.next()){
                        if(!enabled.test(r.getString("channel")))continue;
                        id=r.getLong("id");attempt=r.getInt("attempt");int observations=r.getInt("observations");
                        String summary=r.getString("summary")+(observations>1?"\n\nGesammelte Beobachtungen: "+observations+". Anhang: letzter Stand; frühere Details im lokalen Spielerprofil.":"");
                        message=new Message(r.getString("channel"),r.getString("title"),summary,r.getString("dossier"));inFlight=id;break;
                    }}
                }
            }
            if(message==null)return;
            boolean delivered=false;
            try{delivered=Boolean.TRUE.equals(send.apply(message));}catch(RuntimeException e){deliveryState="Versandfehler: "+e.getClass().getSimpleName();}
            long due=System.currentTimeMillis()+Math.min(900000,5000L*(1L<<Math.min(8,attempt)));
            synchronized(this){try(PreparedStatement s=db.prepareStatement(delivered?"DELETE FROM pending WHERE id=?":"UPDATE pending SET due=?,attempt=? WHERE id=?")){
                if(delivered)s.setLong(1,id);else{s.setLong(1,due);s.setInt(2,Math.min(10000,attempt+1));s.setLong(3,id);}s.executeUpdate();
            }finally{inFlight=-1;}}
            if(delivered){lastSuccess=System.currentTimeMillis();deliveryState="Versand erfolgreich";nextAttempt=0;}
            else{deliveryState="Versand wartet; Discord-Status und private Kanalrechte prüfen";nextAttempt=due;return;}
        }catch(Exception e){synchronized(this){inFlight=-1;}if(!Thread.currentThread().isInterrupted())failure("Discord-Protokollspeicher: "+e.getClass().getSimpleName());return;}
    }
    synchronized Map<String,Object> status(){
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT count(*),coalesce(sum(observations),0),coalesce(sum(length(CAST(dossier AS BLOB))+length(CAST(summary AS BLOB))),0),coalesce(max(attempt),0) FROM pending")){
            r.next();return SimplexAdminSuite.appMap("pending",r.getInt(1),"observations",r.getLong(2),"bytes",r.getLong(3),"maxAttempts",r.getInt(4),"capacity",MAX_ROWS,"maxBytes",MAX_BYTES,"storageError",storageError,"deliveryState",deliveryState,"lastSuccess",lastSuccess,"nextAttempt",nextAttempt);
        }catch(SQLException e){return SimplexAdminSuite.appMap("pending",-1,"storageError","SQLite-Fehler "+e.getErrorCode());}
    }
    synchronized void retry(){try(Statement s=db.createStatement()){s.executeUpdate("UPDATE pending SET due=0,attempt=0");nextAttempt=System.currentTimeMillis();}catch(SQLException e){throw new IllegalArgumentException("Protokollspeicher nicht verfügbar");}}
    synchronized boolean clear(){try(Statement s=db.createStatement()){s.executeUpdate("DELETE FROM pending");return true;}catch(SQLException e){return false;}}
    synchronized int pending(){return ((Number)status().get("pending")).intValue();}
    @Override public void close(){worker.shutdownNow();try{worker.awaitTermination(25,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}synchronized(this){try{db.close();}catch(SQLException e){errors.accept("Protokollwarteschlange konnte nicht geschlossen werden");}}}
}
