package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.*;

/**
 * Additive username/password accounts. Passwords never leave PBKDF2; only session hashes persist.
 * 9.8.0 adds (same file, additive keys): the main admin ("owner"), TOTP two-factor with hashed recovery codes,
 * passkeys, per-session device details, temporary device locks and a console-only emergency reset.
 */
final class AppUsers {
    static final int ITERATIONS=600_000;
    static final long APP_SESSION=30L*86_400_000, WEB_SESSION=7L*86_400_000, WEB_SHORT=12L*3_600_000, HANDOFF_SESSION=15L*60_000;
    static final int MAX_SESSIONS=20;
    private final Path file;
    private final Properties state=new Properties();
    private final SecureRandom random=new SecureRandom();
    private record Seen(long at,String ip,Map<String,String> device){}
    private final Map<String,Seen> seen=new HashMap<>();
    private long flushed=System.currentTimeMillis();
    AppUsers(Path file)throws IOException{this.file=file;if(Files.exists(file))try(var in=Files.newInputStream(file)){state.load(in);}AppApiSecurity.protect(file);}
    static String username(String raw){String s=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);if(!s.matches("[a-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Benutzername: 1–64 Buchstaben, Ziffern, Punkt, - oder _");return s;}
    private String random(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    static String derive(String password,String salt){PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),Base64.getDecoder().decode(salt),ITERATIONS,256);try{return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}catch(Exception e){throw new IllegalStateException(e);}finally{spec.clearPassword();}}
    static void checkPassword(String password){if(password==null||password.length()<12||password.length()>256)throw new IllegalArgumentException("Passwort: 12–256 Zeichen");}
    synchronized boolean exists(String name){return state.containsKey("u."+name+".hash");}
    synchronized String created(String name){return state.getProperty("u."+name+".created","0");}
    synchronized Set<String> permissions(String name){return Set.copyOf(Arrays.asList(state.getProperty("u."+name+".permissions","").split(",")));}
    synchronized boolean enabled(String name){return exists(name)&&Boolean.parseBoolean(state.getProperty("u."+name+".enabled","true"));}
    synchronized boolean owner(String name){return exists(name)&&Boolean.parseBoolean(state.getProperty("u."+name+".owner","false"));}
    synchronized String ownerName(){for(String k:state.stringPropertyNames())if(k.startsWith("u.")&&k.endsWith(".owner")&&Boolean.parseBoolean(state.getProperty(k))){String name=k.substring(2,k.length()-6);if(exists(name))return name;}return "";}
    synchronized boolean mfa(String name){return !state.getProperty("u."+name+".totp","").isBlank();}
    synchronized boolean mustChange(String name){return Boolean.parseBoolean(state.getProperty("u."+name+".must-change","false"));}
    synchronized List<Map<String,Object>> list(){List<Map<String,Object>> rows=new ArrayList<>();for(String k:state.stringPropertyNames())if(k.startsWith("u.")&&k.endsWith(".hash")){String name=k.substring(2,k.length()-5);
        rows.add(SimplexAdminSuite.appMap("username",name,"enabled",enabled(name),"permissions",permissions(name).stream().filter(s->!s.isBlank()).sorted().toList(),"setupExpires",setupExpiry(name),"sessions",sessions(name),"owner",owner(name),"mfa",mfa(name),"passkeys",passkeys(name).size(),"lastSeen",lastSeen(name),"mustChange",mustChange(name),"publicName",publicName(name),"created",Long.parseLong(state.getProperty("u."+name+".created","0"))));}
        rows.sort(Comparator.comparing((Map<String,Object> r)->!Boolean.TRUE.equals(r.get("owner"))).thenComparing(r->String.valueOf(r.get("username"))));return rows;}
    synchronized void save(String raw,String password,Set<String> permissions,boolean enabled)throws IOException{
        String name=username(raw),stem="u."+name+".";boolean fresh=!exists(name);
        if(fresh||!password.isEmpty())checkPassword(password);
        if(fresh&&list().size()>=128)throw new IllegalArgumentException("Maximal 128 App-Benutzer");
        Properties next=copy();
        if(fresh||!password.isEmpty())setPassword(next,name,password);
        if(fresh)next.setProperty(stem+"created",Long.toString(System.currentTimeMillis()));
        next.setProperty(stem+"permissions",String.join(",",new TreeSet<>(permissions)));next.setProperty(stem+"enabled",Boolean.toString(enabled));
        // Changing rights, password or enabled state revokes every previous session immediately.
        dropSessions(next,name,null);
        persist(next);
    }
    private void setPassword(Properties next,String name,String password){byte[] salt=new byte[24];random.nextBytes(salt);String s=Base64.getEncoder().encodeToString(salt);String stem="u."+name+".";next.setProperty(stem+"salt",s);next.setProperty(stem+"hash",derive(password,s));next.setProperty(stem+"algorithm","PBKDF2-HMAC-SHA256/"+ITERATIONS);}
    private void dropSessions(Properties next,String name,String keepDigest){
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("s.")&&next.getProperty(k).startsWith(name+"|")){String digest=k.substring(2);if(digest.equals(keepDigest))continue;next.remove(k);next.remove("m."+digest);seen.remove(digest);}
    }
    synchronized void logoutAll(String name)throws IOException{Properties next=copy();dropSessions(next,name,null);persist(next);}
    synchronized void delete(String raw)throws IOException{
        String name=username(raw);Properties next=copy();
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(own(k,name)||(k.startsWith("s.")||k.startsWith("t."))&&next.getProperty(k).startsWith(name+"|"))next.remove(k);
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("m.")&&!next.containsKey("s."+k.substring(2)))next.remove(k);
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("p.")&&name.equals(String.valueOf(json(next.getProperty(k)).get("user"))))next.remove(k);
        persist(next);
    }
    /** Password check only. Returns the canonical user name or null; always spends one PBKDF2 derivation. */
    synchronized String verifyPassword(String raw,String password){
        String parsed;try{parsed=username(raw);}catch(Exception e){parsed="invalid";}final String name=parsed;
        String salt=state.getProperty("u."+name+".salt",Base64.getEncoder().encodeToString(new byte[24]));String candidate=derive(password==null?"":password,salt);
        if(!enabled(name)||!MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),state.getProperty("u."+name+".hash","").getBytes(StandardCharsets.US_ASCII)))return null;
        return name;
    }
    /** Legacy single-step login (no second factor, no pending password change). */
    synchronized String login(String raw,String password,long now)throws IOException{
        String name=verifyPassword(raw,password);if(name==null||mfa(name)||mustChange(name))return null;
        return createSession(name,now,APP_SESSION,Map.of("kind","app"));
    }
    /** Creates a session with device details; the least recently used session is replaced once the limit is reached. */
    synchronized String createSession(String name,long now,long ttl,Map<String,?> device)throws IOException{
        if(!enabled(name))throw new IllegalArgumentException("Benutzer deaktiviert");
        Properties next=copy();
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("s.")){String[] parts=next.getProperty(k).split("\\|");if(parts.length!=2||Long.parseLong(parts[1])<now){next.remove(k);next.remove("m."+k.substring(2));}}
        List<String> own=next.stringPropertyNames().stream().filter(k->k.startsWith("s.")&&next.getProperty(k).startsWith(name+"|")).map(k->k.substring(2)).sorted(Comparator.comparingLong(d->seenAt(next,d))).toList();
        for(int i=0;i<=own.size()-MAX_SESSIONS;i++){next.remove("s."+own.get(i));next.remove("m."+own.get(i));seen.remove(own.get(i));}
        String token="sasu_"+random(),digest=AppApiSecurity.hash(token);
        next.setProperty("s."+digest,name+"|"+(now+ttl));
        Map<String,Object> meta=new LinkedHashMap<>();for(var e:device.entrySet())if(e.getValue()!=null)meta.put(e.getKey(),e.getValue() instanceof Boolean?e.getValue():clean(String.valueOf(e.getValue()),120));
        meta.putIfAbsent("kind","app");meta.put("created",now);meta.put("seen",now);next.setProperty("m."+digest,DiscordBotManager.Json.stringify(meta));
        persist(next);return token;
    }
    private long seenAt(Properties p,String digest){Seen s=seen.get(digest);if(s!=null)return s.at();Object v=json(p.getProperty("m."+digest,"")).get("seen");return v instanceof Number n?n.longValue():0;}
    /** 9.7.0: one-time setup code for an existing, enabled user. A new code replaces the previous one of that user. */
    synchronized String setupCode(String raw,int minutes,long now)throws IOException{
        String name=username(raw);if(!enabled(name))throw new IllegalArgumentException("Benutzer existiert nicht oder ist deaktiviert");
        if(mfa(name))throw new IllegalArgumentException("Dieser Benutzer nutzt Zwei-Faktor-Schutz und meldet sich mit Passwort und Code an.");
        if(minutes<5||minutes>10080)throw new IllegalArgumentException("Gültigkeit: 5 Minuten bis 7 Tage");
        Properties next=copy();
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("t.")){String[] parts=next.getProperty(k).split("\\|");if(parts.length!=2||parts[0].equals(name)||Long.parseLong(parts[1])<now)next.remove(k);}
        String code="sass_"+random();next.setProperty("t."+AppApiSecurity.hash(code),name+"|"+(now+minutes*60_000L));persist(next);return code;
    }
    /** Redeems a setup code exactly once and returns {username, session}. Failed codes reveal nothing. */
    synchronized String[] redeem(String code,long now)throws IOException{return redeem(code,now,Map.of("kind","app","label","Setup-Link"));}
    synchronized String[] redeem(String code,long now,Map<String,?> device)throws IOException{
        if(code==null||!code.startsWith("sass_")||code.length()>160)return null;
        String key="t."+AppApiSecurity.hash(code),row=state.getProperty(key);if(row==null)return null;
        Properties next=copy();next.remove(key);
        String[] parts=row.split("\\|");if(parts.length!=2||Long.parseLong(parts[1])<now||!enabled(parts[0])||mfa(parts[0])){persist(next);return null;}
        persist(next);return new String[]{parts[0],createSession(parts[0],now,APP_SESSION,device)};
    }
    synchronized long setupExpiry(String name){long best=0;for(String k:state.stringPropertyNames())if(k.startsWith("t.")){String[] parts=state.getProperty(k).split("\\|");if(parts.length==2&&parts[0].equals(name))best=Math.max(best,Long.parseLong(parts[1]));}return best>System.currentTimeMillis()?best:0;}
    synchronized void cancelSetup(String raw)throws IOException{String name=username(raw);Properties next=copy();next.stringPropertyNames().stream().filter(k->k.startsWith("t.")&&next.getProperty(k).startsWith(name+"|")).toList().forEach(next::remove);persist(next);}
    synchronized int sessions(String name){long now=System.currentTimeMillis();int n=0;for(String k:state.stringPropertyNames())if(k.startsWith("s.")){String[] parts=state.getProperty(k).split("\\|");if(parts.length==2&&parts[0].equals(name)&&Long.parseLong(parts[1])>=now)n++;}return n;}
    synchronized AppApiSecurity.Key authenticate(String token){if(token==null||!token.startsWith("sasu_")||token.length()>160)return null;String digest=AppApiSecurity.hash(token),row=state.getProperty("s."+digest);if(row==null)return null;String[] parts=row.split("\\|");if(parts.length!=2||!enabled(parts[0])||Long.parseLong(parts[1])<System.currentTimeMillis())return null;return new AppApiSecurity.Key("user:"+parts[0]+":"+digest.substring(0,16),parts[0],"full",digest,0);}
    synchronized boolean valid(AppApiSecurity.Key k){if(!k.id().startsWith("user:"))return false;String row=state.getProperty("s."+k.hash());return row!=null&&row.startsWith(k.label()+"|")&&enabled(k.label())&&Long.parseLong(row.split("\\|")[1])>=System.currentTimeMillis();}
    synchronized boolean allows(AppApiSecurity.Key key,String permission){return !key.id().startsWith("user:")||valid(key)&&(permissions(key.label()).contains("*")||permissions(key.label()).contains(permission));}
    synchronized boolean canNotify(String owner){if(!owner.startsWith("user:"))return false;String name;try{name=new String(Base64.getUrlDecoder().decode(owner.substring(5)),StandardCharsets.UTF_8);}catch(IllegalArgumentException e){return false;}return enabled(name)&&(permissions(name).contains("*")||permissions(name).contains("action.notifications.update"));}

    // ---- 9.8.0: main admin, migration and emergency reset ----

    /** Creates the main admin from an existing full API key. The key itself stays valid unless the caller revokes it. */
    synchronized void createOwner(String raw,String password)throws IOException{
        String name=username(raw);checkPassword(password);
        if(!ownerName().isEmpty())throw new IllegalArgumentException("Es gibt bereits einen Hauptadmin-Zugang ("+ownerName()+"). Mit ihm anmelden oder per Serverkonsole zurücksetzen.");
        if(exists(name))throw new IllegalArgumentException("Benutzername ist bereits vergeben");
        Properties next=copy();setPassword(next,name,password);String stem="u."+name+".";
        next.setProperty(stem+"permissions","*");next.setProperty(stem+"enabled","true");next.setProperty(stem+"owner","true");next.setProperty(stem+"created",Long.toString(System.currentTimeMillis()));
        persist(next);
    }
    /**
     * Console-only recovery. Shows the user name again, sets a one-time password that must be replaced at the next
     * login, removes two-factor, recovery codes and passkeys, unlocks and signs out every device of the main admin.
     * Without an existing main admin, {@code raw} creates one (or promotes that existing user).
     */
    synchronized String[] emergencyReset(String raw)throws IOException{
        String name=ownerName();boolean created=false;
        if(name.isEmpty()){
            if(raw==null||raw.isBlank())throw new IllegalArgumentException("Noch kein Hauptadmin-Zugang. Benutzernamen angeben: /sas app owner-reset <Benutzername>");
            name=username(raw);created=!exists(name);
        }
        String password=AccountCrypto.password();Properties next=copy();String stem="u."+name+".";
        setPassword(next,name,password);next.setProperty(stem+"permissions","*");next.setProperty(stem+"enabled","true");next.setProperty(stem+"owner","true");next.setProperty(stem+"must-change","true");
        if(created)next.setProperty(stem+"created",Long.toString(System.currentTimeMillis()));
        for(String suffix:List.of("totp","totp-step","recovery"))next.remove(stem+suffix);
        final String user=name;for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("p.")&&user.equals(String.valueOf(json(next.getProperty(k)).get("user"))))next.remove(k);
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("t.")&&next.getProperty(k).startsWith(name+"|"))next.remove(k);
        dropSessions(next,name,null);persist(next);
        return new String[]{name,password,Boolean.toString(created)};
    }
    // ---- 9.9.0: username change, public name, console recovery for any user ----

    /**
     * Renames an account. Password, 2FA, recovery codes, passkeys, permissions and every signed-in device move to the
     * new name; tokens stay valid because sessions are stored by token hash. Pending setup links are cancelled.
     */
    synchronized void rename(String oldRaw,String newRaw)throws IOException{
        String from=username(oldRaw),to=username(newRaw);
        if(!exists(from))throw new IllegalArgumentException("Benutzer nicht gefunden");
        if(from.equals(to))throw new IllegalArgumentException("Der neue Benutzername ist identisch.");
        if(exists(to))throw new IllegalArgumentException("Benutzername ist bereits vergeben");
        Properties next=new Properties();
        for(String k:state.stringPropertyNames()){
            String v=state.getProperty(k),key=k;
            if(own(k,from))key="u."+to+"."+k.substring(("u."+from+".").length());
            else if(k.startsWith("t.")&&v.startsWith(from+"|"))continue;
            else if(k.startsWith("s.")&&v.startsWith(from+"|"))v=to+v.substring(from.length());
            else if(k.startsWith("p.")){Map<String,Object> row=json(v);if(from.equals(row.get("user"))){row.put("user",to);v=DiscordBotManager.Json.stringify(row);}}
            next.setProperty(key,v);
        }
        next.setProperty("u."+to+".renamed",Long.toString(System.currentTimeMillis()));
        persist(next);
    }
    /** Name players see on ticket replies and in-game notices; empty = server default ("Team"). */
    synchronized String publicName(String name){return state.getProperty("u."+name+".public-name","");}
    synchronized void setPublicName(String name,String value)throws IOException{
        if(!exists(name))throw new IllegalArgumentException("Benutzer nicht gefunden");
        String v=value==null?"":value.trim();if(!v.isEmpty())AppChat.validateName(v);
        Properties next=copy();if(v.isEmpty())next.remove("u."+name+".public-name");else next.setProperty("u."+name+".public-name",v);persist(next);
    }
    /** Console recovery for any user: one-time password (must be changed), 2FA/recovery/passkeys removed, devices signed out and unlocked. */
    synchronized String resetUser(String raw)throws IOException{
        String name=username(raw);if(!exists(name))throw new IllegalArgumentException("Benutzer nicht gefunden");
        String password=AccountCrypto.password();Properties next=copy();String stem="u."+name+".";
        setPassword(next,name,password);next.setProperty(stem+"enabled","true");next.setProperty(stem+"must-change","true");
        for(String suffix:List.of("totp","totp-step","recovery"))next.remove(stem+suffix);
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("p.")&&name.equals(String.valueOf(json(next.getProperty(k)).get("user"))))next.remove(k);
        for(String k:new ArrayList<>(next.stringPropertyNames()))if(k.startsWith("t.")&&next.getProperty(k).startsWith(name+"|"))next.remove(k);
        dropSessions(next,name,null);persist(next);return password;
    }
    /** Console only: makes another existing user the main admin (full rights); the previous main admin keeps a normal account with all rights. */
    synchronized String transferOwner(String raw)throws IOException{
        String name=username(raw);if(!exists(name))throw new IllegalArgumentException("Benutzer nicht gefunden");
        String previous=ownerName();Properties next=copy();
        if(!previous.isEmpty())next.setProperty("u."+previous+".owner","false");
        next.setProperty("u."+name+".owner","true");next.setProperty("u."+name+".permissions","*");next.setProperty("u."+name+".enabled","true");
        persist(next);return previous;
    }
    synchronized void setEnabled(String raw,boolean enabled)throws IOException{
        String name=username(raw);if(!exists(name))throw new IllegalArgumentException("Benutzer nicht gefunden");
        if(!enabled&&owner(name))throw new IllegalArgumentException("Der Hauptadmin kann nicht deaktiviert werden. Zuerst „sas app owner-transfer <Benutzer>“.");
        Properties next=copy();next.setProperty("u."+name+".enabled",Boolean.toString(enabled));if(!enabled)dropSessions(next,name,null);persist(next);
    }
    /** Lifts temporary device locks of one user (every session). */
    synchronized int unlockAll(String raw)throws IOException{
        String name=username(raw);int n=0;Properties next=copy();
        for(String k:state.stringPropertyNames())if(k.startsWith("s.")&&state.getProperty(k).startsWith(name+"|")){String digest=k.substring(2);Map<String,Object> m=meta(digest);if(Boolean.TRUE.equals(m.get("locked"))){m.put("locked",false);next.setProperty("m."+digest,DiscordBotManager.Json.stringify(m));n++;}}
        persist(next);return n;
    }

    /** Changes the own password; other sessions of this user are signed out, the current one stays. */
    synchronized void changePassword(String name,String current,String fresh,String keepDigest)throws IOException{
        if(verifyPassword(name,current)==null)throw new IllegalArgumentException("Aktuelles Passwort ist falsch");
        replacePassword(name,fresh,keepDigest);
    }
    synchronized void replacePassword(String name,String fresh,String keepDigest)throws IOException{
        checkPassword(fresh);if(verifyPassword(name,fresh)!=null)throw new IllegalArgumentException("Bitte ein neues Passwort wählen");
        Properties next=copy();setPassword(next,name,fresh);next.remove("u."+name+".must-change");dropSessions(next,name,keepDigest);persist(next);
    }

    // ---- 9.8.0: TOTP and recovery codes ----

    synchronized void enableTotp(String name,String secret)throws IOException{Properties next=copy();next.setProperty("u."+name+".totp",secret);next.remove("u."+name+".totp-step");persist(next);}
    synchronized void disableMfa(String name)throws IOException{Properties next=copy();for(String s:List.of("totp","totp-step","recovery"))next.remove("u."+name+"."+s);persist(next);}
    synchronized boolean checkTotp(String name,String code,long now)throws IOException{
        String secret=state.getProperty("u."+name+".totp","");if(secret.isBlank())return false;
        long step=AccountCrypto.verifyTotp(secret,code,now),last=Long.parseLong(state.getProperty("u."+name+".totp-step","-1"));
        if(step<0||step<=last)return false;
        Properties next=copy();next.setProperty("u."+name+".totp-step",Long.toString(step));persist(next);return true;
    }
    synchronized List<String> newRecovery(String name)throws IOException{
        List<String> codes=AccountCrypto.recoveryCodes();Properties next=copy();
        next.setProperty("u."+name+".recovery",String.join(",",codes.stream().map(c->AppApiSecurity.hash("recovery|"+name+"|"+AccountCrypto.normalizeRecovery(c))).toList()));
        persist(next);return codes;
    }
    synchronized int recoveryLeft(String name){String v=state.getProperty("u."+name+".recovery","");return v.isBlank()?0:v.split(",").length;}
    synchronized boolean useRecovery(String name,String code)throws IOException{
        String normalized=AccountCrypto.normalizeRecovery(code);if(normalized.length()!=12)return false;
        String digest=AppApiSecurity.hash("recovery|"+name+"|"+normalized);List<String> left=new ArrayList<>(Arrays.asList(state.getProperty("u."+name+".recovery","").split(",")));
        boolean found=false;for(Iterator<String> it=left.iterator();it.hasNext();){String v=it.next();if(MessageDigest.isEqual(v.getBytes(StandardCharsets.US_ASCII),digest.getBytes(StandardCharsets.US_ASCII))){it.remove();found=true;}}
        if(!found)return false;Properties next=copy();next.setProperty("u."+name+".recovery",String.join(",",left.stream().filter(s->!s.isBlank()).toList()));persist(next);return true;
    }

    // ---- 9.8.0: passkeys ----

    synchronized String handle(String name)throws IOException{
        String value=state.getProperty("u."+name+".handle","");if(!value.isEmpty())return value;
        byte[] b=new byte[16];random.nextBytes(b);value=WebAuthn.b64(b);Properties next=copy();next.setProperty("u."+name+".handle",value);persist(next);return value;
    }
    synchronized String userForHandle(String handle){for(String k:state.stringPropertyNames())if(k.startsWith("u.")&&k.endsWith(".handle")&&state.getProperty(k).equals(handle))return k.substring(2,k.length()-7);return null;}
    synchronized List<Map<String,Object>> passkeys(String name){
        List<Map<String,Object>> out=new ArrayList<>();
        for(String k:state.stringPropertyNames())if(k.startsWith("p.")){Map<String,Object> row=json(state.getProperty(k));if(!name.equals(row.get("user")))continue;out.add(SimplexAdminSuite.appMap("id",k.substring(2,26),"name",row.get("name"),"created",row.get("created"),"used",row.get("used"),"rp",row.get("rp"),"synced",row.get("synced")));}
        out.sort(Comparator.comparingLong(r->r.get("created") instanceof Number n?n.longValue():0));return out;
    }
    synchronized List<String> credentialIds(String name){List<String> out=new ArrayList<>();for(String k:state.stringPropertyNames())if(k.startsWith("p.")){Map<String,Object> row=json(state.getProperty(k));if(name.equals(row.get("user")))out.add(String.valueOf(row.get("cid")));}return out;}
    synchronized void addPasskey(String name,WebAuthn.Credential c,String label,String rp)throws IOException{
        String key="p."+AppApiSecurity.hash(WebAuthn.b64(c.id()));if(state.containsKey(key))throw new IllegalArgumentException("Dieser Passkey ist bereits registriert");
        if(passkeys(name).size()>=10)throw new IllegalArgumentException("Maximal 10 Passkeys pro Benutzer");
        Properties next=copy();next.setProperty(key,DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("user",name,"cid",WebAuthn.b64(c.id()),"key",c.publicKey(),"alg",c.alg(),"count",c.signCount(),"name",clean(label.isBlank()?"Passkey":label,60),"created",System.currentTimeMillis(),"used",0L,"rp",rp,"synced",c.backupEligible())));
        persist(next);
    }
    /** Stored record for a credential id (base64url), or null. */
    synchronized Map<String,Object> passkey(String credentialId){String row=state.getProperty("p."+AppApiSecurity.hash(credentialId));return row==null?null:json(row);}
    synchronized void passkeyUsed(String credentialId,long count)throws IOException{String key="p."+AppApiSecurity.hash(credentialId);Map<String,Object> row=json(state.getProperty(key,""));if(row.isEmpty())return;row.put("count",count);row.put("used",System.currentTimeMillis());Properties next=copy();next.setProperty(key,DiscordBotManager.Json.stringify(row));persist(next);}
    synchronized void updatePasskey(String name,String id,String label,boolean delete)throws IOException{
        for(String k:state.stringPropertyNames())if(k.startsWith("p.")&&k.substring(2).startsWith(id)&&id.length()>=24){Map<String,Object> row=json(state.getProperty(k));if(!name.equals(row.get("user")))continue;
            Properties next=copy();if(delete)next.remove(k);else{row.put("name",clean(label,60));next.setProperty(k,DiscordBotManager.Json.stringify(row));}persist(next);return;}
        throw new IllegalArgumentException("Passkey nicht gefunden");
    }

    // ---- 9.8.0: devices and sessions ----

    /** Records activity in memory; written to disk at most every five minutes. */
    synchronized void touch(String digest,String ip,Map<String,String> device){seen.put(digest,new Seen(System.currentTimeMillis(),ip,device==null?Map.of():device));if(System.currentTimeMillis()-flushed>300_000L)try{persist(copy());}catch(IOException ignored){}}
    synchronized Map<String,Object> meta(String digest){Map<String,Object> m=json(state.getProperty("m."+digest,""));Seen s=seen.get(digest);if(s!=null){m.put("seen",s.at());if(s.ip()!=null&&!s.ip().isBlank())m.put("ip",s.ip());for(var e:s.device().entrySet())if(!e.getValue().isBlank())m.put(e.getKey(),e.getValue());}return m;}
    synchronized boolean locked(String digest){return Boolean.TRUE.equals(json(state.getProperty("m."+digest,"")).get("locked"));}
    synchronized long lastSeen(String name){long best=0;for(String k:state.stringPropertyNames())if(k.startsWith("s.")&&state.getProperty(k).startsWith(name+"|")){Object v=meta(k.substring(2)).get("seen");if(v instanceof Number n)best=Math.max(best,n.longValue());}return best;}
    /** All live sessions; {@code name} null = every user. */
    synchronized List<Map<String,Object>> sessionRows(String name){
        long now=System.currentTimeMillis();List<Map<String,Object>> out=new ArrayList<>();
        for(String k:state.stringPropertyNames())if(k.startsWith("s.")){String[] parts=state.getProperty(k).split("\\|");if(parts.length!=2||Long.parseLong(parts[1])<now)continue;if(name!=null&&!parts[0].equals(name))continue;
            String digest=k.substring(2);Map<String,Object> m=meta(digest);Map<String,Object> row=new LinkedHashMap<>(m);row.put("id",digest.substring(0,24));row.put("user",parts[0]);row.put("expires",Long.parseLong(parts[1]));row.put("owner",owner(parts[0]));row.putIfAbsent("kind","app");row.putIfAbsent("locked",false);row.putIfAbsent("seen",0L);out.add(row);}
        out.sort(Comparator.comparingLong((Map<String,Object> r)->r.get("seen") instanceof Number n?n.longValue():0).reversed());return out;
    }
    synchronized String digestFor(String id){if(id==null||!id.matches("[a-f0-9]{24}"))return null;for(String k:state.stringPropertyNames())if(k.startsWith("s.")&&k.substring(2).startsWith(id))return k.substring(2);return null;}
    synchronized String sessionUser(String digest){String row=state.getProperty("s."+digest);return row==null?null:row.split("\\|")[0];}
    synchronized void updateSession(String digest,String operation,String label)throws IOException{
        if(!state.containsKey("s."+digest))throw new IllegalArgumentException("Gerät nicht gefunden oder bereits abgemeldet");
        Properties next=copy();Map<String,Object> m=meta(digest);
        switch(operation){
            case "rename"->{String v=clean(label,60);if(v.isBlank())m.remove("label");else m.put("label",v);next.setProperty("m."+digest,DiscordBotManager.Json.stringify(m));}
            case "lock","unlock"->{m.put("locked",operation.equals("lock"));next.setProperty("m."+digest,DiscordBotManager.Json.stringify(m));}
            case "logout"->{next.remove("s."+digest);next.remove("m."+digest);seen.remove(digest);}
            default->throw new IllegalArgumentException("Aktion: rename, lock, unlock oder logout");
        }
        persist(next);
    }
    /** Device details for classic API keys (keys.properties stays unchanged). */
    synchronized void touchKey(String id,String ip,Map<String,String> device){touch("k:"+id,ip,device);}
    synchronized Map<String,Object> keyMeta(String id){return meta("k:"+id);}
    synchronized boolean keyLocked(String id){return locked("k:"+id);}
    synchronized void updateKey(String id,String operation,String label)throws IOException{
        Properties next=copy();Map<String,Object> m=meta("k:"+id);
        switch(operation){case "rename"->{String v=clean(label,60);if(v.isBlank())m.remove("label");else m.put("label",v);}case "lock"->m.put("locked",true);case "unlock"->m.put("locked",false);default->throw new IllegalArgumentException("Aktion nicht möglich");}
        next.setProperty("m.k:"+id,DiscordBotManager.Json.stringify(m));persist(next);
    }
    synchronized void forgetKey(String id)throws IOException{Properties next=copy();next.remove("m.k:"+id);seen.remove("k:"+id);persist(next);}

    /**
     * True when {@code key} is a property of exactly this account. Usernames may contain dots, so "u.alice.bob.hash"
     * belongs to "alice.bob", never to "alice": account properties are single segments without a dot.
     */
    static boolean own(String key,String name){String stem="u."+name+".";return key.startsWith(stem)&&key.indexOf('.',stem.length())<0;}
    static String clean(String value,int max){String v=value==null?"":value.codePoints().filter(c->!Character.isISOControl(c)).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString().trim();return v.length()>max?v.substring(0,max):v;}
    @SuppressWarnings("unchecked") private static Map<String,Object> json(String raw){if(raw==null||raw.isBlank())return new LinkedHashMap<>();try{Object v=DiscordBotManager.Json.parse(raw);return v instanceof Map<?,?> m?new LinkedHashMap<>((Map<String,Object>)m):new LinkedHashMap<>();}catch(RuntimeException e){return new LinkedHashMap<>();}}
    private Properties copy(){Properties next=new Properties();next.putAll(state);return next;}
    private void persist(Properties next)throws IOException{
        // Merge in-memory activity into the session rows that still exist.
        for(var e:seen.entrySet()){String key="m."+e.getKey();if(!e.getKey().startsWith("k:")&&!next.containsKey("s."+e.getKey()))continue;Map<String,Object> m=json(next.getProperty(key,""));m.put("seen",e.getValue().at());if(e.getValue().ip()!=null&&!e.getValue().ip().isBlank())m.put("ip",e.getValue().ip());for(var d:e.getValue().device().entrySet())if(!d.getValue().isBlank())m.put(d.getKey(),d.getValue());next.setProperty(key,DiscordBotManager.Json.stringify(m));}
        AtomicState.save(file,next,"Simplex App users: PBKDF2 passwords, SHA-256 session hashes, TOTP secrets and passkey public keys. No plaintext passwords.");AppApiSecurity.protect(file);state.clear();state.putAll(next);flushed=System.currentTimeMillis();
    }
}
