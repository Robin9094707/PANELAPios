package eu.simplexsmp.adminsuite;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Standalone authentication/confirmation store; no plugin or player data is rewritten. */
final class AppApiSecurity {
    record Key(String id,String label,String scope,String hash,long created) {}
    record Issued(Key key,String token) {}
    record Ticket(String owner,String request,long expires) {}
    final Path directory;
    private final Map<String,Key> keys = new LinkedHashMap<>();
    private final Map<String,Ticket> tickets = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Properties state = new Properties();
    AppApiSecurity(Path directory) throws Exception {
        this.directory=directory;Files.createDirectories(directory);protect(directory);
        Path file=directory.resolve("keys.properties");
        if(Files.exists(file))try(var input=Files.newInputStream(file)){state.load(input);}
        for(String name:state.stringPropertyNames())if(name.endsWith(".hash")){
            String id=name.substring(0,name.length()-5);
            keys.put(id,new Key(id,state.getProperty(id+".label","App"),state.getProperty(id+".scope","read"),state.getProperty(name),Long.parseLong(state.getProperty(id+".created","0"))));
        }
        if(!state.containsKey("server-id")){state.setProperty("server-id",UUID.randomUUID().toString());save();}
    }
    synchronized String serverId(){return state.getProperty("server-id");}
    synchronized List<Key> keys(){return List.copyOf(keys.values());}
    synchronized Issued issue(String label,String scope) throws Exception {
        if(!Set.of("read","manage","full").contains(scope))throw new IllegalArgumentException("Rechteprofil: read, manage oder full");
        if(keys.size()>=32)throw new IllegalArgumentException("Maximal 32 Schlüssel; zuerst einen widerrufen.");
        if(label==null||!label.matches("[\\p{L}\\p{N} _.:-]{1,64}"))throw new IllegalArgumentException("Schlüsselname: 1–64 Buchstaben/Ziffern");
        String id=UUID.randomUUID().toString().substring(0,12),token="sas_"+id+"_"+randomToken();
        Key key=new Key(id,label,scope,hash(token),System.currentTimeMillis());keys.put(id,key);
        try{save();}catch(Exception e){keys.remove(id);throw e;}return new Issued(key,token);
    }
    synchronized Key authenticate(String token){
        if(token==null||token.length()>160)return null;
        String digest=hash(token);
        for(Key key:keys.values())if(MessageDigest.isEqual(key.hash().getBytes(StandardCharsets.US_ASCII),digest.getBytes(StandardCharsets.US_ASCII)))return key;
        return null;
    }
    synchronized void revoke(String id)throws Exception{
        Key removed=keys.remove(id);if(removed==null)throw new IllegalArgumentException("Schlüssel-ID nicht gefunden");
        try{save();}catch(Exception e){keys.put(id,removed);throw e;}
        tickets.values().removeIf(t->t.owner().equals(id));
    }
    synchronized String prepare(Key key,String request,long now){
        tickets.values().removeIf(t->t.expires()<now);
        if(tickets.size()>=128)throw new IllegalArgumentException("Zu viele offene Bestätigungen");
        String id=randomToken();tickets.put(id,new Ticket(key.id(),request,now+60_000));return id;
    }
    synchronized String consume(Key key,String id,long now){
        Ticket ticket=tickets.get(id);
        if(ticket==null||ticket.expires()<now||!ticket.owner().equals(key.id())||!keys.containsKey(key.id()))throw new IllegalArgumentException("Bestätigung abgelaufen, bereits verwendet oder nicht für diesen Schlüssel.");
        tickets.remove(id);return ticket.request();
    }
    static boolean permitted(Key key,String required){return key!=null&&(required.equals("read")||key.scope().equals("full")||required.equals("manage")&&key.scope().equals("manage"));}
    private String randomToken(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void save()throws Exception{
        Properties result=new Properties();result.setProperty("server-id",state.getProperty("server-id"));
        for(Key k:keys.values()){result.setProperty(k.id()+".label",k.label());result.setProperty(k.id()+".scope",k.scope());result.setProperty(k.id()+".hash",k.hash());result.setProperty(k.id()+".created",Long.toString(k.created()));}
        Path temp=Files.createTempFile(directory,"keys-",".tmp");
        try{protect(temp);try(var out=Files.newOutputStream(temp)){result.store(out,"Simplex Panel API: only key hashes; do not publish");}
            try{Files.move(temp,directory.resolve("keys.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,directory.resolve("keys.properties"),StandardCopyOption.REPLACE_EXISTING);}
            protect(directory.resolve("keys.properties"));state.clear();state.putAll(result);
        }finally{Files.deleteIfExists(temp);}
    }
    static void protect(Path file){try{Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString(Files.isDirectory(file)?"rwx------":"rw-------"));}catch(Exception ignored){}}
    static void checkJson(String text){
        int depth=0;boolean string=false,escape=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}else if(c=='"')string=true;else if(c=='{'||c=='['){if(++depth>24)throw new IllegalArgumentException("JSON zu tief verschachtelt");}else if(c=='}'||c==']'){if(--depth<0)throw new IllegalArgumentException("Ungültiges JSON");}}
        if(depth!=0||string)throw new IllegalArgumentException("Unvollständiges JSON");
    }
}
