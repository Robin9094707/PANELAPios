package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;

/** One-to-one links. Commit durable state before publishing it. Codes never go to disk. */
final class AccountLinks {
    record Link(UUID minecraft, String discord, String name, long linkedAt) { }
    private record Challenge(String discord, long expiry) { }
    private final Path file;
    private volatile Map<UUID,Link> links = Map.of();
    private final Map<String,Challenge> codes = new HashMap<>();
    private final Map<UUID,Deque<Long>> attempts = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    AccountLinks(Path file) throws IOException {
        this.file=file;
        if (!Files.exists(file)) return;
        Properties p=new Properties();try(Reader in=Files.newBufferedReader(file)){p.load(in);}
        Set<String> used=new HashSet<>();Map<UUID,Link> loaded=new LinkedHashMap<>();
        for(String key:p.stringPropertyNames()) if(key.startsWith("link.")) {
            try { UUID id=UUID.fromString(key.substring(5));String[] row=p.getProperty(key).split("\\|",4);
                if(row.length!=4||!row[0].matches("[0-9]{5,25}")||!used.add(row[0]))throw new IllegalArgumentException();
                loaded.put(id,new Link(id,row[0],new String(Base64.getDecoder().decode(row[1]),java.nio.charset.StandardCharsets.UTF_8),Long.parseLong(row[2])));
            } catch(RuntimeException e){throw new IOException("Ungültige Kontoverknüpfung: "+key,e);}
        }
        links=Collections.unmodifiableMap(loaded);
    }
    List<Link> all(){return List.copyOf(links.values());}
    Link byMinecraft(UUID id){return links.get(id);}
    Link byDiscord(String id){return links.values().stream().filter(l->l.discord.equals(id)).findFirst().orElse(null);}
    synchronized String issue(String discord,int ttlSeconds){
        if(!discord.matches("[0-9]{5,25}"))throw new IllegalArgumentException("Invalid Discord ID");
        long now=System.currentTimeMillis();codes.entrySet().removeIf(e->e.getValue().expiry<now);
        for(var e:codes.entrySet())if(e.getValue().discord.equals(discord))return e.getKey();
        if(codes.size()>=5000)throw new IllegalStateException("Too many pending links. Please try again later.");
        String code;do{code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));}while(codes.containsKey(code));
        codes.put(code,new Challenge(discord,now+Math.max(60,Math.min(900,ttlSeconds))*1000L));return code;
    }
    synchronized Link consume(UUID minecraft,String name,String code) throws IOException {
        long now=System.currentTimeMillis();attempts.entrySet().removeIf(e->e.getValue().isEmpty()||e.getValue().peekLast()<now-60_000);
        Deque<Long> rate=attempts.computeIfAbsent(minecraft,k->new ArrayDeque<>());rate.removeIf(t->t<now-60_000);
        if(rate.size()>=5)throw new IllegalArgumentException("Too many attempts. Please wait one minute.");rate.add(now);
        Challenge c=codes.get(code);
        if(c==null||c.expiry<now){codes.remove(code);throw new IllegalArgumentException("Invalid or expired code. Use /link in Discord for a new code.");}
        Link l=link(minecraft,c.discord,name);codes.entrySet().removeIf(e->e.getValue().discord.equals(c.discord));return l;
    }
    synchronized Link link(UUID id,String discord,String name) throws IOException {
        if(!discord.matches("[0-9]{5,25}"))throw new IllegalArgumentException("Invalid Discord ID");
        if(links.containsKey(id)||byDiscord(discord)!=null)throw new IllegalArgumentException("An account is already linked. Unlink it first.");
        Map<UUID,Link> next=new LinkedHashMap<>(links);Link l=new Link(id,discord,name,System.currentTimeMillis());next.put(id,l);persist(next);links=Collections.unmodifiableMap(next);return l;
    }
    synchronized boolean unlink(UUID id,String expectedDiscord) throws IOException {
        Link old=links.get(id);if(old==null||!old.discord.equals(expectedDiscord))return false;
        Map<UUID,Link> next=new LinkedHashMap<>(links);next.remove(id);persist(next);links=Collections.unmodifiableMap(next);codes.entrySet().removeIf(e->e.getValue().discord.equals(old.discord));return true;
    }
    synchronized Link rename(UUID id,String expected,String name)throws IOException {
        Link old=links.get(id);if(old==null||!old.discord.equals(expected))return null;
        if(old.name.equals(name))return old;
        Map<UUID,Link> next=new LinkedHashMap<>(links);Link update=new Link(id,old.discord,name,old.linkedAt);next.put(id,update);persist(next);links=Collections.unmodifiableMap(next);return update;
    }
    private void persist(Map<UUID,Link> next)throws IOException{
        Properties p=new Properties();p.setProperty("schema","1");
        for(Link l:next.values())p.setProperty("link."+l.minecraft,l.discord+"|"+Base64.getEncoder().encodeToString(l.name.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"|"+l.linkedAt+"|1");
        AtomicState.save(file,p,"Simplex Discord links; no verification codes");
    }
}
