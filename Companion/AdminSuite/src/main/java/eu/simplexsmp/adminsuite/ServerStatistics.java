package eu.simplexsmp.adminsuite;

import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Filename-only world census; all disk work and profile aggregation run off the tick thread. */
final class ServerStatistics implements Listener {
    record Profile(String platform,String model,String os,String input,String version,long lastSeen,long firstSeen,
                   long play,long afk,long sessions,long points,int warnings,boolean banned,boolean muted,int devices,String locale) {
        Profile(String platform,String model,String os,String input,String version,long lastSeen,long firstSeen,long play,long afk,long sessions,long points,int warnings,boolean banned,boolean muted,int devices){this(platform,model,os,input,version,lastSeen,firstSeen,play,afk,sessions,points,warnings,banned,muted,devices,"");}
    }
    record Live(String worldId,String worldName,Path playerdata,long days,String phase,String clock,int online,int javaOnline,int bedrockOnline,int max,double tps,double cpu,long ramUsed,long ramMax,int chunks,int entities,long uptime) { }
    record Sample(long at,int online) { }
    private final SimplexAdminSuite plugin;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final java.util.concurrent.ThreadPoolExecutor io=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,"Simplex-Statistics");t.setDaemon(true);return t;});
    private final Map<String,Set<String>> worldPlayers=new HashMap<>();
    private final Deque<Sample> samples=new ArrayDeque<>();
    private final Path file;
    private BukkitTask task;
    private long lastCensus;
    private String censusWorld="";
    private volatile List<Map<String,Object>> payloads=List.of();
    private volatile int worldCount;
    private volatile String censusNote="Playerdata census pending";
    ServerStatistics(SimplexAdminSuite p){plugin=p;file=p.getDataFolder().toPath().resolve("server-statistics.properties");load();}
    void start(){task=Bukkit.getScheduler().runTaskTimer(plugin,this::refresh,40,1200);}
    void stop(){if(task!=null)task.cancel();try{io.execute(()->{try{save();}catch(IOException e){plugin.getLogger().warning("Statistikverlauf nicht gespeichert.");}});}catch(java.util.concurrent.RejectedExecutionException ignored){}io.shutdown();try{io.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    List<Map<String,Object>> payloads(){return payloads;}
    void refresh(){
        if(!busy.compareAndSet(false,true))return;
        Live live=plugin.statisticsLive();List<SimplexAdminSuite.PlayerProfile> profiles=plugin.expansionProfiles();
        Set<String> online=new HashSet<>();for(var p:Bukkit.getOnlinePlayers())if(live.worldId!=null)online.add(p.getUniqueId().toString());
        try { io.execute(()->{try{
            record(live.online);Set<String> known=worldPlayers.computeIfAbsent(live.worldId,k->new HashSet<>());known.addAll(online);
                if(!live.worldId.equals(censusWorld)||System.currentTimeMillis()-lastCensus>=600_000){
                    if(live.playerdata!=null){Set<String> census=census(live.playerdata);known.addAll(census);boolean available=Files.isDirectory(live.playerdata)||Files.isDirectory(live.playerdata.getParent().resolve("players/data"));censusNote=available?"Main Overworld player records + joins recorded for this world":"Main Overworld player records unavailable; recorded joins only";}else censusNote="Main Overworld unavailable";
                    lastCensus=System.currentTimeMillis();censusWorld=live.worldId;
                }worldCount=known.size();
                List<Profile> rows=profiles.stream().map(SimplexAdminSuite.PlayerProfile::statisticsSnapshot).toList();payloads=render(live,rows);save();
            plugin.discordRelay().refreshStatistics();
        }catch(Exception e){plugin.getLogger().warning("Serverstatistik: "+e.getClass().getSimpleName()+": "+e.getMessage());}finally{busy.set(false);}}); } catch(java.util.concurrent.RejectedExecutionException e) { busy.set(false); }
    }
    static Set<String> census(Path legacy)throws IOException{
        Set<String> result=new HashSet<>();for(Path folder:List.of(legacy,legacy.getParent().resolve("players/data")))if(Files.isDirectory(folder))try(var files=Files.list(folder)){files.map(p->p.getFileName().toString()).filter(n->n.endsWith(".dat")||n.endsWith(".dat_old")).forEach(n->{try{result.add(UUID.fromString(n.substring(0,n.indexOf(".dat"))).toString());}catch(IllegalArgumentException ignored){}});}return result;
    }
    private void observe(UUID joined){
        int count=plugin.discordPlayerSnapshots().size();World main=Bukkit.getWorld(plugin.getConfig().getString("scoreboard.main-world","world"));String key=main==null?null:main.getUID().toString();
        try{io.execute(()->{record(count);if(joined!=null&&key!=null)worldPlayers.computeIfAbsent(key,k->new HashSet<>()).add(joined.toString());});}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    @EventHandler(priority=EventPriority.MONITOR)public void join(PlayerJoinEvent e){UUID id=e.getPlayer().getUniqueId();Bukkit.getScheduler().runTask(plugin,()->observe(id));}
    @EventHandler(priority=EventPriority.MONITOR)public void quit(PlayerQuitEvent e){Bukkit.getScheduler().runTask(plugin,()->observe(null));}
    private void record(int online){long now=System.currentTimeMillis();Sample last=samples.peekLast();
        if(last!=null&&last.at/60_000==now/60_000){if(online>last.online){samples.removeLast();samples.add(new Sample(now,online));}}else samples.add(new Sample(now,online));
        while(!samples.isEmpty()&&samples.peekFirst().at<now-7L*86400_000)samples.removeFirst();}
    private List<Map<String,Object>> render(Live l,List<Profile> profiles){
        long now=System.currentTimeMillis(),play=0,afk=0,sessions=0,points=0;int recent=0,new24=0,bans=0,mutes=0,warnings=0,java=0,bedrock=0,unknown=0,devicesKnown=0;
        Map<String,Integer> models=new HashMap<>(),oses=new HashMap<>(),versions=new HashMap<>(),inputs=new HashMap<>(),languages=new HashMap<>();
        for(Profile p:profiles){play+=p.play;afk+=p.afk;sessions+=p.sessions;points+=p.points;warnings+=p.warnings;if(p.banned)bans++;if(p.muted)mutes++;if(p.lastSeen>=now-86400_000)recent++;if(p.firstSeen>=now-86400_000)new24++;
            devicesKnown+=p.devices;
            if(p.platform.equals("JAVA")){java++;count(oses,"Java (OS unknown)");}
            else if(p.platform.equals("BEDROCK")){bedrock++;if(valid(p.model))count(models,label(DeviceNames.display(p.model,"Bedrock",false)));count(oses,normalizeOs(p.os));if(valid(p.input))count(inputs,label(p.input));}else unknown++;
            if(valid(p.locale))count(languages,PlayerLanguages.localeName(p.locale,false));
            if(valid(p.version))count(versions,label(p.version));
        }
        List<Map<String,Object>> fields=new ArrayList<>();
        field(fields,"🌍 World & community","**"+worldCount+"** unique world players\n**"+profiles.size()+"** Admin Suite profiles\n**"+l.days+"** In-game days • "+l.phase+" • "+l.clock+"\nWorld: **"+label(l.worldName)+"**\n"+censusNote,false);
        field(fields,"👥 Live players",l.online+" / "+l.max+" • Java "+l.javaOnline+" • Bedrock "+l.bedrockOnline,true);
        field(fields,"📈 Recorded peaks","24 hours: **"+peak(now-86400_000)+"**\n7 days: **"+peak(now-7L*86400_000)+"**",true);
        field(fields,"🕒 Activity","Seen in 24h: **"+recent+"**\nNew suite profiles in 24h: **"+new24+"**\nSessions: **"+sessions+"**",true);
        field(fields,"⏳ Recorded playtime",hours(play)+" hours total • "+hours(afk)+" hours AFK\n**"+points+"** Simplex Points",false);
        field(fields,"🎮 Profile platforms","Java **"+java+"** • Bedrock **"+bedrock+"** • Unknown **"+unknown+"**\nLinked Discord accounts: **"+plugin.accountLinks.store.all().size()+"** • Saved device records: **"+devicesKnown+"**",false);
        field(fields,"📱 Top 10 device models · Bedrock",top(models),false);
        field(fields,"💻 Top 10 operating systems",top(oses)+"\nJava counted as Windows by server policy.",false);
        field(fields,"🕹️ Top 10 input methods · Bedrock",top(inputs),false);
        field(fields,"🌐 Top 10 client languages",top(languages),false);
        field(fields,"🧩 Top 10 game versions",top(versions),false);
        field(fields,"🛡️ Suite moderation","Active internal bans: **"+bans+"** • Mutes: **"+mutes+"**\nSaved warnings: **"+warnings+"**",false);
        field(fields,"⚙️ Performance",String.format(Locale.ROOT,"**%.2f TPS**\nCPU %s\nRAM %s · %d / %d MiB\nLoaded chunks: %d • Entities: %d\nUptime: %dh %dm",l.tps,bar(l.cpu),bar(l.ramMax>0?(double)l.ramUsed/l.ramMax:-1),l.ramUsed/1048576,l.ramMax/1048576,l.chunks,l.entities,l.uptime/3600000,l.uptime/60000%60),false);
        field(fields,"📊 Last 24h · hourly peaks",sparkline(now)+"\nPeaks collected from minute samples and join/leave events. Gaps are marked ·. Online counts exclude vanished players. History begins with this update.",false);
        field(fields,"ℹ️ How to read these statistics","World players are distinct UUIDs, not people. Deleted playerdata cannot recover old joins. Rankings count one latest known platform/model per suite profile; models exclude Java. Client metadata is self-reported. Playtime/AFK are suite records; outside-plugin history is not reconstructed.",false);
        return pages("✨ Simplex SMP · Server statistics",fields,now);
    }
    private int peak(long since){return samples.stream().filter(s->s.at>=since).mapToInt(Sample::online).max().orElse(0);}
    private String sparkline(long now){String glyphs="▁▂▃▄▅▆▇█";int[] bins=new int[24];Arrays.fill(bins,-1);long start=now-86400_000;for(Sample s:samples)if(s.at>=start){int i=(int)Math.min(23,(s.at-start)/3600000);bins[i]=Math.max(bins[i],s.online);}int max=Arrays.stream(bins).max().orElse(1);StringBuilder b=new StringBuilder("`");for(int n:bins)b.append(n<0?'·':glyphs.charAt(max<=0?0:Math.min(7,n*7/max)));return b.append("`  oldest → now · max ").append(Math.max(0,max)).toString();}
    static String normalizeOs(String raw){String s=raw==null?"":raw.trim().toUpperCase(Locale.ROOT).replace(' ','_');return switch(s){case "1","ANDROID"->"Android";case "2","IOS","I_OS"->"iOS";case "3","OSX","MACOS","MAC_OS"->"macOS";case "4","AMAZON","FIREOS","FIRE_OS"->"Fire OS";case "5","GEAR_VR"->"Gear VR";case "6","HOLOLENS"->"HoloLens";case "7","WINDOWS_10","WIN10","WINDOWS","UWP"->"Windows";case "8","WINDOWS_32","WIN32"->"Windows";case "9","DEDICATED"->"Dedicated";case "10","TVOS","TV_OS"->"tvOS";case "11","PLAYSTATION","PS4"->"PlayStation";case "12","NINTENDO","SWITCH","NX"->"Nintendo Switch";case "13","XBOX"->"Xbox";case "14","WINDOWS_PHONE"->"Windows Phone";case "15","LINUX"->"Linux";default->valid(raw)?label(raw):"Unknown";};}
    private static boolean valid(String s){return s!=null&&!s.isBlank()&&!Set.of("UNKNOWN","UNBEKANNT","N/A","0").contains(s.trim().toUpperCase(Locale.ROOT));}
    static String label(String s){if(s==null||s.isBlank())return "Unknown";String v=s.replaceAll("[\\p{Cntrl}\\n\\r]"," ").replace("@","＠").replace("`", "'").replace("*", "").replace("_"," ").replace("[", "(").replace("]", ")");return v.substring(0,Math.min(64,v.length()));}
    private static void count(Map<String,Integer> m,String k){m.merge(k,1,Integer::sum);}
    static String top(Map<String,Integer> counts){if(counts.isEmpty())return "_No reported data yet_";int total=counts.values().stream().mapToInt(Integer::intValue).sum();StringBuilder out=new StringBuilder();List<Map.Entry<String,Integer>> sorted=counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(10).toList();int n=0;for(var e:sorted)out.append(++n).append(". **").append(label(e.getKey())).append("** · ").append(e.getValue()).append(String.format(Locale.ROOT," (%.1f%%)\n",100D*e.getValue()/Math.max(1,total)));return out.toString();}
    static String bar(double fraction){if(!Double.isFinite(fraction)||fraction<0)return "n/a";double value=Math.min(1,fraction);int full=(int)Math.round(value*8);return "`"+"▰".repeat(full)+"▱".repeat(8-full)+"` "+Math.round(value*100)+"%";}
    private static String hours(long ms){return String.format(Locale.ROOT,"%.1f",ms/3600000D);}
    static void field(List<Map<String,Object>> fields,String name,String value,boolean inline){fields.add(DiscordBotManager.mapOf("name",name.substring(0,Math.min(256,name.length())),"value",value.substring(0,Math.min(1024,value.length())),"inline",inline));}
    static List<Map<String,Object>> pages(String title,List<Map<String,Object>> fields,long time){
        List<Map<String,Object>> messages=new ArrayList<>();List<Map<String,Object>> current=new ArrayList<>();int size=title.length()+150;
        for(var f:fields){int length=String.valueOf(f.get("name")).length()+String.valueOf(f.get("value")).length();if(current.size()>=25||size+length>5700){messages.add(page(title,current,time,messages.size()+1));current=new ArrayList<>();size=title.length()+150;}current.add(f);size+=length;}
        if(!current.isEmpty())messages.add(page(title,current,time,messages.size()+1));return List.copyOf(messages);
    }
    private static Map<String,Object> page(String title,List<Map<String,Object>> fields,long time,int page){return DiscordBotManager.mapOf("content","","embeds",List.of(DiscordBotManager.mapOf("title",title,"color",0x8B5CF6,"fields",List.copyOf(fields),"footer",DiscordBotManager.mapOf("text","Live statistics · refreshed every minute · page "+page),"timestamp",java.time.Instant.ofEpochMilli(time).toString())),"allowed_mentions",DiscordBotManager.mapOf("parse",List.of()));}
    private void load(){if(!Files.exists(file))return;try{Properties p=new Properties();try(Reader in=Files.newBufferedReader(file)){p.load(in);}for(String key:p.stringPropertyNames())if(key.startsWith("world.")){Set<String> ids=new HashSet<>();for(String id:p.getProperty(key).split(","))try{ids.add(UUID.fromString(id).toString());}catch(Exception ignored){}worldPlayers.put(key.substring(6),ids);}for(String row:p.getProperty("samples","").split(";")){String[] c=row.split(":");if(c.length==2)samples.add(new Sample(Long.parseLong(c[0]),Integer.parseInt(c[1])));}while(samples.size()>10081)samples.removeFirst();}catch(Exception e){plugin.getLogger().warning("Statistikverlauf konnte nicht geladen werden: "+e.getClass().getSimpleName());}}
    private void save()throws IOException{Properties p=new Properties();for(var e:worldPlayers.entrySet())p.setProperty("world."+e.getKey(),String.join(",",e.getValue()));StringJoiner j=new StringJoiner(";");for(Sample s:samples)j.add(s.at+":"+s.online);p.setProperty("samples",j.toString());AtomicState.save(file,p,"Simplex world census and 7-day minute peaks");}
}
