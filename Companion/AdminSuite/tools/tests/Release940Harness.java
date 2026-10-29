package eu.simplexsmp.adminsuite;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

/** 9.4.0: durable app chat, support tickets (store, lifecycle, delivery queue, settings) and Discord permission math. */
public final class Release940Harness {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void denied(Runnable action,String message){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("suite940-");Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object old=serverField.get(null);
        try{
            // Synchronous scheduler so main-thread hand-offs run inline.
            BukkitScheduler scheduler=AppApiIntegrationHarness.proxy(BukkitScheduler.class,(p,m,v)->{if(m.getName().equals("runTask")&&v.length==2&&v[1] instanceof Runnable r){r.run();return null;}return AppApiIntegrationHarness.empty(m.getReturnType());});
            Server server=AppApiIntegrationHarness.proxy(Server.class,(p,m,v)->switch(m.getName()){case "getScheduler"->scheduler;case "getOnlinePlayers"->List.of();case "getPlayer"->null;case "getLogger"->java.util.logging.Logger.getLogger("Release940");default->AppApiIntegrationHarness.empty(m.getReturnType());});
            serverField.set(null,server);

            // App chat: 50 rows, survives a restart, clear rotates the epoch and keeps counting IDs.
            Path chatFile=folder.resolve("app-chat.properties");
            AppChat chat=new AppChat();chat.attach(chatFile);for(int i=0;i<70;i++)chat.record("","Player","Message "+i,i%2==0?"player":"discord");chat.stop();
            AppChat reloaded=new AppChat();reloaded.attach(chatFile);Map<?,?> page=(Map<?,?>)reloaded.read(0);List<Map<?,?>> items=(List<Map<?,?>>)page.get("items");
            check(items.size()==AppChat.LIMIT&&AppChat.LIMIT==50,"chat keeps the last 50 messages across restarts");
            check(items.get(items.size()-1).get("text").equals("Message 69")&&page.get("cursor").equals("70"),"newest message and cursor restored");
            String epoch=String.valueOf(page.get("epoch"));reloaded.clear(null,"Harness");
            Map<?,?> cleared=(Map<?,?>)reloaded.read(0);check(((List<?>)cleared.get("items")).isEmpty()&&!epoch.equals(cleared.get("epoch")),"clear empties history and rotates epoch");
            reloaded.record("","A","after clear","player");check(((Map<?,?>)((List<?>)((Map<?,?>)reloaded.read(70)).get("items")).get(0)).get("id").equals("71"),"IDs continue after clear");reloaded.stop();
            Files.writeString(chatFile,"row.0={broken");AppChat damaged=new AppChat();damaged.attach(chatFile);check(((List<?>)((Map<?,?>)damaged.read(0)).get("items")).isEmpty(),"damaged chat file does not block startup");damaged.stop();

            // Ticket store: lifecycle, delivery queue, ordering and persistence.
            Path ticketDir=folder.resolve("tickets");TicketStore store=new TicketStore(ticketDir);
            UUID alex=new UUID(0,7),sam=new UUID(0,8);
            var a=store.create(alex,"Alex","","minecraft","technical","normal","de","","Mein Haus wurde gegrieft\nbei 100 64 100","Alex",alex.toString());
            var b=store.create(sam,"Sam","123456789012345678","discord","report","urgent","en","Griefer","Someone is griefing spawn","Sam","123456789012345678");
            check(a.id==1&&b.id==2&&a.subject.startsWith("Mein Haus"),"sequential IDs and subject fallback");
            check(store.pending(alex).isEmpty(),"player message needs no delivery");
            var reply=store.reply(a.id,"Robin","app","staff","app","Wir schauen uns das an.");
            check(!reply.delivered()&&store.pending(alex).size()==1&&store.get(a.id).status.equals("answered"),"staff reply waits for Minecraft delivery");
            check(store.counts().get("waitingDelivery").equals(1),"waiting delivery counter");
            store.delivered(a.id,reply.id());check(store.pending(alex).isEmpty(),"delivered reply leaves the queue");
            var discordOnly=store.create(null,"Jo","999999999999999999","discord","general","low","en","","Question","Jo","999999999999999999");
            check(store.reply(discordOnly.id,"Robin","1","staff","discord","Answer").delivered(),"Discord-only tickets need no Minecraft delivery");
            store.reply(a.id,"Alex",alex.toString(),"player","minecraft","Danke!");check(store.get(a.id).status.equals("open"),"player reply reopens the queue for staff");
            Map<?,?> list=(Map<?,?>)store.list("active","",0);check(((Map<?,?>)((List<?>)list.get("items")).get(0)).get("id").equals(2L),"urgent tickets are listed first");
            check(((List<?>)((Map<?,?>)store.list("active","grief",0)).get("items")).size()==2,"search spans subject and text preview owners");
            store.close(b.id,"Robin");denied(()->store.close(b.id,"Robin"),"double close rejected");denied(()->store.reply(b.id,"Sam","x","player","discord","hello?"),"closed ticket rejects player replies");
            store.reopen(b.id);check(store.get(b.id).active(),"reopen");
            store.channel(b.id,"111111111111111111","thread");check(store.byChannel("111111111111111111").id==b.id,"channel lookup");store.channelDeleted("111111111111111111");check(store.byChannel("111111111111111111")==null,"deleted Discord channel is released");
            check(store.activeCount(null,"123456789012345678")==1&&store.activeCount(alex,null)==1,"active counts per owner");
            store.putState("panel.message","222222222222222222");store.hint(alex,123L);
            var pendingReply=store.reply(a.id,"Robin","app","staff","app","Noch offen?");store.stop();
            TicketStore again=new TicketStore(ticketDir);
            check(again.all().size()==3&&again.get(a.id).messages.size()==4&&again.stateValue("panel.message","").equals("222222222222222222")&&again.hinted(alex)==123L,"tickets, messages and panel state survive a restart");
            check(again.pending(alex).size()==1&&again.pending(alex).get(0).message().id()==pendingReply.id(),"undelivered replies survive a restart");
            var c=again.create(alex,"Alex","","minecraft","general","normal","de","Neu","Neues Ticket","Alex",alex.toString());check(c.id==4,"sequence continues after restart");
            denied(()->again.create(alex,"Alex","","minecraft","bogus","normal","de","","x","Alex",""),"unknown category rejected");
            denied(()->again.create(alex,"Alex","","minecraft","general","normal","de","","   ","Alex",""),"empty ticket rejected");
            check(TicketStore.clean("a§cb\u0007c",10).equals("a cbc"),"formatting and control characters removed");
            again.stop();Files.writeString(ticketDir.resolve("ticket-99.json"),"{not json");TicketStore third=new TicketStore(ticketDir);
            check(third.all().size()==4&&Files.exists(ticketDir.resolve("ticket-99.json.damaged")),"damaged ticket file is set aside, not deleted");third.stop();

            // Ticket manager on a synthetic plugin: limits, cooldown, settings validation.
            SimplexAdminSuite plugin=Moderation790Harness.plugin(folder.resolve("plugin"));Files.createDirectories(folder.resolve("plugin"));
            Moderation790Harness.set(plugin,JavaPlugin.class,"configFile",folder.resolve("plugin/config.yml").toFile());
            for(Field field:SimplexAdminSuite.class.getDeclaredFields())if(field.getType()==ThreadLocal.class&&field.getName().equals("adminActor")){field.setAccessible(true);field.set(plugin,new ThreadLocal<String>());}
            Moderation790Harness.set(plugin,SimplexAdminSuite.class,"auditedConfig",new HashMap<String,String>());
            TicketManager manager=new TicketManager(plugin,folder.resolve("plugin/tickets"));plugin.tickets=manager;
            plugin.getConfig().set("tickets.create-cooldown-seconds",0);plugin.getConfig().set("tickets.max-open-per-player",2);
            var t1=manager.open(alex,"Alex","","minecraft","general","normal","de","","Eins",false);manager.open(alex,"Alex","","minecraft","general","normal","de","","Zwei",false);
            denied(()->manager.open(alex,"Alex","","minecraft","general","normal","de","","Drei",false),"open ticket limit enforced");
            var m=manager.staffReply(t1.id,"Robin","app","app","Antwort",false);check(!m.delivered(),"staff reply from the app waits for the player");
            Map<String,Object> result=(Map<String,Object>)manager.appAction(new HashMap<>(Map.of("action","priority","id",Long.toString(t1.id),"priority","high")),"Panel",false);
            check(((Map<?,?>)result.get("ticket")).get("priority").equals("high"),"app priority action");
            denied(()->manager.appAction(new HashMap<>(Map.of("action","delete","id",Long.toString(t1.id))),"Panel",false),"delete requires full");
            manager.appAction(new HashMap<>(Map.of("action","close","id",Long.toString(t1.id))),"Panel",false);check(!manager.store.get(t1.id).active()&&manager.store.pending(alex).size()==2,"close notice is queued for the player");
            manager.saveSettings(new HashMap<>(Map.of("channelId","123456789012345678","mode","channel","staffRoleIds","234567890123456789, 345678901234567890","deliveryDelay",25,"joinHint",false)),"Panel");
            check(plugin.getConfig().getString("tickets.discord.mode").equals("channel")&&plugin.getConfig().getStringList("tickets.discord.staff-role-ids").size()==2&&manager.deliveryDelay()==25,"settings saved under tickets.*");
            denied(()->manager.saveSettings(new HashMap<>(Map.of("channelId","#general")),"Panel"),"invalid channel ID rejected");
            denied(()->manager.saveSettings(new HashMap<>(Map.of("discord-bot.token","x")),"Panel"),"unknown/secret settings rejected");
            denied(()->manager.saveSettings(new HashMap<>(Map.of("deliveryDelay",1)),"Panel"),"delivery delay bounded");
            check(TicketManager.priority("urgent",true).equals("Notfall")&&TicketManager.category("report",false).equals("Report a player"),"DE/EN labels");
            manager.stop();

            // Discord: effective permissions follow Discord's overwrite order; command schema stays valid.
            String guild="100000000000000000",role="200000000000000000",member="300000000000000000";
            long base=TicketDiscord.VIEW|TicketDiscord.SEND;
            List<Object> overwrites=List.of(Map.of("id",guild,"type",0,"allow","0","deny",Long.toString(TicketDiscord.VIEW)),Map.of("id",role,"type",0,"allow",Long.toString(TicketDiscord.VIEW|TicketDiscord.CREATE_PRIVATE),"deny","0"),Map.of("id",member,"type",1,"allow","0","deny",Long.toString(TicketDiscord.SEND)));
            long effective=TicketDiscord.effective(base,overwrites,guild,Set.of(role),member);
            check((effective&TicketDiscord.VIEW)!=0&&(effective&TicketDiscord.CREATE_PRIVATE)!=0&&(effective&TicketDiscord.SEND)==0,"role allow beats @everyone deny, member deny wins last");
            check((TicketDiscord.effective(base,overwrites,guild,Set.of(),"")&TicketDiscord.VIEW)==0,"@everyone deny applies without roles");
            check(TicketDiscord.effective(TicketDiscord.ADMIN,overwrites,guild,Set.of(),"")==~0L,"administrator bypasses overwrites");
            TicketDiscord discord=new TicketDiscord(plugin,null);List<Object> commands=new ArrayList<>();commands.add(discord.command());DiscordCommandSchema.validate(commands);checks++;
            check(SuiteEventCenter.TYPES.containsAll(Set.of("ticket","ticket-urgent")),"Bark ticket event types");
            System.out.println("Release940Harness: "+checks+" production checks passed");
        }finally{serverField.set(null,old);try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
