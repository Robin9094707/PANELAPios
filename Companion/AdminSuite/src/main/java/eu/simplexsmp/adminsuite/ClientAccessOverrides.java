package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reversible exemptions; never deletes a ban, identity or existing profile. */
final class ClientAccessOverrides {
    private final Path file;
    private final Properties persisted=new Properties();
    private final Map<UUID,Set<String>> whitelists=new ConcurrentHashMap<>();
    private final Map<UUID,Long> emergencies=new ConcurrentHashMap<>();
    private final Map<UUID,UUID> verifiedLinks=new ConcurrentHashMap<>();
    ClientAccessOverrides(Path file)throws IOException{
        this.file=file;if(Files.exists(file))try(Reader in=Files.newBufferedReader(file)){persisted.load(in);}
        for(String key:persisted.stringPropertyNames())try{
            if(key.startsWith("whitelist.")){UUID id=UUID.fromString(key.substring(10));Set<String> values=new HashSet<>();for(String v:persisted.getProperty(key).split(","))if(valid(v))values.add(v);whitelists.put(id,Set.copyOf(values));}
            if(key.startsWith("emergency."))emergencies.put(UUID.fromString(key.substring(10)),Long.parseLong(persisted.getProperty(key)));
            if(key.startsWith("verified-link."))verifiedLinks.put(UUID.fromString(key.substring(14)),UUID.fromString(persisted.getProperty(key)));
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid access override record: "+key,invalid);}
    }
    static boolean valid(String key){return key!=null&&key.matches("[a-z0-9_-]{1,64}");}
    boolean exempt(UUID id,String check){Set<String> values=whitelists.getOrDefault(id,Set.of());return values.contains("all")||values.contains(check);}
    Set<String> whitelist(UUID id){return whitelists.getOrDefault(id,Set.of());}
    boolean emergency(UUID id,long now){return id!=null&&emergencies.getOrDefault(id,0L)>now;}
    long emergencyUntil(UUID id){return emergencies.getOrDefault(id,0L);}
    synchronized void whitelist(UUID id,String check,boolean enabled)throws IOException{
        if(!valid(check))throw new IllegalArgumentException("Ungültige Prüf-ID");
        Set<String> next=new TreeSet<>(whitelist(id));if(enabled)next.add(check);else next.remove(check);
        write("whitelist."+id,next.isEmpty()?null:String.join(",",next));whitelists.put(id,Set.copyOf(next));
    }
    synchronized void emergency(UUID id,boolean enabled,long now)throws IOException{
        long until=enabled?Math.addExact(now,30L*60*1000):0;write("emergency."+id,enabled?Long.toString(until):null);
        if(enabled)emergencies.put(id,until);else emergencies.remove(id);
    }
    synchronized boolean verifiedLink(UUID bedrock,UUID java)throws IOException{
        if(bedrock.equals(java)||java.equals(verifiedLinks.get(bedrock)))return false;
        write("verified-link."+bedrock,java.toString());verifiedLinks.put(bedrock,java);return true;
    }
    Set<String> verifiedLinks(UUID id){Set<String> values=new HashSet<>();UUID java=verifiedLinks.get(id);if(java!=null)values.add(java.toString());verifiedLinks.forEach((bedrock,target)->{if(target.equals(id))values.add(bedrock.toString());});return Set.copyOf(values);}
    private void write(String key,String value)throws IOException{
        Properties copy=new Properties();copy.putAll(persisted);if(value==null)copy.remove(key);else copy.setProperty(key,value);
        AtomicState.save(file,copy,"AdminSuite client exemptions and reversible 30-minute emergency access");persisted.clear();persisted.putAll(copy);
    }
}
