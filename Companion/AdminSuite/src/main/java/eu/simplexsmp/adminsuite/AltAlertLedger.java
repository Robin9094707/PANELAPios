package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Persistent per-group membership alerts. An anchor is a reference account, not proven ownership. */
final class AltAlertLedger {
    record Alert(UUID anchor,UUID joining,Set<UUID> known){Alert{known=Set.copyOf(known);}}
    private final Path file;private final Map<UUID,Set<UUID>> groups=new LinkedHashMap<>();boolean seeded;
    AltAlertLedger(Path file)throws IOException{this.file=file;Properties p=new Properties();if(Files.exists(file))try(Reader in=Files.newBufferedReader(file)){p.load(in);}seeded=Boolean.parseBoolean(p.getProperty("seeded","false"));for(String key:p.stringPropertyNames())if(key.startsWith("group.")){Set<UUID> ids=new HashSet<>();for(String id:p.getProperty(key).split(","))ids.add(UUID.fromString(id));groups.put(UUID.fromString(key.substring(6)),ids);}}
    synchronized void seed(Map<UUID,Set<UUID>> baseline)throws IOException{if(seeded)return;Map<UUID,Set<UUID>> next=copy();baseline.forEach((root,members)->{if(members.size()>1)next.put(root,new HashSet<>(members));});save(next,true);groups.clear();groups.putAll(next);seeded=true;}
    synchronized Alert observe(UUID joining,UUID suggestedAnchor,Set<UUID> members)throws IOException{
        if(members.size()<2||!members.contains(joining))return null;
        Map<UUID,Set<UUID>> next=copy();Set<UUID> known=new HashSet<>();UUID anchor=suggestedAnchor;boolean existing=false;
        for(var entry:new ArrayList<>(next.entrySet()))if(!Collections.disjoint(entry.getValue(),members)){known.addAll(entry.getValue());if(!existing){anchor=entry.getKey();existing=true;}next.remove(entry.getKey());}
        boolean fresh=!known.contains(joining);Set<UUID> union=new HashSet<>(known);union.add(joining);union.add(anchor);next.put(anchor,union);
        if(!fresh){save(next,seeded);groups.clear();groups.putAll(next);return null;}known.addAll(members);known.remove(joining);return new Alert(anchor,joining,known);
    }
    synchronized void acknowledged(Alert alert)throws IOException{
        Map<UUID,Set<UUID>> next=copy();Set<UUID> union=new HashSet<>();for(var e:new ArrayList<>(next.entrySet()))if(e.getKey().equals(alert.anchor)||e.getValue().contains(alert.joining)||!Collections.disjoint(e.getValue(),alert.known)){union.addAll(e.getValue());next.remove(e.getKey());}union.add(alert.anchor);union.add(alert.joining);next.put(alert.anchor,union);save(next,seeded);groups.clear();groups.putAll(next);
    }
    private Map<UUID,Set<UUID>> copy(){Map<UUID,Set<UUID>> result=new LinkedHashMap<>();groups.forEach((id,members)->result.put(id,new HashSet<>(members)));return result;}
    private void save(Map<UUID,Set<UUID>> values,boolean seeded)throws IOException{Properties p=new Properties();p.setProperty("seeded",Boolean.toString(seeded));values.forEach((root,members)->p.setProperty("group."+root,String.join(",",members.stream().map(UUID::toString).sorted().toList())));AtomicState.save(file,p,"Previously announced strong account groups; no punishment state");}
}
