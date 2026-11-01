package eu.simplexsmp.adminsuite;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

final class OnlineWatchManager implements Listener {
    final OnlineWatches store;private final SimplexAdminSuite plugin;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"SimplexOnlineWatches");t.setDaemon(true);return t;});
    private final Map<UUID,UUID> visits=new ConcurrentHashMap<>();private org.bukkit.scheduler.BukkitTask task;
    OnlineWatchManager(SimplexAdminSuite p)throws java.io.IOException{plugin=p;store=new OnlineWatches(p.getDataFolder().toPath().resolve("online-watches.properties"));}
    void start(){task=Bukkit.getScheduler().runTaskTimer(plugin,()->{List<Player> online=List.copyOf(Bukkit.getOnlinePlayers());work(()->{prune();store.expire(System.currentTimeMillis());for(Player p:online)notices(p);});},200,1200);}
    private void prune()throws java.io.IOException{for(var w:store.all()){var link=plugin.accountLinks.store.byDiscord(w.discord());if(link==null||!link.minecraft().equals(w.verified()))store.remove(w.id());}}
    interface Work{void run()throws Exception;}
    void work(Work r){try{worker.execute(()->{try{r.run();}catch(Exception e){plugin.getLogger().warning("Online notifications: "+e.getMessage());}});}catch(RejectedExecutionException ignored){}}
    void stop(){if(task!=null)task.cancel();worker.shutdown();try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    String t(Player p,String de,String en){return plugin.playerText(p,de,en);}
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){Player p=e.getPlayer();UUID visit=UUID.randomUUID();visits.put(p.getUniqueId(),visit);Bukkit.getScheduler().runTaskLater(plugin,()->{if(!p.isOnline()||!visit.equals(visits.get(p.getUniqueId())))return;work(()->joined(p,visit));},60);}
    @EventHandler public void quit(PlayerQuitEvent e){visits.remove(e.getPlayer().getUniqueId());}
    private void joined(Player p,UUID visit)throws Exception{
        prune();store.expire(System.currentTimeMillis());notices(p);
        List<String> notified=new ArrayList<>();DiscordBotManager bot=plugin.discordRelay();if(bot==null)return;
        for(OnlineWatches.Watch w:store.all()){
            if(!w.target().equals(p.getUniqueId())||!visit.equals(visits.get(p.getUniqueId()))||store.blocked(w.target()))continue;
            AccountLinks.Link verified=plugin.accountLinks.store.byDiscord(w.discord());
            if(verified==null||!verified.minecraft().equals(w.verified())){store.remove(w.id());continue;}
            if(w.mode().equals("permanent")&&w.asked()>0&&!w.accepted())continue;
            // Recheck immediately before the network request; only the intended subscriber can be mentioned.
            if(!bot.ticketRunning()||store.get(w.id())==null)continue;
            String content="<@"+w.discord()+"> "+(w.locale().equals("de")?w.player()+" ist jetzt online.":w.player()+" is now online.");
            if(bot.sendOnlineNotice(w.channel(),w.discord(),content)){store.delivered(w.id(),System.currentTimeMillis());notified.add(w.username());}
        }
        if(!notified.isEmpty())plugin.runAdminTask(()->{if(p.isOnline()){
            p.sendMessage(t(p,"§bÜber deinen Beitritt benachrichtigt: ","§bNotified about your join: ")+String.join(", ",notified));hint(p);
            if(store.all().stream().anyMatch(w->w.target().equals(p.getUniqueId())&&w.asked()>0&&!w.accepted())){p.sendMessage(t(p,"§eDauerhafte Anfragen: /joinnotify · Ohne Antwort nach 3 Tagen automatisch angenommen. Du kannst jederzeit ablehnen oder alles deaktivieren.","§ePermanent requests: /joinnotify · Automatically accepted after 3 days without a reply. You can decline or disable all notifications at any time."));menu(p);}
        }});
    }
    private void notices(Player p)throws Exception{Map<String,String> notices=store.peekNotices(p.getUniqueId());if(!notices.isEmpty())plugin.runAdminTask(()->{if(p.isOnline()){p.sendMessage(t(p,"§eNach 3 Tagen automatisch angenommen: ","§eAutomatically accepted after 3 days: ")+String.join(", ",notices.values()));hint(p);work(()->store.ackNotices(notices));}});}
    private void hint(Player p){if(plugin.isBedrockClient(p))p.sendMessage(t(p,"§7Verwalten / dauerhaft deaktivieren: /joinnotify","§7Manage / disable permanently: /joinnotify"));else p.sendMessage(Component.text(t(p,"[Beitrittshinweise verwalten]","[Manage join notifications]")).clickEvent(ClickEvent.runCommand("/joinnotify")));}
    boolean command(org.bukkit.command.CommandSender sender,String[] args){if(!(sender instanceof Player p))return true;
        if(args.length>0){String op=args[0];work(()->{if(op.equals("off")||op.equals("on"))store.block(p.getUniqueId(),op.equals("off"));else if(args.length==2&&(op.equals("yes")||op.equals("no")))store.decide(p.getUniqueId(),args[1],op.equals("yes"));plugin.runAdminTask(()->menu(p));});}else menu(p);return true;}
    void menu(Player p){if(!p.isOnline())return;List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        boolean off=store.blocked(p.getUniqueId());rows.add(new AdminUpdateMenu.Button(t(p,"Alle Beitrittshinweise","All join notifications"),off?t(p,"Deaktiviert · aktivieren","Disabled · enable"):t(p,"Aktiv · dauerhaft deaktivieren und alle löschen","Enabled · disable permanently and remove all"),off?Material.GRAY_DYE:Material.LIME_DYE,()->command(p,new String[]{off?"on":"off"})));
        for(var w:store.all())if(w.target().equals(p.getUniqueId())){
            String detail=w.username()+" · "+(w.accepted()?t(p,"dauerhaft erlaubt","permanent allowed"):w.asked()==0?t(p,"wartet auf ersten Hinweis","waiting for first notice"):t(p,"Anfrage · automatische Annahme nach 3 Tagen","request · auto-accept after 3 days"));
            if(w.asked()>0&&!w.accepted())rows.add(new AdminUpdateMenu.Button(t(p,"Erlauben: ","Allow: ")+w.username(),detail,Material.LIME_DYE,()->command(p,new String[]{"yes",w.id()})));
            rows.add(new AdminUpdateMenu.Button(t(p,"Entfernen / ablehnen: ","Remove / decline: ")+w.username(),detail,Material.BARRIER,()->work(()->{var current=store.get(w.id());if(current!=null&&current.target().equals(p.getUniqueId()))store.remove(w.id());plugin.runAdminTask(()->menu(p));})));
        }
        if(plugin.isBedrockClient(p)){var form=SimplexAdminSuite.SimpleForm.builder().title(t(p,"Beitrittshinweise","Join notifications")).content(t(p,"Einmalige Hinweise brauchen keine Zustimmung. Dauerhafte Anfragen werden nach 3 Tagen automatisch angenommen. Deaktivieren löscht alle Hinweise.","One-time notices need no consent. Permanent requests auto-accept after 3 days. Disabling removes all subscriptions."));for(var r:rows)form.button(r.title()+"\n"+r.detail(),x->plugin.runAdminTask(r.action()));plugin.sendBedrockForm(p,form);}
        else WatchInventory.show(plugin,p,rows,t(p,"Beitrittshinweise","Join notifications"));
    }
}
