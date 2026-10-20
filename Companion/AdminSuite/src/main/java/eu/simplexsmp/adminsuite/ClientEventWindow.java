package eu.simplexsmp.adminsuite;

import java.util.*;
import java.io.*;
import java.nio.file.*;

/** First event now, trailing aggregate at the end of a bounded per-account window. */
final class ClientEventWindow {
    record Event(UUID player,String name,String title,String detail,String dossier,long at){ }
    record Delivery(Event latest,int count,Map<String,Integer> kinds){Delivery{kinds=Map.copyOf(kinds);}}
    private static final class Window {
        long due;Event latest;int pending;final Map<String,Integer> kinds=new LinkedHashMap<>();
        Window(long due){this.due=due;}
    }
    private final Map<UUID,Window> windows=new LinkedHashMap<>();
    private final long interval;private boolean dirty;
    ClientEventWindow(long interval){this.interval=Math.max(10000,interval);}
    Delivery offer(Event event){
        dirty=true;if(event.dossier.length()>200000)return null;
        Window window=windows.get(event.player);
        if(window==null){if(windows.size()>=1024)return null;windows.put(event.player,new Window(event.at+interval));return new Delivery(event,1,Map.of(event.title,1));}
        if(event.at>=window.due&&window.pending==0){window.due=event.at+interval;return new Delivery(event,1,Map.of(event.title,1));}
        window.latest=event;window.pending=Math.min(1000000,window.pending+1);
        if(window.kinds.size()<32||window.kinds.containsKey(event.title))window.kinds.merge(event.title,1,(a,b)->Math.min(1000000,a+b));
        return null;
    }
    List<Delivery> flush(long now){List<Delivery> deliveries=new ArrayList<>();Iterator<Map.Entry<UUID,Window>> entries=windows.entrySet().iterator();while(entries.hasNext()){
        Window window=entries.next().getValue();if(now<window.due)continue;dirty=true;
        if(window.pending>0){deliveries.add(new Delivery(window.latest,window.pending,window.kinds));window.pending=0;window.kinds.clear();window.latest=null;window.due=now+interval;}
        else entries.remove();
    }return List.copyOf(deliveries);}
    void requeue(Delivery delivery,long now){Window w=windows.computeIfAbsent(delivery.latest.player,k->new Window(now+60000));w.latest=delivery.latest;w.pending=Math.min(1000000,w.pending+delivery.count);delivery.kinds.forEach((k,n)->w.kinds.merge(k,n,(a,b)->Math.min(1000000,a+b)));w.due=Math.min(w.due,now+60000);dirty=true;}
    void checkpoint(Path file)throws IOException{if(!dirty)return;Properties p=new Properties();for(var entry:windows.entrySet()){Window w=entry.getValue();Map<String,Object> row=new LinkedHashMap<>();row.put("due",w.due);row.put("pending",w.pending);row.put("kinds",w.kinds);if(w.latest!=null){Event e=w.latest;row.put("latest",Map.of("name",e.name,"title",e.title,"detail",e.detail,"dossier",e.dossier,"at",e.at));}p.setProperty(entry.getKey().toString(),DiscordBotManager.Json.stringify(row));}AtomicState.save(file,p,"Discord per-account aggregation windows and latest pending dossier");dirty=false;}
    void restore(Path file)throws IOException{if(!Files.exists(file))return;Properties p=new Properties();try(Reader in=Files.newBufferedReader(file)){p.load(in);}try{for(String key:p.stringPropertyNames()){if(windows.size()>=1024)throw new IOException("Too many saved log windows");UUID id=UUID.fromString(key);Map<?,?> row=(Map<?,?>)DiscordBotManager.Json.parse(p.getProperty(key));Window w=new Window(((Number)row.get("due")).longValue());w.pending=((Number)row.get("pending")).intValue();if(w.pending<0||w.pending>1000000)throw new IOException("Invalid log count");if(row.get("kinds") instanceof Map<?,?> kinds)kinds.forEach((k,v)->w.kinds.put(k.toString(),((Number)v).intValue()));if(row.get("latest") instanceof Map<?,?> e){w.latest=new Event(id,e.get("name").toString(),e.get("title").toString(),e.get("detail").toString(),e.get("dossier").toString(),((Number)e.get("at")).longValue());if(w.latest.dossier.length()>200000)throw new IOException("Invalid saved dossier size");}if(w.pending>0&&w.latest==null)throw new IOException("Missing pending event");windows.put(id,w);}}catch(RuntimeException invalid){throw new IOException("Invalid saved log window; original file preserved",invalid);}}
    int tracked(){return windows.size();}
}
