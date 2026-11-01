package eu.simplexsmp.adminsuite;
import java.util.*;
import org.bukkit.Bukkit;
final class MemoryRestartGuard {
    private final SimplexAdminSuite plugin;private final ServerMemory.Window window=new ServerMemory.Window();private org.bukkit.scheduler.BukkitTask task;
    private volatile ServerMemory.Sample sample=new ServerMemory.Sample(-1,-1,"unavailable");private long lastRestart;private String status="";
    MemoryRestartGuard(SimplexAdminSuite p){plugin=p;}
    void start(){task=Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,()->{ServerMemory.Sample s=ServerMemory.read();plugin.runAdminTask(()->tick(s));},100,200);}
    void stop(){if(task!=null)task.cancel();}
    private void tick(ServerMemory.Sample s){sample=s;if(!plugin.getConfig().getBoolean("memory-restart.enabled",false)){window.reset();return;}
        long threshold=plugin.getConfig().getLong("memory-restart.threshold-bytes",18_100_000_000L),duration=plugin.getConfig().getLong("memory-restart.hold-seconds",300)*1000L;
        if(!s.available()){window.reset();status="Kein begrenzter Server-Container erkannt; Neustart deaktiviert / No bounded server container";return;}
        if(threshold<=0||threshold>s.limit()){window.reset();status="Schwellwert liegt außerhalb des Containerlimits";return;}
        long now=System.currentTimeMillis();if(window.sample(now,s,threshold,duration)&&now-lastRestart>=900_000){lastRestart=now;window.reset();plugin.appRestart(SimplexAdminSuite.appMap("operation","schedule","seconds",plugin.getConfig().getInt("memory-restart.countdown-seconds",60),"reasonDE","Server-RAM seit längerer Zeit über dem Grenzwert","reasonEN","Server memory has remained above the configured threshold"),"RAM-Notfallneustart");status="Countdown gestartet";}
    }
    Object data(){Runtime rt=Runtime.getRuntime();return SimplexAdminSuite.appMap("available",sample.available(),"used",sample.used(),"limit",sample.limit(),"source",sample.source(),"heapUsed",rt.totalMemory()-rt.freeMemory(),"enabled",plugin.getConfig().getBoolean("memory-restart.enabled",false),"thresholdBytes",plugin.getConfig().getLong("memory-restart.threshold-bytes",18_100_000_000L),"holdSeconds",plugin.getConfig().getInt("memory-restart.hold-seconds",300),"countdownSeconds",plugin.getConfig().getInt("memory-restart.countdown-seconds",60),"status",status);}
    Object configure(Map<String,Object> v){boolean enabled=Boolean.parseBoolean(String.valueOf(v.getOrDefault("enabled","false")));long threshold=Long.parseLong(String.valueOf(v.get("thresholdBytes")));int hold=Integer.parseInt(String.valueOf(v.get("holdSeconds"))),count=Integer.parseInt(String.valueOf(v.get("countdownSeconds")));if(threshold<100_000_000||hold<30||hold>86400||count<5||count>60)throw new IllegalArgumentException("RAM ≥ 0,1 GB; Haltedauer 30–86400 s; Countdown 5–60 s");if(enabled&&(!sample.available()||threshold>sample.limit()))throw new IllegalArgumentException("Kein gültiger Container-RAM-Wert oder Grenzwert größer als Containerlimit");plugin.getConfig().set("memory-restart.enabled",enabled);plugin.getConfig().set("memory-restart.threshold-bytes",threshold);plugin.getConfig().set("memory-restart.hold-seconds",hold);plugin.getConfig().set("memory-restart.countdown-seconds",count);plugin.saveConfig();window.reset();return SimplexAdminSuite.appMap("ok",true,"title","RAM-Notfallneustart gespeichert","description","Container-RAM in Bytes; kein Java-Heap. Host/Panel-Autostart muss eingerichtet sein.");}
}
