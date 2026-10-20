package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.util.*;
public final class AppApiSecurityHarness {
    static int checks;
    static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
    static void denied(Runnable work){try{work.run();throw new AssertionError("Expected denial");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("suite-api-security-");
        try{
            AppApiSecurity first=new AppApiSecurity(dir);String id=first.serverId();AppApiSecurity.Issued read=first.issue("Read phone","read"),full=first.issue("Full phone","full"),manage=first.issue("Moderator","manage");
            check(first.authenticate(read.token()).equals(read.key()),"valid read token");check(first.authenticate("wrong")==null,"wrong token rejected");check(!read.token().equals(full.token()),"independent random tokens");
            check(!Files.readString(dir.resolve("keys.properties")).contains(full.token()),"no raw tokens persisted");
            AppApiSecurity loaded=new AppApiSecurity(dir);check(loaded.serverId().equals(id),"stable identity");check(loaded.authenticate(full.token())!=null,"key survives restart");
            check(AppApiSecurity.permitted(read.key(),"read")&&!AppApiSecurity.permitted(read.key(),"manage")&&!AppApiSecurity.permitted(read.key(),"full"),"read scope cannot mutate");
            check(AppApiSecurity.permitted(manage.key(),"manage")&&!AppApiSecurity.permitted(manage.key(),"full"),"moderator cannot use full endpoints");check(AppApiSecurity.permitted(full.key(),"full"),"full allowed");
            String ticket=loaded.prepare(full.key(),"immutable-original-action",1000);denied(()->loaded.consume(read.key(),ticket,1001));check(loaded.consume(full.key(),ticket,1001).equals("immutable-original-action"),"ticket bound to original request/key");denied(()->loaded.consume(full.key(),ticket,1002));
            String expired=loaded.prepare(full.key(),"action",1000);denied(()->loaded.consume(full.key(),expired,61001));
            String revoked=loaded.prepare(full.key(),"action",1000);loaded.revoke(full.key().id());check(loaded.authenticate(full.token())==null,"revoked token rejected immediately");denied(()->loaded.consume(full.key(),revoked,1001));
            check(new AppApiSecurity(dir).authenticate(full.token())==null,"revocation durable");
            AppApiSecurity.checkJson("{\"nested\":{\"text\":\"[quoted]\\\"\"}}");checks++;
            denied(()->AppApiSecurity.checkJson("[".repeat(25)+"]".repeat(25)));denied(()->AppApiSecurity.checkJson("{\"bad\":\"open"));
            for(int i=0;i<30;i++)loaded.issue("Device"+i,"read");try{loaded.issue("Too many","full");throw new AssertionError("Key limit ignored");}catch(IllegalArgumentException expected){checks++;}
            System.out.println("PASS "+checks+" API authentication/confirmation checks");
        }finally{try(var stream=Files.walk(dir)){for(Path p:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
