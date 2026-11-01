package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.util.*;
/** Focused checks: additive identities, revocation, consent, opt-out and continuous memory samples. */
public final class Release960Harness {
    static int checks;static void check(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("suite960-");AppApiSecurity security=new AppApiSecurity(dir.resolve("api"));var old=security.issue("Existing iPhone","full");String id=security.serverId();
        security.users.save("Test_Player","example-long-password",Set.of("data.players","ticket.close","action.notifications.update"),true);
        check(security.users.login("test_player","wrong",System.currentTimeMillis())==null,"wrong password");
        String token=security.users.login("TEST_PLAYER","example-long-password",System.currentTimeMillis());var user=security.authenticate(token);
        check(user!=null&&security.valid(user),"login");check(security.users.allows(user,"ticket.close"),"explicit right");check(!security.users.allows(user,"ticket.delete"),"deny omitted right");check(security.canNotifyOwner(security.notificationOwner(user)),"stable per-user Bark owner");
        security=new AppApiSecurity(dir.resolve("api"));check(security.authenticate(token)!=null,"session survives restart");check(security.serverId().equals(id)&&security.authenticate(old.token())!=null,"legacy identity and key preserved");
        security.users.save("test_player","changed-long-password",Set.of("data.players"),true);check(security.authenticate(token)==null,"password/rights revoke session");check(!security.canNotifyOwner(security.notificationOwner(user)),"Bark permission revoked");check(!Files.readString(dir.resolve("api/users.properties")).contains("changed-long-password"),"no plaintext password");
        AccountLinks links=new AccountLinks(dir.resolve("discord-links.properties"));UUID first=UUID.randomUUID(),second=UUID.randomUUID();links.link(first,"123456789012345678","First");links.replace(first,"123456789012345678","223456789012345678","First");check(links.byDiscord("123456789012345678")==null,"old verification removed atomically");check(new AccountLinks(dir.resolve("discord-links.properties")).byMinecraft(first).discord().equals("223456789012345678"),"new verification persisted");
        OnlineWatches watches=new OnlineWatches(dir.resolve("online.properties"));var once=watches.add("123456789012345678",first,"DiscordA",second,"Target","323456789012345678","en","once");watches.delivered(once.id(),1000);check(watches.get(once.id())==null,"one-time consumed only after successful delivery");
        var repeat=watches.add("123456789012345678",first,"DiscordA",second,"Target","323456789012345678","de","permanent");check(watches.expire(System.currentTimeMillis()).isEmpty(),"consent never starts while target offline");watches.delivered(repeat.id(),1000);check(watches.expire(1000+OnlineWatches.CONSENT_MS-1).isEmpty(),"no early accept");check(watches.expire(1000+OnlineWatches.CONSENT_MS).size()==1,"accept at three days");check(watches.notices(second).equals(List.of("DiscordA")),"target receives auto-accept notice");watches.block(second,true);check(watches.all().isEmpty()&&new OnlineWatches(dir.resolve("online.properties")).blocked(second),"opt-out durable and clears watches");
        watches.block(second,false);for(int i=0;i<10;i++)watches.add("123456789012345678",first,"A",UUID.randomUUID(),"P","323456789012345678","en","once");boolean limit=false;try{watches.add("123456789012345678",first,"A",second,"Target","323456789012345678","en","once");}catch(IllegalArgumentException e){limit=true;}check(limit,"limit ten");
        ServerMemory.Window window=new ServerMemory.Window();var high=new ServerMemory.Sample(900,1000,"fixture");check(!window.sample(0,high,800,30000),"hold not instant");check(!window.sample(10000,new ServerMemory.Sample(-1,-1,"unavailable"),800,30000),"missing sample resets");check(!window.sample(20000,high,800,30000),"starts fresh");check(!window.sample(40000,high,800,30000)&&window.sample(50000,high,800,30000),"continuous high duration");
        System.out.println("PASS: "+checks+" focused 9.6.0 checks");
    }
}
