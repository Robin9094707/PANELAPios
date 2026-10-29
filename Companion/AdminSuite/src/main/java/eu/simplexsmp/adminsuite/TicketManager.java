package eu.simplexsmp.adminsuite;

import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

/** Support tickets for Java (menus + chat), Bedrock (native forms), Discord and the app. */
final class TicketManager implements Listener {
    static final String STAFF="simplexadminsuite.tickets.staff";
    private final SimplexAdminSuite plugin;
    final TicketStore store;
    private final Map<UUID,Long> onlineSince=new HashMap<>();
    private final Map<UUID,Prompt> prompts=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,Long> replyCooldown=new HashMap<>();
    private BukkitTask task;
    private record Prompt(Consumer<String> action,long expires) { }

    TicketManager(SimplexAdminSuite plugin,java.nio.file.Path folder)throws java.io.IOException{this.plugin=plugin;store=new TicketStore(folder);}
    void start(){task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,40L,20L);for(Player p:Bukkit.getOnlinePlayers())onlineSince.put(p.getUniqueId(),System.currentTimeMillis());}
    void stop(){if(task!=null)task.cancel();prompts.clear();onlineSince.clear();store.stop();}
    boolean enabled(){return plugin.getConfig().getBoolean("tickets.enabled",true);}
    int deliveryDelay(){return Math.max(5,Math.min(300,plugin.getConfig().getInt("tickets.delivery-delay-seconds",20)));}
    private TicketDiscord discord(){DiscordBotManager bot=plugin.discordRelay();return bot==null?null:bot.tickets;}
    private String t(Player p,String de,String en){return plugin.playerText(p,de,en);}
    boolean staff(CommandSender sender){return sender!=null&&plugin.has(sender,STAFF);}

    static String category(String id,boolean de){return switch(id){case "technical"->de?"Technischer Support":"Technical support";case "report"->de?"Spieler melden":"Report a player";case "discord"->"Discord";case "other"->de?"Sonstiges":"Other";default->de?"Allgemeiner Support":"General support";};}
    static String priority(String id,boolean de){return switch(id){case "low"->de?"Niedrig":"Low";case "high"->de?"Hoch":"High";case "urgent"->de?"Notfall":"Urgent";default->"Normal";};}
    static String priorityHint(String id,boolean de){return switch(id){
        case "low"->de?"Ideen, kleine Fragen, nichts Eiliges":"Ideas, small questions, nothing urgent";
        case "high"->de?"Schweres Problem, das zeitnah gelöst werden muss":"Major problem that needs prompt help";
        case "urgent"->de?"Nur bei akutem Notfall (Griefing, Sicherheitsproblem, Serverausfall). Missbrauch wird bestraft.":"Active emergencies only (griefing, security issue, outage). Abuse may be punished.";
        default->de?"Normale Supportanfrage":"Normal support request";};}
    static String status(String id,boolean de){return switch(id){case "answered"->de?"Beantwortet":"Answered";case "closed"->de?"Geschlossen":"Closed";default->de?"Offen":"Open";};}
    private static Material categoryIcon(String id){return switch(id){case "technical"->Material.REDSTONE;case "report"->Material.IRON_SWORD;case "discord"->Material.NAME_TAG;case "other"->Material.PAPER;default->Material.BOOK;};}
    private static Material priorityIcon(String id){return switch(id){case "low"->Material.LIME_DYE;case "high"->Material.ORANGE_DYE;case "urgent"->Material.RED_DYE;default->Material.YELLOW_DYE;};}

    // ---------------------------------------------------------------- lifecycle shared by Minecraft, Discord and the app

    /** Applies player limits, stores the ticket and fans out to staff, Bark and Discord. */
    TicketStore.Ticket open(UUID owner,String ownerName,String discordUser,String source,String category,String priority,String language,String subject,String text,boolean createDiscordChannel){
        if(!enabled())throw new IllegalArgumentException(language.equals("de")?"Das Ticketsystem ist deaktiviert.":"The ticket system is disabled.");
        boolean de=language.equals("de");
        int max=Math.max(1,Math.min(20,plugin.getConfig().getInt("tickets.max-open-per-player",3)));
        if(store.activeCount(owner,discordUser)>=max)throw new IllegalArgumentException(de?"Du hast bereits "+max+" offene Tickets. Bitte warte auf eine Antwort.":"You already have "+max+" open tickets. Please wait for a reply.");
        long cooldown=Math.max(0,plugin.getConfig().getInt("tickets.create-cooldown-seconds",60))*1000L,last=store.lastCreated(owner,discordUser);
        if(System.currentTimeMillis()-last<cooldown)throw new IllegalArgumentException(de?"Bitte warte kurz, bevor du ein weiteres Ticket erstellst.":"Please wait a moment before creating another ticket.");
        if(discordUser!=null&&discordUser.isBlank()&&owner!=null&&plugin.accountLinks!=null){var link=plugin.accountLinks.store.byMinecraft(owner);if(link!=null)discordUser=link.discord();}
        TicketStore.Ticket ticket=store.create(owner,ownerName,discordUser,source,category,priority,language,subject,text,ownerName,owner==null?discordUser:owner.toString());
        announce(ticket);
        TicketDiscord d=discord();if(createDiscordChannel&&d!=null)d.openedAsync(ticket.id);
        return ticket;
    }
    private void announce(TicketStore.Ticket ticket){
        long id=ticket.id;String subject=ticket.subject,name=ticket.ownerName,prio=ticket.priority,cat=ticket.category;UUID owner=ticket.owner;
        plugin.runAdminTask(()->{
            plugin.suiteAudit(name,"Ticket erstellt","#"+id+" · "+prio+" · "+subject);
            if(plugin.appEvents!=null&&plugin.getConfig().getBoolean("tickets.bark",true))
                plugin.appEvents.record(prio.equals("urgent")?"ticket-urgent":"ticket",owner,(prio.equals("urgent")?"🚨 Notfall-Ticket #":"Neues Ticket #")+id+" · "+name,category(cat,true)+" · "+priority(prio,true)+"\n"+subject,prio.equals("urgent"));
            for(Player staff:Bukkit.getOnlinePlayers())if(staff(staff)&&!staff.getUniqueId().equals(owner))notifyStaff(staff,id,t(staff,(prio.equals("urgent")?"§c§lNotfall-Ticket":"§bNeues Ticket")+" §7#"+id+" von §f"+name+"§7: §f"+TicketStore.clip(subject,60),(prio.equals("urgent")?"§c§lUrgent ticket":"§bNew ticket")+" §7#"+id+" from §f"+name+"§7: §f"+TicketStore.clip(subject,60)));
        });
    }
    TicketStore.Message staffReply(long id,String author,String authorId,String source,String text,boolean fromDiscord){
        TicketStore.Message m=store.reply(id,author,authorId,"staff",source,text);
        TicketDiscord d=discord();if(!fromDiscord&&d!=null)d.forwardAsync(id,m);
        plugin.runAdminTask(()->plugin.suiteAudit(author,"Ticket beantwortet","#"+id+" ("+source+")"));
        return m;
    }
    TicketStore.Message playerReply(long id,String author,String authorId,String source,String text,boolean fromDiscord){
        TicketStore.Message m=store.reply(id,author,authorId,"player",source,text);
        TicketDiscord d=discord();if(!fromDiscord&&d!=null)d.forwardAsync(id,m);
        TicketStore.Ticket ticket=store.get(id);UUID owner=ticket==null?null:ticket.owner;
        plugin.runAdminTask(()->{for(Player staff:Bukkit.getOnlinePlayers())if(staff(staff)&&!staff.getUniqueId().equals(owner))notifyStaff(staff,id,t(staff,"§bTicket §7#"+id+"§b: neue Antwort von §f"+author,"§bTicket §7#"+id+"§b: new reply from §f"+author));});
        return m;
    }
    TicketStore.Ticket close(long id,String actor,boolean fromDiscord){
        TicketStore.Ticket ticket=store.close(id,actor);
        store.system(id,(ticket.language.equals("de")?"Ticket geschlossen von ":"Ticket closed by ")+actor,true);
        TicketDiscord d=discord();if(!fromDiscord&&d!=null)d.closedAsync(id,actor);
        plugin.runAdminTask(()->plugin.suiteAudit(actor,"Ticket geschlossen","#"+id));
        return ticket;
    }
    TicketStore.Ticket reopen(long id,String actor,boolean fromDiscord){
        TicketStore.Ticket ticket=store.reopen(id);store.system(id,(ticket.language.equals("de")?"Ticket wieder geöffnet von ":"Ticket reopened by ")+actor,false);
        TicketDiscord d=discord();if(!fromDiscord&&d!=null)d.reopenedAsync(id,actor);
        plugin.runAdminTask(()->plugin.suiteAudit(actor,"Ticket wieder geöffnet","#"+id));
        return ticket;
    }
    TicketStore.Ticket claim(long id,String actor,boolean fromDiscord){
        TicketStore.Ticket ticket=store.claim(id,actor);TicketDiscord d=discord();if(!fromDiscord&&d!=null)d.noticeAsync(id,"🙋 "+actor+" kümmert sich um dieses Ticket / is handling this ticket.");return ticket;
    }
    TicketStore.Ticket priority(long id,String priority,String actor,boolean fromDiscord){
        TicketStore.Ticket ticket=store.priority(id,priority);TicketDiscord d=discord();if(!fromDiscord&&d!=null)d.noticeAsync(id,"⚑ Priorität / priority: "+priority(priority,true)+" · "+actor);return ticket;
    }

    // ---------------------------------------------------------------- delivery after continuous online time

    private void tick(){
        long now=System.currentTimeMillis();long delay=deliveryDelay()*1000L;
        prompts.entrySet().removeIf(e->e.getValue().expires<now);
        for(Player p:Bukkit.getOnlinePlayers()){
            if(plugin.adminRuntime!=null&&plugin.adminRuntime.blocked(p)){onlineSince.remove(p.getUniqueId());continue;}
            long since=onlineSince.computeIfAbsent(p.getUniqueId(),k->now);
            if(now-since<delay)continue;
            List<TicketStore.Pending> pending=store.pending(p.getUniqueId());
            for(TicketStore.Pending item:pending){deliver(p,item);store.delivered(item.ticket(),item.message().id());}
            if(!pending.isEmpty())p.playSound(p.getLocation(),"minecraft:block.note_block.chime",0.8F,1.2F);
            hint(p,now,since);
        }
    }
    private void deliver(Player p,TicketStore.Pending item){
        long id=item.ticket();boolean staffReply=item.message().role().equals("staff");
        String head=staffReply?t(p,"§b§l[Ticket #"+id+"] §bAntwort von §f"+item.message().author()+" §7(Team)","§b§l[Ticket #"+id+"] §bReply from §f"+item.message().author()+" §7(staff)"):"§b§l[Ticket #"+id+"] §7"+item.message().text();
        p.sendMessage(head);
        if(staffReply)p.sendMessage("§f"+TicketStore.clip(item.message().text(),400));
        TicketStore.Ticket ticket=store.get(id);
        actions(p,id,false,ticket!=null&&ticket.active());
    }
    /** Java gets clickable actions; Bedrock gets the exact command because chat clicks are unavailable there. */
    private void actions(Player p,long id,boolean staffView,boolean closedAware){
        if(plugin.isBedrockClient(p)){p.sendMessage(t(p,"§7Öffnen: §f/ticket view "+id+" §7· Antworten: §f/ticket reply "+id+" <Text>","§7Open: §f/ticket view "+id+" §7· Reply: §f/ticket reply "+id+" <text>"));return;}
        Component line=Component.text(t(p,"[Öffnen]","[Open]")).color(NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/ticket view "+id)).hoverEvent(HoverEvent.showText(Component.text("/ticket view "+id)))
            .append(Component.text("  ")).append(Component.text(t(p,"[Antworten]","[Reply]")).color(NamedTextColor.GREEN).clickEvent(ClickEvent.suggestCommand("/ticket "+(staffView?"answer ":"reply ")+id+" ")).hoverEvent(HoverEvent.showText(Component.text(t(p,"Antwort in den Chat schreiben","Type your reply in chat")))));
        if(closedAware)line=line.append(Component.text("  ")).append(Component.text(t(p,"[Schließen]","[Close]")).color(NamedTextColor.RED).clickEvent(ClickEvent.suggestCommand("/ticket close "+id)));
        p.sendMessage(line);
    }
    private void notifyStaff(Player staff,long id,String text){
        staff.sendMessage(text);
        if(!plugin.isBedrockClient(staff))staff.sendMessage(Component.text(t(staff,"[Ticket öffnen]","[Open ticket]")).color(NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/ticket view "+id)));
        else staff.sendMessage("§7/ticket view "+id);
    }
    private void hint(Player p,long now,long since){
        if(!plugin.getConfig().getBoolean("tickets.join-hint",true)||!enabled()||now-since<45_000L||now-since>60_000L)return;
        UUID id=p.getUniqueId();if(now-store.hinted(id)<7L*86_400_000L)return;store.hint(id,now);
        int active=store.activeCount(id,null);
        p.sendMessage(active>0?t(p,"§bDu hast "+active+" offene(s) Ticket(s). §7Öffnen mit §f/ticket","§bYou have "+active+" open ticket(s). §7Open them with §f/ticket"):t(p,"§bBrauchst du Hilfe vom Team? §7Erstelle ein Ticket mit §f/ticket","§bNeed help from staff? §7Open a ticket with §f/ticket"));
    }
    @EventHandler public void join(PlayerJoinEvent e){onlineSince.put(e.getPlayer().getUniqueId(),System.currentTimeMillis());}
    @EventHandler public void quit(PlayerQuitEvent e){onlineSince.remove(e.getPlayer().getUniqueId());prompts.remove(e.getPlayer().getUniqueId());}
    /** Called from the async chat listener; work is moved to the main thread. */
    boolean acceptText(Player p,String message){
        Prompt prompt=prompts.remove(p.getUniqueId());if(prompt==null||prompt.expires<System.currentTimeMillis())return false;
        plugin.runAdminTask(()->{if(!p.isOnline())return;if(Set.of("abbrechen","cancel").contains(message.trim().toLowerCase(Locale.ROOT))){p.sendMessage(t(p,"§7Abgebrochen.","§7Cancelled."));return;}prompt.action.accept(message);});
        return true;
    }
    private void prompt(Player p,String de,String en,Consumer<String> action){
        p.closeInventory();prompts.put(p.getUniqueId(),new Prompt(action,System.currentTimeMillis()+300_000L));
        p.sendMessage(t(p,"§b"+de+" §7(in den Chat schreiben · §fabbrechen§7 bricht ab)","§b"+en+" §7(type in chat · §fcancel§7 aborts)"));
    }

    // ---------------------------------------------------------------- commands and menus

    boolean command(CommandSender sender,String[] args){
        if(!enabled()){sender.sendMessage(plugin.prefix()+"Tickets are disabled.");return true;}
        String action=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
        if(!(sender instanceof Player p)){sender.sendMessage("Tickets: in-game /ticket, Discord or Simplex Panel. Offen: "+store.counts());return true;}
        try{
            switch(action){
                case ""->home(p);
                case "new","neu","create"->{if(args.length>1)create(p,"general","normal","",join(args,1));else wizard(p);}
                case "list","liste","meine"->mine(p);
                case "view","show","open","öffnen","oeffnen"->view(p,id(args,1));
                case "reply","antworten"->{long id=id(args,1);if(args.length>2)reply(p,id,join(args,2));else prompt(p,"Deine Antwort für Ticket #"+id,"Your reply for ticket #"+id,text->reply(p,id,text));}
                case "close","schließen","schliessen"->closeOwn(p,id(args,1));
                case "reopen"->{long id=id(args,1);TicketStore.Ticket ticket=readable(p,id);reopen(ticket.id,p.getName(),false);p.sendMessage(t(p,"§aTicket #"+id+" wieder geöffnet.","§aTicket #"+id+" reopened."));}
                case "staff","team","admin"->{requireStaff(p);staffList(p);}
                case "answer","beantworten"->{requireStaff(p);long id=id(args,1);if(args.length>2)staffAnswer(p,id,join(args,2));else prompt(p,"Teamantwort für Ticket #"+id,"Staff reply for ticket #"+id,text->staffAnswer(p,id,text));}
                case "claim"->{requireStaff(p);long id=id(args,1);claim(id,p.getName(),false);p.sendMessage("§aTicket #"+id+" übernommen.");}
                case "priority","prio"->{requireStaff(p);long id=id(args,1);if(args.length<3||!TicketStore.PRIORITIES.contains(args[2].toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("/ticket priority <id> low|normal|high|urgent");priority(id,args[2].toLowerCase(Locale.ROOT),p.getName(),false);p.sendMessage("§aPriorität gespeichert.");}
                default->{sender.sendMessage(t(p,"§b/ticket §7Menü · §f/ticket new <Text> §7· §f/ticket list §7· §f/ticket view <ID> §7· §f/ticket reply <ID> <Text> §7· §f/ticket close <ID>","§b/ticket §7menu · §f/ticket new <text> §7· §f/ticket list §7· §f/ticket view <ID> §7· §f/ticket reply <ID> <text> §7· §f/ticket close <ID>"));if(staff(p))sender.sendMessage("§7Team: §f/ticket staff · answer <ID> <Text> · claim <ID> · priority <ID> <Stufe>");}
            }
        }catch(IllegalArgumentException e){p.sendMessage(plugin.prefix()+"§c"+e.getMessage());}
        return true;
    }
    List<String> complete(CommandSender sender,String[] args){
        List<String> values=new ArrayList<>();
        if(args.length==1){values.addAll(List.of("new","list","view","reply","close","reopen"));if(staff(sender))values.addAll(List.of("staff","answer","claim","priority"));}
        else if(args.length==2&&sender instanceof Player p&&!args[0].equalsIgnoreCase("new")){for(TicketStore.Ticket t:staff(p)&&Set.of("answer","claim","priority","view").contains(args[0].toLowerCase(Locale.ROOT))?store.all():store.owned(p.getUniqueId(),null))if(t.active()||args[0].equalsIgnoreCase("reopen"))values.add(Long.toString(t.id));}
        else if(args.length==3&&args[0].equalsIgnoreCase("priority"))values.addAll(TicketStore.PRIORITIES);
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);return values.stream().distinct().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    private static String join(String[] args,int from){return String.join(" ",Arrays.copyOfRange(args,from,args.length));}
    private long id(String[] args,int index){if(args.length<=index)throw new IllegalArgumentException("Ticket-ID fehlt / missing ticket ID");try{return Long.parseLong(args[index].replace("#",""));}catch(NumberFormatException e){throw new IllegalArgumentException("Ungültige Ticket-ID / invalid ticket ID");}}
    private void requireStaff(Player p){if(!staff(p))throw new IllegalArgumentException(t(p,"Keine Berechtigung.","No permission."));}
    private TicketStore.Ticket readable(Player p,long id){TicketStore.Ticket ticket=store.get(id);if(ticket==null||!staff(p)&&!p.getUniqueId().equals(ticket.owner))throw new IllegalArgumentException(t(p,"Ticket #"+id+" nicht gefunden.","Ticket #"+id+" not found."));return ticket;}

    void home(Player p){
        List<CommunityMenus.Row> rows=new ArrayList<>();
        rows.add(CommunityMenus.row(t(p,"Neues Ticket","New ticket"),t(p,"Frage, Problem oder Meldung an das Team","Question, problem or report for staff"),Material.WRITABLE_BOOK,()->wizard(p)));
        int mine=store.activeCount(p.getUniqueId(),null);
        rows.add(CommunityMenus.row(t(p,"Meine Tickets","My tickets"),mine+" "+t(p,"offen","open"),Material.BOOKSHELF,()->mine(p)));
        if(staff(p)){var c=store.counts();rows.add(CommunityMenus.row(t(p,"Team: offene Tickets","Staff: open tickets"),c.get("open")+" "+t(p,"offen","open")+" · "+c.get("urgent")+" "+t(p,"Notfall","urgent"),Material.SHIELD,()->staffList(p)));}
        DiscordBotManager bot=plugin.discordRelay();
        String body=t(p,"Das Team antwortet hier im Spiel"+(bot!=null&&bot.enabled()?" und auf Discord":"")+". Antworten erreichen dich auch, wenn du offline warst.","Staff replies here in game"+(bot!=null&&bot.enabled()?" and on Discord":"")+". You receive replies even if you were offline.");
        plugin.communityMenus.show(p,t(p,"Support-Tickets","Support tickets"),body,rows,0,false);
    }
    private void wizard(Player p){
        boolean de=plugin.community!=null&&plugin.community.german(p);
        if(plugin.isBedrockClient(p)){
            List<String> cats=TicketStore.CATEGORIES.stream().map(c->category(c,de)).toList(),prios=TicketStore.PRIORITIES.stream().map(x->priority(x,de)+" – "+priorityHint(x,de)).toList();
            var form=SimplexAdminSuite.CustomForm.builder().playerFacing().title(t(p,"Neues Ticket","New ticket"))
                .dropdown(t(p,"Worum geht es?","What is it about?"),cats,0)
                .dropdown(t(p,"Dringlichkeit","Urgency"),prios,1)
                .input(t(p,"Betreff (optional)","Subject (optional)"),t(p,"Kurz zusammengefasst","Short summary"),"")
                .input(t(p,"Beschreibe dein Anliegen","Describe your request"),t(p,"Was ist passiert? Wo? Wer?","What happened? Where? Who?"),"")
                .validResultHandler(r->plugin.runAdminTask(()->{if(!p.isOnline())return;
                    try{create(p,TicketStore.CATEGORIES.get(Math.max(0,Math.min(TicketStore.CATEGORIES.size()-1,r.asDropdown(0)))),TicketStore.PRIORITIES.get(Math.max(0,Math.min(3,r.asDropdown(1)))),r.asInput(2),r.asInput(3));}
                    catch(IllegalArgumentException e){p.sendMessage(plugin.prefix()+"§c"+e.getMessage());}}));
            if(!plugin.sendBedrockForm(p,form))p.sendMessage(t(p,"§cFormular nicht verfügbar. Nutze §f/ticket new <Text>","§cForm unavailable. Use §f/ticket new <text>"));
            return;
        }
        List<CommunityMenus.Row> rows=new ArrayList<>();
        for(String c:TicketStore.CATEGORIES)rows.add(CommunityMenus.row(category(c,de),t(p,"Auswählen","Select"),categoryIcon(c),()->choosePriority(p,c)));
        plugin.communityMenus.show(p,t(p,"Neues Ticket · Thema","New ticket · topic"),t(p,"Wähle das passende Thema.","Choose the matching topic."),rows,0,false);
    }
    private void choosePriority(Player p,String cat){
        boolean de=plugin.community!=null&&plugin.community.german(p);List<CommunityMenus.Row> rows=new ArrayList<>();
        for(String x:TicketStore.PRIORITIES)rows.add(CommunityMenus.row(priority(x,de),priorityHint(x,de),priorityIcon(x),()->prompt(p,"Beschreibe dein Anliegen ("+category(cat,true)+", "+priority(x,true)+")","Describe your request ("+category(cat,false)+", "+priority(x,false)+")",text->{try{create(p,cat,x,"",text);}catch(IllegalArgumentException e){p.sendMessage(plugin.prefix()+"§c"+e.getMessage());}})));
        plugin.communityMenus.show(p,t(p,"Neues Ticket · Dringlichkeit","New ticket · urgency"),t(p,"Wähle ehrlich. Notfall nur bei akuten Problemen.","Choose honestly. Urgent is for emergencies only."),rows,0,false);
    }
    private void create(Player p,String cat,String prio,String subject,String text){
        boolean de=plugin.community!=null&&plugin.community.german(p);
        if(text==null||text.isBlank())throw new IllegalArgumentException(t(p,"Bitte beschreibe dein Anliegen.","Please describe your request."));
        TicketStore.Ticket ticket=open(p.getUniqueId(),p.getName(),"","minecraft",cat,prio,de?"de":"en",subject,text,true);
        p.sendMessage(t(p,"§aTicket §f#"+ticket.id+"§a erstellt. Das Team wurde benachrichtigt.","§aTicket §f#"+ticket.id+"§a created. Staff has been notified."));
        p.sendMessage(t(p,"§7Antworten erhältst du hier im Chat – auch wenn du offline warst.","§7Replies arrive here in chat – even if you were offline."));
        DiscordBotManager bot=plugin.discordRelay();
        if(bot!=null&&bot.enabled()&&plugin.accountLinks!=null&&plugin.accountLinks.store.byMinecraft(p.getUniqueId())==null)
            p.sendMessage(t(p,"§7Tipp: Verknüpfe Discord mit §f/link§7, dann siehst du das Ticket und alle Antworten auch auf Discord.","§7Tip: link Discord with §f/link§7 to see this ticket and every reply on Discord too."));
        actions(p,ticket.id,false,true);
    }
    private void mine(Player p){
        boolean de=plugin.community!=null&&plugin.community.german(p);List<CommunityMenus.Row> rows=new ArrayList<>();
        for(TicketStore.Ticket ticket:store.owned(p.getUniqueId(),null)){long id=ticket.id;rows.add(CommunityMenus.row("#"+id+" · "+TicketStore.clip(ticket.subject,40),status(ticket.status,de)+" · "+priority(ticket.priority,de)+" · "+plugin.profileDate(ticket.updated),ticket.active()?Material.WRITABLE_BOOK:Material.BOOK,()->view(p,id)));}
        if(rows.isEmpty())rows.add(CommunityMenus.row(t(p,"Neues Ticket","New ticket"),t(p,"Du hast noch keine Tickets.","You have no tickets yet."),Material.WRITABLE_BOOK,()->wizard(p)));
        plugin.communityMenus.show(p,t(p,"Meine Tickets","My tickets"),t(p,"Neueste zuerst","Newest first"),rows,0,false);
    }
    private void staffList(Player p){
        List<CommunityMenus.Row> rows=new ArrayList<>();
        Object listed=store.list("active","",0);
        if(listed instanceof Map<?,?> page&&page.get("items") instanceof List<?> items)for(Object raw:items)if(raw instanceof Map<?,?> row){
            long id=((Number)row.get("id")).longValue();String prio=String.valueOf(row.get("priority"));
            rows.add(CommunityMenus.row("#"+id+" · "+TicketStore.clip(String.valueOf(row.get("subject")),40),row.get("ownerName")+" · "+priority(prio,true)+" · "+status(String.valueOf(row.get("status")),true)+(String.valueOf(row.get("claimedBy")).isBlank()?"":" · "+row.get("claimedBy")),priorityIcon(prio),()->view(p,id)));
        }
        if(rows.isEmpty())rows.add(CommunityMenus.row("Keine offenen Tickets","Alles erledigt",Material.LIME_DYE,()->home(p)));
        plugin.communityMenus.show(p,"Team · Tickets","Notfälle zuerst, dann neueste Aktivität",rows,0,false);
    }
    void view(Player p,long id){
        TicketStore.Ticket ticket=readable(p,id);boolean de=plugin.community!=null&&plugin.community.german(p),staffView=staff(p)&&!p.getUniqueId().equals(ticket.owner);
        String header="#"+ticket.id+" · "+ticket.subject+"\n"+status(ticket.status,de)+" · "+priority(ticket.priority,de)+" · "+category(ticket.category,de)+(staffView?" · "+ticket.ownerName+(ticket.claimedBy.isBlank()?"":" · "+ticket.claimedBy):"");
        List<TicketStore.Message> messages;synchronized(store){messages=new ArrayList<>(ticket.messages);}
        List<TicketStore.Message> recent=messages.subList(Math.max(0,messages.size()-10),messages.size());
        if(plugin.isBedrockClient(p)){
            StringBuilder body=new StringBuilder(header).append("\n\n");
            for(TicketStore.Message m:recent)body.append(m.role().equals("staff")?"§b":m.role().equals("system")?"§7":"§f").append(m.author()).append(m.role().equals("staff")?t(p," (Team)"," (staff)"):"").append("§r: ").append(TicketStore.clip(m.text(),300)).append("\n");
            var form=SimplexAdminSuite.SimpleForm.builder().playerFacing().title(t(p,"Ticket #","Ticket #")+ticket.id).content(body.toString());
            if(ticket.active()||staffView)form.button(t(p,"Antworten","Reply"),r->plugin.runAdminTask(()->replyForm(p,id,staffView)));
            if(ticket.active())form.button(t(p,"Ticket schließen","Close ticket"),r->plugin.runAdminTask(()->{try{closeOwn(p,id);}catch(IllegalArgumentException e){p.sendMessage("§c"+e.getMessage());}}));
            else form.button(t(p,"Wieder öffnen","Reopen"),r->plugin.runAdminTask(()->{try{reopen(id,p.getName(),false);view(p,id);}catch(IllegalArgumentException e){p.sendMessage("§c"+e.getMessage());}}));
            if(staffView&&ticket.active())form.button("Übernehmen",r->plugin.runAdminTask(()->{claim(id,p.getName(),false);view(p,id);}));
            form.button(t(p,"Zurück","Back"),r->plugin.runAdminTask(()->{if(staffView)staffList(p);else mine(p);}));
            if(!plugin.sendBedrockForm(p,form)){p.sendMessage(header);for(TicketStore.Message m:recent)p.sendMessage("§7"+m.author()+": §f"+m.text());}
            return;
        }
        p.closeInventory();
        p.sendMessage("§8§m                                        ");
        p.sendMessage("§b§l"+header.replace("\n","\n§7"));
        for(TicketStore.Message m:recent)p.sendMessage((m.role().equals("staff")?"§b":m.role().equals("system")?"§8":"§f")+m.author()+(m.role().equals("staff")?t(p," §7(Team)"," §7(staff)"):"")+"§7: §f"+TicketStore.clip(m.text(),400));
        if(ticket.active()||staffView)actions(p,id,staffView,ticket.active());
        else p.sendMessage(Component.text(t(p,"[Wieder öffnen]","[Reopen]")).color(NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/ticket reopen "+id)));
        if(staffView&&ticket.active())p.sendMessage(Component.text("[Übernehmen]").color(NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/ticket claim "+id))
            .append(Component.text("  [Priorität]").color(NamedTextColor.GOLD).clickEvent(ClickEvent.suggestCommand("/ticket priority "+id+" "))));
    }
    private void replyForm(Player p,long id,boolean staffView){
        var form=SimplexAdminSuite.CustomForm.builder().playerFacing().title(t(p,"Antwort · Ticket #","Reply · ticket #")+id).input(t(p,"Deine Nachricht","Your message"),"","")
            .validResultHandler(r->plugin.runAdminTask(()->{if(!p.isOnline())return;try{if(staffView)staffAnswer(p,id,r.asInput(0));else reply(p,id,r.asInput(0));}catch(IllegalArgumentException e){p.sendMessage("§c"+e.getMessage());}}));
        if(!plugin.sendBedrockForm(p,form))p.sendMessage("§7/ticket reply "+id+" <Text>");
    }
    private boolean cooled(Player p){long now=System.currentTimeMillis();Long last=replyCooldown.put(p.getUniqueId(),now);return last==null||now-last>2500;}
    private void reply(Player p,long id,String text){
        TicketStore.Ticket ticket=readable(p,id);
        if(!p.getUniqueId().equals(ticket.owner)){staffAnswer(p,id,text);return;}
        if(!cooled(p))throw new IllegalArgumentException(t(p,"Bitte kurz warten.","Please wait a moment."));
        if(!ticket.active())throw new IllegalArgumentException(t(p,"Das Ticket ist geschlossen. Öffne es mit /ticket reopen "+id,"This ticket is closed. Reopen it with /ticket reopen "+id));
        playerReply(id,p.getName(),p.getUniqueId().toString(),"minecraft",text,false);
        p.sendMessage(t(p,"§aAntwort zu Ticket #"+id+" gesendet.","§aReply sent to ticket #"+id+"."));
    }
    private void staffAnswer(Player p,long id,String text){
        requireStaff(p);if(!cooled(p))throw new IllegalArgumentException("Bitte kurz warten.");
        TicketStore.Ticket ticket=store.get(id);if(ticket==null)throw new IllegalArgumentException("Ticket #"+id+" nicht gefunden.");
        staffReply(id,p.getName(),p.getUniqueId().toString(),"minecraft",text,false);
        Player owner=ticket.owner==null?null:Bukkit.getPlayer(ticket.owner);
        p.sendMessage("§aAntwort gespeichert. "+(ticket.owner==null?"Zustellung über Discord.":owner==null?"Spieler offline – Zustellung nach "+deliveryDelay()+" s Online-Zeit.":"Zustellung nach "+deliveryDelay()+" s Online-Zeit."));
    }
    private void closeOwn(Player p,long id){
        TicketStore.Ticket ticket=readable(p,id);
        if(!ticket.active())throw new IllegalArgumentException(t(p,"Ticket ist bereits geschlossen.","Ticket is already closed."));
        close(id,p.getName(),false);p.sendMessage(t(p,"§aTicket #"+id+" geschlossen.","§aTicket #"+id+" closed."));
    }

    // ---------------------------------------------------------------- app API

    Object appList(Map<String,String> query){
        int offset;try{offset=Integer.parseInt(query.getOrDefault("offset","0"));}catch(NumberFormatException e){offset=0;}
        Map<String,Object> page=new LinkedHashMap<>(castMap(store.list(query.getOrDefault("status","active"),query.getOrDefault("q",""),offset)));
        page.put("enabled",enabled());return page;
    }
    Object appTicket(long id){
        TicketStore.Ticket ticket=store.get(id);if(ticket==null)throw new IllegalArgumentException("Ticket #"+id+" nicht gefunden");
        Map<String,Object> row;synchronized(store){row=ticket.json();}
        Player owner=ticket.owner==null?null:Bukkit.getPlayer(ticket.owner);
        row.put("ownerOnline",owner!=null);row.put("deliveryDelay",deliveryDelay());row.put("guild",plugin.getConfig().getString("discord-bot.guild-id",""));
        row.put("ownerPlatform",owner==null?"":plugin.isBedrockClient(owner)?"BEDROCK":"JAVA");
        row.put("linked",ticket.owner!=null&&plugin.accountLinks!=null&&plugin.accountLinks.store.byMinecraft(ticket.owner)!=null);
        return row;
    }
    Object appAction(Map<String,Object> input,String actor,boolean full){
        String action=String.valueOf(input.getOrDefault("action",""));long id;
        try{id=Long.parseLong(String.valueOf(input.getOrDefault("id","0")));}catch(NumberFormatException e){throw new IllegalArgumentException("Ticket-ID ungültig");}
        String text=String.valueOf(input.getOrDefault("text",""));
        switch(action){
            case "reply"->{TicketStore.Message m=staffReply(id,actor,"app","app",text,false);return SimplexAdminSuite.appMap("ok",true,"title","Antwort gespeichert","description",m.delivered()?"Zugestellt.":"Wird nach "+deliveryDelay()+" Sekunden Online-Zeit in Minecraft zugestellt.","ticket",appTicket(id));}
            case "close"->close(id,actor,false);
            case "reopen"->reopen(id,actor,false);
            case "claim"->claim(id,actor,false);
            case "priority"->priority(id,String.valueOf(input.getOrDefault("priority","normal")),actor,false);
            case "delete"->{if(!full)throw new IllegalArgumentException("Löschen benötigt full-Zugriff");if(!store.delete(id))throw new IllegalArgumentException("Ticket nicht gefunden");plugin.suiteAudit(actor,"Ticket gelöscht","#"+id);return SimplexAdminSuite.appMap("ok",true,"title","Ticket gelöscht","description","#"+id+" wurde entfernt.");}
            default->throw new IllegalArgumentException("Unbekannte Ticketaktion");
        }
        return SimplexAdminSuite.appMap("ok",true,"title","Ticket aktualisiert","description","#"+id,"ticket",appTicket(id));
    }
    static final List<String> SETTINGS=List.of("enabled","delivery-delay-seconds","max-open-per-player","create-cooldown-seconds","join-hint","bark","discord.enabled","discord.channel-id","discord.mode","discord.category-id","discord.staff-role-ids","discord.log-channel-id","discord.ping-staff");
    Map<String,Object> settings(){
        var c=plugin.getConfig();
        return SimplexAdminSuite.appMap("enabled",enabled(),"deliveryDelay",deliveryDelay(),"maxOpen",c.getInt("tickets.max-open-per-player",3),"cooldown",c.getInt("tickets.create-cooldown-seconds",60),"joinHint",c.getBoolean("tickets.join-hint",true),"bark",c.getBoolean("tickets.bark",true),
            "discordEnabled",c.getBoolean("tickets.discord.enabled",true),"channelId",c.getString("tickets.discord.channel-id",""),"mode",c.getString("tickets.discord.mode","thread"),"categoryId",c.getString("tickets.discord.category-id",""),
            "staffRoleIds",c.getStringList("tickets.discord.staff-role-ids"),"logChannelId",c.getString("tickets.discord.log-channel-id",""),"pingStaff",c.getBoolean("tickets.discord.ping-staff",true),"counts",store.counts(),"storeError",store.lastError());
    }
    /** Validated, partial update; unknown fields are rejected and nothing outside tickets.* changes. */
    Object saveSettings(Map<String,Object> input,String actor){
        var c=plugin.getConfig();Map<String,Object> next=new LinkedHashMap<>();
        for(var e:input.entrySet()){
            String k=e.getKey();Object v=e.getValue();
            switch(k){
                case "enabled","joinHint","bark","discordEnabled","pingStaff"->next.put(switch(k){case "joinHint"->"join-hint";case "discordEnabled"->"discord.enabled";case "pingStaff"->"discord.ping-staff";default->k;},Boolean.parseBoolean(String.valueOf(v)));
                case "deliveryDelay"->next.put("delivery-delay-seconds",bounded(v,5,300));
                case "maxOpen"->next.put("max-open-per-player",bounded(v,1,20));
                case "cooldown"->next.put("create-cooldown-seconds",bounded(v,0,3600));
                case "channelId","categoryId","logChannelId"->{String id=String.valueOf(v).trim();if(!id.isEmpty()&&!id.matches("[0-9]{15,22}"))throw new IllegalArgumentException("Ungültige Discord-ID: "+k);next.put(switch(k){case "channelId"->"discord.channel-id";case "categoryId"->"discord.category-id";default->"discord.log-channel-id";},id);}
                case "mode"->{String mode=String.valueOf(v);if(!Set.of("thread","channel").contains(mode))throw new IllegalArgumentException("Modus: thread oder channel");next.put("discord.mode",mode);}
                case "staffRoleIds"->{List<String> roles=new ArrayList<>();for(String id:(v instanceof List<?> l?l.stream().map(String::valueOf).toList():Arrays.asList(String.valueOf(v).split(",")))){String r=id.trim();if(r.isEmpty())continue;if(!r.matches("[0-9]{15,22}"))throw new IllegalArgumentException("Ungültige Rollen-ID");roles.add(r);}if(roles.size()>10)throw new IllegalArgumentException("Maximal 10 Teamrollen");next.put("discord.staff-role-ids",roles);}
                default->throw new IllegalArgumentException("Unbekannte Ticketeinstellung: "+k);
            }
        }
        for(var e:next.entrySet())c.set("tickets."+e.getKey(),e.getValue());
        plugin.saveConfig();plugin.suiteAudit(actor,"Ticketeinstellungen gespeichert",String.join(",",next.keySet()));
        return SimplexAdminSuite.appMap("ok",true,"title","Ticketeinstellungen gespeichert","description",next.size()+" Werte gespeichert.","settings",settings());
    }
    private static int bounded(Object v,int min,int max){int n;try{n=(int)Double.parseDouble(String.valueOf(v));}catch(NumberFormatException e){throw new IllegalArgumentException("Zahl erwartet");}if(n<min||n>max)throw new IllegalArgumentException("Wert "+min+"–"+max+" erwartet");return n;}
    @SuppressWarnings("unchecked") private static Map<String,Object> castMap(Object value){return (Map<String,Object>)value;}
}
