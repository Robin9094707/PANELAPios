package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Durable bounded delivery queue; network work never runs on the server thread. */
final class SecurityLogOutbox implements AutoCloseable {
    record Message(String channel,String title,String summary,String dossier){ }
    private final Connection db;private final Function<Message,Boolean> send;private final Consumer<String> errors;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Simplex-private-dossiers");t.setDaemon(true);return t;});
    SecurityLogOutbox(Path file,Function<Message,Boolean> send,Consumer<String> errors)throws SQLException{this.send=send;this.errors=errors;try{Class.forName("org.sqlite.JDBC");}catch(ClassNotFoundException missing){throw new SQLException("Bundled SQLite driver missing",missing);}db=DriverManager.getConnection("jdbc:sqlite:"+file);try(Statement s=db.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");s.execute("CREATE TABLE IF NOT EXISTS pending(id INTEGER PRIMARY KEY,channel TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,dossier TEXT NOT NULL,due INTEGER NOT NULL,attempt INTEGER NOT NULL DEFAULT 0)");}worker.scheduleWithFixedDelay(this::tick,2,2,TimeUnit.SECONDS);}
    synchronized boolean enqueue(Message m){if(m.dossier.length()>200000)return false;try(Statement count=db.createStatement();ResultSet rows=count.executeQuery("SELECT count(*),coalesce(sum(length(dossier)),0) FROM pending")){if(rows.next()&&(rows.getInt(1)>=2048||rows.getLong(2)>64L*1024*1024))return false;try(PreparedStatement s=db.prepareStatement("INSERT INTO pending(channel,title,summary,dossier,due) VALUES(?,?,?,?,?)")){s.setString(1,m.channel);s.setString(2,m.title);s.setString(3,m.summary);s.setString(4,m.dossier);s.setLong(5,System.currentTimeMillis());s.executeUpdate();return true;}}catch(SQLException e){errors.accept("Private Protokollwarteschlange konnte nicht gespeichert werden");return false;}}
    private void tick(){try{long id;int attempt;Message message;synchronized(this){try(PreparedStatement s=db.prepareStatement("SELECT id,channel,title,summary,dossier,attempt FROM pending WHERE due<=? ORDER BY id LIMIT 1")){s.setLong(1,System.currentTimeMillis());try(ResultSet rows=s.executeQuery()){if(!rows.next())return;id=rows.getLong(1);message=new Message(rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5));attempt=rows.getInt(6);}}}
        boolean delivered=Boolean.TRUE.equals(send.apply(message));synchronized(this){try(PreparedStatement s=db.prepareStatement(delivered?"DELETE FROM pending WHERE id=?":"UPDATE pending SET due=?,attempt=? WHERE id=?")){if(delivered)s.setLong(1,id);else{s.setLong(1,System.currentTimeMillis()+Math.min(900000,5000L*(1L<<Math.min(8,attempt))));s.setInt(2,Math.min(10000,attempt+1));s.setLong(3,id);}s.executeUpdate();}}
    }catch(Exception e){if(!Thread.currentThread().isInterrupted())errors.accept("Private Discord-Protokolle warten auf erneuten Versand: "+e.getClass().getSimpleName());}}
    synchronized boolean clear(){try(Statement s=db.createStatement()){s.executeUpdate("DELETE FROM pending");return true;}catch(SQLException e){return false;}}
    synchronized int pending(){try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM pending")){return r.next()?r.getInt(1):0;}catch(SQLException e){return -1;}}
    @Override public void close(){worker.shutdownNow();try{worker.awaitTermination(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}synchronized(this){try{db.close();}catch(SQLException e){errors.accept("Protokollwarteschlange konnte nicht geschlossen werden");}}}
}
