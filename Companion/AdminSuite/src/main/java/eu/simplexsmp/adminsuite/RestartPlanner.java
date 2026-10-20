package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Wall-clock scheduling in an explicit zone; a DST overlap never restarts twice. */
final class RestartPlanner {
    static final String ROOT="restart-calendar.";
    record Occurrence(Instant at,String key){ }
    private final SimplexAdminSuite plugin;private final Path file;
    private final Properties completed=new Properties();private final Set<String> announced=new HashSet<>();private BukkitTask task;
    RestartPlanner(SimplexAdminSuite plugin)throws IOException{this.plugin=plugin;file=plugin.getDataFolder().toPath().resolve("restart-calendar-state.properties");if(Files.exists(file))try(Reader in=Files.newBufferedReader(file)){completed.load(in);}}
    void start(){if(task==null)task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);}
    void stop(){if(task!=null)task.cancel();task=null;}
    static ZoneId zone(String input){return ZoneId.of(input==null||input.isBlank()?"Europe/Berlin":input);}
    static List<LocalTime> times(List<String> values){TreeSet<LocalTime> result=new TreeSet<>();for(String raw:values){if(!raw.matches("[0-2][0-9]:[0-5][0-9]"))throw new IllegalArgumentException("Uhrzeit als HH:mm");LocalTime t=LocalTime.parse(raw);result.add(t);}return List.copyOf(result);}
    static Occurrence next(Instant now,ZoneId zone,List<LocalTime> times,Set<String> completed){Occurrence best=null;LocalDate today=now.atZone(zone).toLocalDate();for(int days=0;days<3;days++)for(LocalTime time:times){LocalDate day=today.plusDays(days);String key=day+"/"+time;Instant instant=day.atTime(time).atZone(zone).toInstant();if(instant.isBefore(now.minusSeconds(2))||completed.contains(key))continue;if(best==null||instant.isBefore(best.at))best=new Occurrence(instant,key);}return best;}
    private Occurrence next(){try{return next(Instant.now(),zone(plugin.getConfig().getString(ROOT+"timezone","Europe/Berlin")),times(plugin.getConfig().getStringList(ROOT+"times")),completed.stringPropertyNames());}catch(RuntimeException invalid){return null;}}
    private String nextLabel(){Occurrence n=next();return n==null?"Kein gültiger Termin":n.at.atZone(zone(plugin.getConfig().getString(ROOT+"timezone","Europe/Berlin"))).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z"));}
    private void tick(){if(!plugin.getConfig().getBoolean(ROOT+"enabled",false))return;try{
        Occurrence next=next();if(next==null)return;long seconds=Duration.between(Instant.now(),next.at).getSeconds();
        for(int minutes:new int[]{30,15,10,5,1})if(seconds>0&&seconds<=minutes*60L&&seconds>Math.max(0,minutes*60L-3)&&announced.add(next.key+"/"+minutes))for(Player p:Bukkit.getOnlinePlayers())p.sendMessage(plugin.prefix()+plugin.playerText(p,"Geplanter Serverneustart in "+minutes+" Minute(n).","Scheduled server restart in "+minutes+" minute(s)."));
        int countdown=Math.max(10,Math.min(300,plugin.getConfig().getInt(ROOT+"countdown-seconds",60)));
        if(seconds>=0&&seconds<=countdown){
            // Persist before dispatch. A reload/restart cannot re-run the same scheduled occurrence.
            Properties copy=new Properties();copy.putAll(completed);copy.setProperty(next.key,Long.toString(System.currentTimeMillis()));
            LocalDate cutoff=LocalDate.now().minusDays(7);copy.stringPropertyNames().stream().filter(key->{try{return LocalDate.parse(key.split("/")[0]).isBefore(cutoff);}catch(Exception e){return false;}}).toList().forEach(copy::remove);
            AtomicState.save(file,copy,"Consumed restart schedule occurrences");completed.clear();completed.putAll(copy);
            plugin.calendarRestart("Zeitplan "+next.key,(int)Math.max(5,seconds));
        }
    }catch(Exception invalid){plugin.getLogger().warning("Neustart-Zeitplan pausiert: "+invalid.getMessage());plugin.getConfig().set(ROOT+"enabled",false);plugin.saveConfig();}}
    private AdminUpdateMenu.Button b(String title,String detail,Material icon,Runnable action){return new AdminUpdateMenu.Button(title,detail,icon,action);}
    void menu(Player p){if(!plugin.has(p,"simplexadminsuite.operations")||!plugin.adminSessionUsable(p))return;List<AdminUpdateMenu.Button>items=new ArrayList<>();
        items.add(AdminUpdateMenu.info("Neustarts nach Uhrzeit","Serverzone: "+ZoneId.systemDefault()+"\nPlanungszone: "+plugin.getConfig().getString(ROOT+"timezone","Europe/Berlin")+"\nNächster Termin: "+nextLabel()+"\nPaper restart bzw. stop; AMP muss den Prozess neu starten.",Material.CLOCK));
        boolean enabled=plugin.getConfig().getBoolean(ROOT+"enabled",false);items.add(b("Automatische Neustarts",enabled?"An • ausschalten":"Aus • einschalten",enabled?Material.LIME_DYE:Material.GRAY_DYE,()->{if(!allowed(p))return;plugin.getConfig().set(ROOT+"enabled",!enabled);plugin.saveConfig();menu(p);}));
        items.add(b("Uhrzeiten bearbeiten",String.join(", ",plugin.getConfig().getStringList(ROOT+"times")),Material.WRITABLE_BOOK,()->plugin.adminUpdate.prompt(p,"Tägliche Uhrzeiten (HH:mm, mit Komma)",String.join(", ",plugin.getConfig().getStringList(ROOT+"times")),input->{if(!allowed(p))return;try{List<String> values=Arrays.stream(input.split(",")).map(String::strip).filter(v->!v.isEmpty()).toList();times(values);plugin.getConfig().set(ROOT+"times",values);plugin.saveConfig();announced.clear();menu(p);}catch(Exception e){plugin.menuError(p,"§cUngültige Uhrzeit. Beispiel: 04:00, 16:30");menu(p);}})));
        items.add(b("Zeitzone bearbeiten",plugin.getConfig().getString(ROOT+"timezone","Europe/Berlin"),Material.COMPASS,()->plugin.adminUpdate.prompt(p,"IANA-Zeitzone",plugin.getConfig().getString(ROOT+"timezone","Europe/Berlin"),input->{if(!allowed(p))return;try{zone(input);plugin.getConfig().set(ROOT+"timezone",input);plugin.saveConfig();menu(p);}catch(Exception e){plugin.menuError(p,"§cUngültige Zeitzone; z. B. Europe/Berlin");menu(p);}})));
        items.add(b("Laufenden Countdown abbrechen","Abbruch des aktuellen Neustarts",Material.BARRIER,()->{if(allowed(p))plugin.cancelCalendarRestart(p.getName());menu(p);}));
        items.add(b("Zurück","Sicherheitszentrale",Material.OAK_DOOR,()->plugin.securityHub.menu(p)));plugin.adminUpdate.show(p,"Neustart-Zeitplanung",items,0);
    }
    private boolean allowed(Player p){return plugin.has(p,"simplexadminsuite.operations")&&plugin.adminSessionUsable(p);}
}
