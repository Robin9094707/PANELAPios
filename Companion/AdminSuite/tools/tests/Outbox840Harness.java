package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real SQLite regression: upgrade, bounded coalescing, retry, crash-safe pending state, ACK race. */
public final class Outbox840Harness {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void tick(SecurityLogOutbox box)throws Exception{var method=SecurityLogOutbox.class.getDeclaredMethod("tick");method.setAccessible(true);method.invoke(box);}
    static void pause(SecurityLogOutbox box)throws Exception{var f=SecurityLogOutbox.class.getDeclaredField("worker");f.setAccessible(true);((ScheduledExecutorService)f.get(box)).shutdownNow();}
    static SecurityLogOutbox.Message message(String value){return new SecurityLogOutbox.Message("123456789012345678","Client-Prüfung","summary", "{\"uuid\":\"player-1\",\"value\":\""+value+"\"}");}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("sas840-");Path file=dir.resolve("queue.db");Class.forName("org.sqlite.JDBC");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);Statement s=c.createStatement()){
            s.execute("CREATE TABLE pending(id INTEGER PRIMARY KEY,channel TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,dossier TEXT NOT NULL,due INTEGER NOT NULL,attempt INTEGER NOT NULL DEFAULT 0)");
            s.execute("INSERT INTO pending VALUES(1,'123456789012345678','legacy','kept','{}',0,0)");
        }
        List<String> errors=new ArrayList<>();AtomicBoolean available=new AtomicBoolean(false),enabled=new AtomicBoolean(false);AtomicInteger sends=new AtomicInteger();
        try(SecurityLogOutbox box=new SecurityLogOutbox(file,m->{sends.incrementAndGet();if(!available.get())throw new IllegalStateException("synthetic offline");return true;},errors::add,c->enabled.get())){
            pause(box);check(box.pending()==1,"8.3 database migration preserves pending row");
            for(int i=0;i<10000;i++)check(box.enqueue(message("event-"+i)),"repeated pending observation accepted");
            check(box.pending()==2,"10000 repeated observations occupy one additional row");check(((Number)box.status().get("observations")).longValue()==10001,"coalesced observation count retained");
            tick(box);check(sends.get()==0,"disabled channels stay queued without network requests");enabled.set(true);tick(box);
            check(((Number)box.status().get("maxAttempts")).intValue()==1,"sender exception advances durable retry");check(box.pending()==2,"failed sender never deletes row");
        }
        available.set(true);
        try(SecurityLogOutbox box=new SecurityLogOutbox(file,m->true,errors::add)){pause(box);check(box.pending()==2,"restart retains legacy and coalesced rows");box.retry();tick(box);check(box.pending()==0,"recovery drains all due rows in bounded batch");}
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);
        try(SecurityLogOutbox box=new SecurityLogOutbox(dir.resolve("race.db"),m->{started.countDown();try{finish.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return true;},errors::add)){
            pause(box);box.enqueue(message("old"));Thread sender=new Thread(()->{try{tick(box);}catch(Exception e){throw new RuntimeException(e);}});sender.start();check(started.await(3,TimeUnit.SECONDS),"sender started");
            check(box.enqueue(message("new")),"new observation accepted during HTTP request");check(box.pending()==2,"in-flight row cannot coalesce with newer observation");finish.countDown();sender.join(5000);check(!sender.isAlive(),"bounded send completes");check(box.pending()==0,"newer observation delivered separately, not lost to old ACK");
        }
        CountDownLatch oldStarted=new CountDownLatch(1),oldFinish=new CountDownLatch(1);AtomicInteger deliveries=new AtomicInteger();
        try(SecurityLogOutbox box=new SecurityLogOutbox(dir.resolve("clear-race.db"),m->{if(deliveries.incrementAndGet()==1){oldStarted.countDown();try{oldFinish.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return true;}return false;},errors::add)){
            pause(box);box.enqueue(message("before-clear"));Thread sender=new Thread(()->{try{tick(box);}catch(Exception e){throw new RuntimeException(e);}});sender.start();check(oldStarted.await(3,TimeUnit.SECONDS),"old HTTP send started");
            check(box.clear(),"explicit manual clear succeeds");check(box.enqueue(message("after-clear")),"new event accepted after clear");oldFinish.countDown();sender.join(5000);
            check(box.pending()==1,"old HTTP ACK cannot delete a reused row ID after explicit clear");
        }
        ClientEventWindow window=new ClientEventWindow(10000);var event=new ClientEventWindow.Event(UUID.randomUUID(),"Player","event","detail","{}",0);var first=window.offer(event);window.requeue(first,0);check(window.flush(59999).isEmpty(),"failed immediate delivery waits full 60 seconds");check(window.flush(60000).size()==1,"retained window retries at 60 seconds");
        System.out.println("PASS "+checks+" SQLite/queue/migration/retry/ACK regression checks");
    }
}
