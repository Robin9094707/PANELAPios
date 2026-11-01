package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.*;

/** Additive username/password accounts. Passwords never leave PBKDF2; only session hashes persist. */
final class AppUsers {
    static final int ITERATIONS=600_000;
    private final Path file;
    private final Properties state=new Properties();
    private final SecureRandom random=new SecureRandom();
    AppUsers(Path file)throws IOException{this.file=file;if(Files.exists(file))try(var in=Files.newInputStream(file)){state.load(in);}AppApiSecurity.protect(file);}
    static String username(String raw){String s=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);if(!s.matches("[a-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Benutzername: 1–64 Buchstaben, Ziffern, Punkt, - oder _");return s;}
    private String random(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    static String derive(String password,String salt){PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),Base64.getDecoder().decode(salt),ITERATIONS,256);try{return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}catch(Exception e){throw new IllegalStateException(e);}finally{spec.clearPassword();}}
    synchronized boolean exists(String name){return state.containsKey("u."+name+".hash");}
    synchronized Set<String> permissions(String name){return Set.copyOf(Arrays.asList(state.getProperty("u."+name+".permissions","").split(",")));}
    synchronized boolean enabled(String name){return exists(name)&&Boolean.parseBoolean(state.getProperty("u."+name+".enabled","true"));}
    synchronized List<Map<String,Object>> list(){List<Map<String,Object>> rows=new ArrayList<>();for(String k:state.stringPropertyNames())if(k.startsWith("u.")&&k.endsWith(".hash")){String name=k.substring(2,k.length()-5);rows.add(SimplexAdminSuite.appMap("username",name,"enabled",enabled(name),"permissions",permissions(name).stream().filter(s->!s.isBlank()).sorted().toList()));}rows.sort(Comparator.comparing(r->String.valueOf(r.get("username"))));return rows;}
    synchronized void save(String raw,String password,Set<String> permissions,boolean enabled)throws IOException{
        String name=username(raw),stem="u."+name+".";boolean fresh=!exists(name);
        if((fresh||!password.isEmpty())&&(password.length()<12||password.length()>256))throw new IllegalArgumentException("Passwort: 12–256 Zeichen");
        if(fresh&&list().size()>=128)throw new IllegalArgumentException("Maximal 128 App-Benutzer");
        Properties next=new Properties();next.putAll(state);
        if(fresh||!password.isEmpty()){byte[] salt=new byte[24];random.nextBytes(salt);String s=Base64.getEncoder().encodeToString(salt);next.setProperty(stem+"salt",s);next.setProperty(stem+"hash",derive(password,s));next.setProperty(stem+"algorithm","PBKDF2-HMAC-SHA256/"+ITERATIONS);}
        next.setProperty(stem+"permissions",String.join(",",new TreeSet<>(permissions)));next.setProperty(stem+"enabled",Boolean.toString(enabled));
        // Changing rights, password or enabled state revokes every previous session immediately.
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("s.")&&next.getProperty(k).startsWith(name+"|"))next.remove(k);
        persist(next);
    }
    synchronized void delete(String raw)throws IOException{String name=username(raw);Properties next=new Properties();next.putAll(state);for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("u."+name+".")||k.startsWith("s.")&&next.getProperty(k).startsWith(name+"|"))next.remove(k);persist(next);}
    synchronized String login(String raw,String password,long now)throws IOException{
        String parsed;try{parsed=username(raw);}catch(Exception e){parsed="invalid";}final String name=parsed;
        String salt=state.getProperty("u."+name+".salt",Base64.getEncoder().encodeToString(new byte[24]));String candidate=derive(password,salt);
        if(!enabled(name)||!MessageDigest.isEqual(candidate.getBytes(java.nio.charset.StandardCharsets.US_ASCII),state.getProperty("u."+name+".hash","").getBytes(java.nio.charset.StandardCharsets.US_ASCII)))return null;
        Properties next=new Properties();next.putAll(state);for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("s.")){String[] parts=next.getProperty(k).split("\\|");if(parts.length!=2||Long.parseLong(parts[1])<now)next.remove(k);}
        long count=next.stringPropertyNames().stream().filter(k->k.startsWith("s.")&&next.getProperty(k).startsWith(name+"|")).count();if(count>=12)throw new IllegalArgumentException("Zu viele Sitzungen. Passwort/Rechte neu speichern, um alte Sitzungen abzumelden.");
        String token="sasu_"+random();next.setProperty("s."+AppApiSecurity.hash(token),name+"|"+(now+30L*86_400_000));persist(next);return token;
    }
    synchronized AppApiSecurity.Key authenticate(String token){if(token==null||!token.startsWith("sasu_")||token.length()>160)return null;String digest=AppApiSecurity.hash(token),row=state.getProperty("s."+digest);if(row==null)return null;String[] parts=row.split("\\|");if(parts.length!=2||!enabled(parts[0])||Long.parseLong(parts[1])<System.currentTimeMillis())return null;return new AppApiSecurity.Key("user:"+parts[0]+":"+digest.substring(0,16),parts[0],"full",digest,0);}
    synchronized boolean valid(AppApiSecurity.Key k){if(!k.id().startsWith("user:"))return false;String row=state.getProperty("s."+k.hash());return row!=null&&row.startsWith(k.label()+"|")&&enabled(k.label())&&Long.parseLong(row.split("\\|")[1])>=System.currentTimeMillis();}
    synchronized boolean allows(AppApiSecurity.Key key,String permission){return !key.id().startsWith("user:")||valid(key)&&(permissions(key.label()).contains("*")||permissions(key.label()).contains(permission));}
    synchronized boolean canNotify(String owner){if(!owner.startsWith("user:"))return false;String name=owner.substring(5);return enabled(name)&&(permissions(name).contains("*")||permissions(name).contains("action.notifications.update"));}
    private void persist(Properties next)throws IOException{AtomicState.save(file,next,"Simplex App users: PBKDF2 passwords and SHA-256 session hashes. No plaintext passwords.");AppApiSecurity.protect(file);state.clear();state.putAll(next);}
}
