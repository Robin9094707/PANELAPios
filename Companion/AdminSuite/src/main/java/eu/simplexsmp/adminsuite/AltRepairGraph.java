package eu.simplexsmp.adminsuite;

import java.util.*;

/** Connectivity-only repair view: do not allocate all pairwise matches for a shared proxy IP. */
final class AltRepairGraph {
    static AltMatchCacheEngine.Result build(List<AltMatchCacheEngine.Profile> profiles,long now){
        Map<UUID,List<UUID>> groups=components(profiles,true),strong=components(profiles,false);
        Map<UUID,Integer> counts=new HashMap<>();Map<UUID,UUID> roots=new HashMap<>();List<UUID> overview=new ArrayList<>();
        for(var p:profiles){int count=groups.get(p.uuid).size()-1;counts.put(p.uuid,count);if(count>0)overview.add(p.uuid);roots.put(p.uuid,strong.get(p.uuid).get(0));}
        return new AltMatchCacheEngine.Result(Map.of(),Map.copyOf(counts),Map.copyOf(groups),Map.copyOf(roots),List.copyOf(overview),profiles.size(),0,0,-1,0,now);
    }
    private static Map<UUID,List<UUID>> components(List<AltMatchCacheEngine.Profile> profiles,boolean ips){
        Map<UUID,AltMatchCacheEngine.Profile> byId=new HashMap<>();Map<UUID,UUID> parents=new HashMap<>();Map<String,Set<UUID>> buckets=new HashMap<>();
        for(var p:profiles){byId.put(p.uuid,p);parents.put(p.uuid,p.uuid);}
        for(var p:profiles){add(buckets,"DEV",p.deviceHistory,p.uuid,false);add(buckets,"SELF",p.selfSignedHistory,p.uuid,false);add(buckets,"XUID",p.xuidHistory,p.uuid,false);if(ips)add(buckets,"IP",p.ipHistory,p.uuid,true);
            Set<String> links=new HashSet<>(p.manualLinks);links.addAll(p.verifiedLinks);for(String raw:links)try{UUID id=UUID.fromString(raw);if(byId.containsKey(id)&&allowed(p,byId.get(id)))union(parents,p.uuid,id);}catch(IllegalArgumentException ignored){}
        }
        // BFS inside each shared-signal bucket: one scan for an ordinary unignored proxy bucket.
        // Explicit ignored pairs are respected, including buckets split by those exclusions.
        for(Set<UUID> bucket:buckets.values()){
            Set<UUID> remaining=new HashSet<>(bucket);
            while(!remaining.isEmpty()){
                UUID seed=remaining.iterator().next();remaining.remove(seed);Deque<UUID> queue=new ArrayDeque<>();queue.add(seed);
                while(!queue.isEmpty()){
                    UUID current=queue.removeFirst();for(var it=remaining.iterator();it.hasNext();){UUID other=it.next();if(!allowed(byId.get(current),byId.get(other)))continue;union(parents,current,other);it.remove();queue.addLast(other);}
                }
            }
        }
        Map<UUID,List<UUID>> collected=new HashMap<>();for(var p:profiles)collected.computeIfAbsent(root(parents,p.uuid),k->new ArrayList<>()).add(p.uuid);
        Map<UUID,List<UUID>> result=new HashMap<>();for(var group:collected.values()){List<UUID> stable=group.stream().sorted().toList();for(UUID id:group)result.put(id,stable);}return result;
    }
    private static boolean allowed(AltMatchCacheEngine.Profile a,AltMatchCacheEngine.Profile b){return !a.ignoredLinks.contains(b.uuid.toString())&&!b.ignoredLinks.contains(a.uuid.toString());}
    private static void add(Map<String,Set<UUID>> buckets,String type,Map<String,Long> values,UUID id,boolean positiveTime){for(var entry:values.entrySet())if(!positiveTime||entry.getValue()>0)buckets.computeIfAbsent(type+"|"+entry.getKey(),k->new HashSet<>()).add(id);}
    private static UUID root(Map<UUID,UUID> p,UUID id){UUID first=id;while(!p.get(id).equals(id))id=p.get(id);while(!first.equals(id)){UUID next=p.get(first);p.put(first,id);first=next;}return id;}
    private static void union(Map<UUID,UUID> p,UUID a,UUID b){UUID x=root(p,a),y=root(p,b);if(!x.equals(y))p.put(y,x);}
}
