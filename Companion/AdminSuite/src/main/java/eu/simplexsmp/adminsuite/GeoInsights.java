package eu.simplexsmp.adminsuite;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Opt-in, coarse IP diagnostics; never influences Alt scores or automatic punishments. */
final class GeoInsights implements AutoCloseable {
    record Result(long at,String country,String region,String city,String postal,String isp,String timezone){ }
    private final Path file;private final Consumer<String> error;private final BooleanSupplier enabled;
    private final Map<String,Result> cache=new ConcurrentHashMap<>();private final Set<String> queued=ConcurrentHashMap.newKeySet();
    private final Deque<Map.Entry<String,String>> queue=new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Simplex-geo-diagnostics");t.setDaemon(true);return t;});
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final java.util.concurrent.atomic.AtomicInteger queuedCount=new java.util.concurrent.atomic.AtomicInteger();
    private long quotaStarted;private int quotaUsed;private long blockedUntil;
    GeoInsights(Path file,BooleanSupplier enabled,Consumer<String> error)throws IOException{this.file=file;this.enabled=enabled;this.error=error;Properties p=new Properties();if(Files.exists(file))try(Reader in=Files.newBufferedReader(file)){p.load(in);}quotaStarted=Long.parseLong(p.getProperty("quota.started","0"));quotaUsed=Integer.parseInt(p.getProperty("quota.used","0"));blockedUntil=Long.parseLong(p.getProperty("quota.blocked","0"));for(String key:p.stringPropertyNames())if(key.startsWith("cache."))try{Object o=DiscordBotManager.Json.parse(p.getProperty(key));if(o instanceof Map<?,?> m)cache.put(key.substring(6),new Result(((Number)m.get("at")).longValue(),text(m,"country"),text(m,"region"),text(m,"city"),text(m,"postal"),text(m,"isp"),text(m,"timezone")));}catch(RuntimeException invalid){throw new IOException("Invalid saved geolocation diagnostic",invalid);}worker.scheduleWithFixedDelay(this::process,2,2,TimeUnit.SECONDS);}
    static String publicIp(String raw){if(raw==null)return null;String value=raw.strip();if(!value.matches("[0-9.]+")&&!value.matches("[0-9a-fA-F:]+"))return null;if(!value.contains(":")){String[] parts=value.split("\\.");if(parts.length!=4)return null;for(String part:parts)try{if(part.isEmpty()||Integer.parseInt(part)>255)return null;}catch(NumberFormatException e){return null;}}
        try{InetAddress address=InetAddress.getByName(value);if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return null;byte[] bytes=address.getAddress();if(bytes.length==16&&(bytes[0]&0xfe)==0xfc)return null;if(bytes.length==4&&(bytes[0]&255)==100&&((bytes[1]&255)>=64&&(bytes[1]&255)<=127))return null;return address.getHostAddress();}catch(UnknownHostException invalid){return null;}}
    boolean enqueue(String key,String ip){if(!enabled.getAsBoolean()||queuedCount.get()>=4096)return false;String valid=publicIp(ip);if(valid==null)return false;Result known=cache.get(key);if(known!=null&&System.currentTimeMillis()-known.at<7L*86400000)return false;if(!queued.add(key))return false;queuedCount.incrementAndGet();queue.addLast(Map.entry(key,valid));return true;}
    Result get(String key){return cache.get(key);}
    int pending(){return queuedCount.get();}
    private void process(){if(!enabled.getAsBoolean()||System.currentTimeMillis()<blockedUntil)return;Map.Entry<String,String> entry=queue.peekFirst();if(entry==null)return;long now=System.currentTimeMillis();if(now-quotaStarted>=86400000){quotaStarted=now;quotaUsed=0;}if(quotaUsed>=900)return;
        try{
            // Persist budget before the request, including failure/429; restarts cannot reset it.
            quotaUsed++;save();
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://ipwho.is/"+entry.getValue()+"?fields=success,country_code,region,city,postal,connection,timezone")).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==429){long retry=3600;try{retry=Math.max(60,Math.min(86400,Long.parseLong(response.headers().firstValue("Retry-After").orElse("3600"))));}catch(NumberFormatException ignored){}blockedUntil=now+retry*1000;save();return;}
            if(response.statusCode()!=200){blockedUntil=now+60000;save();return;}
            Object parsed=DiscordBotManager.Json.parse(response.body());if(!(parsed instanceof Map<?,?> m)||!Boolean.TRUE.equals(m.get("success"))){queue.removeFirst();queued.remove(entry.getKey());queuedCount.decrementAndGet();return;}
            String isp=m.get("connection") instanceof Map<?,?> c?text(c,"isp"):"";String zone=m.get("timezone") instanceof Map<?,?> z?text(z,"id"):"";
            cache.put(entry.getKey(),new Result(now,text(m,"country_code"),text(m,"region"),text(m,"city"),text(m,"postal"),isp,zone));queue.removeFirst();queued.remove(entry.getKey());queuedCount.decrementAndGet();save();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception e){blockedUntil=now+60000;error.accept("Geolocation paused after "+e.getClass().getSimpleName());}
    }
    private static String text(Map<?,?> map,String key){return SecuritySignals.safe(Objects.toString(map.get(key),""));}
    private void save()throws IOException{Properties p=new Properties();p.setProperty("quota.started",Long.toString(quotaStarted));p.setProperty("quota.used",Integer.toString(quotaUsed));p.setProperty("quota.blocked",Long.toString(blockedUntil));cache.forEach((key,value)->p.setProperty("cache."+key,DiscordBotManager.Json.stringify(Map.of("at",value.at,"country",value.country,"region",value.region,"city",value.city,"postal",value.postal,"isp",value.isp,"timezone",value.timezone))));AtomicState.save(file,p,"Coarse geo diagnostics keyed by server-secret IP pseudonyms; never store raw IPs");}
    @Override public void close(){worker.shutdownNow();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))error.accept("Geolocation worker still stopping");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
