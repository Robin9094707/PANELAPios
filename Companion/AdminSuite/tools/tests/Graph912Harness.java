package eu.simplexsmp.adminsuite;
import java.util.*;
public final class Graph912Harness {
 public static void main(String[] args){
  Random random=new Random(912);int checks=0;
  for(int round=0;round<60;round++){
   List<UUID> ids=new ArrayList<>();for(int n=0;n<16;n++)ids.add(new UUID(0,n+1));List<AltMatchCacheEngine.Profile> profiles=new ArrayList<>();
   for(UUID id:ids){Map<String,Long> ips=new HashMap<>(),devices=new HashMap<>();Set<String> ignored=new HashSet<>(),manual=new HashSet<>();for(UUID other:ids){if(random.nextInt(8)==0)ignored.add(other.toString());if(random.nextInt(30)==0)manual.add(other.toString());}if(random.nextBoolean())ips.put("ip"+random.nextInt(4),1L);if(random.nextBoolean())devices.put("device"+random.nextInt(5),1L);profiles.add(new AltMatchCacheEngine.Profile(id,"p",1,ips,devices,Map.of(),Map.of(),manual,ignored));}
   var old=AltMatchCacheEngine.build(profiles,1,100,70,1);var fast=AltRepairGraph.build(profiles,1);
   for(UUID id:ids){if(!new HashSet<>(old.componentMembers.get(id)).equals(new HashSet<>(fast.componentMembers.get(id))))throw new AssertionError("forensic connectivity mismatch");for(UUID other:ids){if(old.sameStrongComponent(id,other)!=fast.sameStrongComponent(id,other))throw new AssertionError("strong connectivity mismatch");checks++;}}
  }
  List<AltMatchCacheEngine.Profile> shared=new ArrayList<>();for(int n=0;n<3000;n++)shared.add(new AltMatchCacheEngine.Profile(new UUID(1,n),"p",1,Map.of("proxy",1L),Map.of(),Map.of(),Map.of(),Set.of(),Set.of()));var result=AltRepairGraph.build(shared,1);if(result.counts.get(shared.get(0).uuid)!=2999)throw new AssertionError("large proxy group");
  System.out.println("Graph912Harness: "+checks+" equivalence checks and 3000-account proxy repair passed");
 }
}
