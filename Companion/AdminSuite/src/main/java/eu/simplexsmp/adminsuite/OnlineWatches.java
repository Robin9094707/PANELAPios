package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Durable subscriptions; the three-day consent clock starts on the first delivered join. */
final class OnlineWatches {
    record Watch(String id,String discord,UUID verified,String username,UUID target,String player,String channel,String locale,String mode,long asked,boolean accepted) { }
    static final long CONSENT_MS=3L*86_400_000;
    private final Path file;private final Properties state=new Properties();
    OnlineWatches(Path file)throws IOException{this.file=file;if(Files.exists(file))try(Reader r=Files.newBufferedReader(file)){state.load(r);}}
    synchronized boolean blocked(UUID target){return Boolean.parseBoolean(state.getProperty("off."+target,"false"));}
    synchronized List<Watch> all(){List<Watch> out=new ArrayList<>();for(String k:state.stringPropertyNames())if(k.startsWith("w.")){String[] r=state.getProperty(k).split("\\|",11);if(r.length!=11)throw new IllegalStateException("Invalid online watch; file preserved");out.add(new Watch(k.substring(2),r[0],UUID.fromString(r[1]),decode(r[2]),UUID.fromString(r[3]),decode(r[4]),r[5],r[6],r[7],Long.parseLong(r[8]),Boolean.parseBoolean(r[9])));}return out;}
    synchronized Watch add(String discord,UUID verified,String username,UUID target,String player,String channel,String locale,String mode)throws IOException{
        if(blocked(target))throw new IllegalArgumentException("This player disabled join notifications. / Dieser Spieler hat Beitrittshinweise deaktiviert.");
        if(!Set.of("once","permanent").contains(mode)||!discord.matches("[0-9]{5,25}")||!channel.matches("[0-9]{5,25}"))throw new IllegalArgumentException("Invalid request");
        if(all().stream().filter(w->w.discord.equals(discord)).count()>=10)throw new IllegalArgumentException("Maximum 10 active notifications. / Maximal 10 aktive Hinweise.");
        if(all().stream().anyMatch(w->w.discord.equals(discord)&&w.target.equals(target)))throw new IllegalArgumentException("Already subscribed; remove it first. / Bereits vorgemerkt; zuerst entfernen.");
        Watch w=new Watch(UUID.randomUUID().toString().substring(0,8),discord,verified,username,target,player,channel,locale.startsWith("de")?"de":"en",mode,0,false);put(w);return w;
    }
    private static String encode(String s){return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String decode(String s){return new String(Base64.getDecoder().decode(s),java.nio.charset.StandardCharsets.UTF_8);}
    private void put(Watch w)throws IOException{Properties next=copy();next.setProperty("w."+w.id,w.discord+"|"+w.verified+"|"+encode(w.username)+"|"+w.target+"|"+encode(w.player)+"|"+w.channel+"|"+w.locale+"|"+w.mode+"|"+w.asked+"|"+w.accepted+"|1");save(next);}
    synchronized Watch get(String id){return all().stream().filter(w->w.id.equals(id)).findFirst().orElse(null);}
    synchronized void remove(String id)throws IOException{Properties next=copy();next.remove("w."+id);save(next);}
    synchronized void delivered(String id,long now)throws IOException{Watch w=get(id);if(w==null)return;if(w.mode.equals("once")){remove(id);return;}if(w.asked==0)put(new Watch(w.id,w.discord,w.verified,w.username,w.target,w.player,w.channel,w.locale,w.mode,now,false));}
    synchronized void decide(UUID player,String id,boolean yes)throws IOException{Watch w=get(id);if(w==null||!w.target.equals(player)||w.asked==0||w.accepted)throw new IllegalArgumentException("Request unavailable / Anfrage nicht verfügbar");if(!yes){remove(id);return;}put(new Watch(w.id,w.discord,w.verified,w.username,w.target,w.player,w.channel,w.locale,w.mode,w.asked,true));}
    synchronized List<Watch> expire(long now)throws IOException{List<Watch> accepted=new ArrayList<>();for(Watch w:all())if(!w.accepted&&w.asked>0&&now-w.asked>=CONSENT_MS&&!blocked(w.target)){decide(w.target,w.id,true);Properties next=copy();next.setProperty("notice."+w.target+"."+w.id,w.username);save(next);accepted.add(w);}return accepted;}
    synchronized List<String> notices(UUID id)throws IOException{Properties next=copy();List<String> names=new ArrayList<>();for(String k:state.stringPropertyNames())if(k.startsWith("notice."+id+".")){names.add(state.getProperty(k));next.remove(k);}if(!names.isEmpty())save(next);return names;}
    synchronized void block(UUID id,boolean off)throws IOException{Properties next=copy();next.setProperty("off."+id,Boolean.toString(off));if(off){for(Watch w:all())if(w.target.equals(id))next.remove("w."+w.id);for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("notice."+id+"."))next.remove(k);}save(next);}
    private Properties copy(){Properties p=new Properties();p.putAll(state);return p;}
    private void save(Properties next)throws IOException{AtomicState.save(file,next,"Simplex online notifications v1");state.clear();state.putAll(next);}
}
