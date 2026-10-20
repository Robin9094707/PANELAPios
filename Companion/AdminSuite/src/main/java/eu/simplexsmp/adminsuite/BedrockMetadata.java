package eu.simplexsmp.adminsuite;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Bounded, allowlisted extraction from Geyser's already verified ClientData. No tokens leave RAM. */
public final class BedrockMetadata {
    private BedrockMetadata() { }
    private static final Map<String,String> CLAIMS = new LinkedHashMap<>();
    public static final Set<String> COSMETICS = Set.of("profileHash", "skinId", "personaSkin", "premiumSkin", "skinColor", "skinImageWidth", "skinImageHeight", "capeId", "capeOnClassicSkin", "trustedSkin", "overrideSkin");
    public static final Set<String> PARTY = Set.of("partyId", "isPartyLeader");
    public static final Set<String> MISC = Set.of("filterProfanity", "clientEditorConnectionIntent", "clientIsEditorCapable");
    static {
        String[][] fields = {{"MaxViewDistance","maxViewDistance"},{"MemoryTier","memoryTier"},{"GraphicsMode","loginGraphicsMode"},{"PlatformType","platformType"},
            {"CompatibleWithClientSideChunkGen","clientSideChunkGen"},{"ProfileHash","profileHash"},{"PlatformUserId","platformUserId"},
            {"SkinId","skinId"},{"PersonaSkin","personaSkin"},{"PremiumSkin","premiumSkin"},{"SkinColor","skinColor"},
            {"SkinImageWidth","skinImageWidth"},{"SkinImageHeight","skinImageHeight"},{"CapeId","capeId"},{"CapeOnClassicSkin","capeOnClassicSkin"},
            {"TrustedSkin","trustedSkin"},{"OverrideSkin","overrideSkin"},{"PartyId","partyId"},{"IsPartyLeader","isPartyLeader"},
            {"FilterProfanity","filterProfanity"},{"ClientEditorConnectionIntent","clientEditorConnectionIntent"},{"ClientIsEditorCapable","clientIsEditorCapable"}};
        for (String[] field : fields) CLAIMS.put(field[0], field[1]);
    }
    public static void collect(Object connection, Object clientData, Properties out, boolean cosmetics, boolean party, boolean misc, boolean session) {
        put(out,"schemaVersion","3");
        if(connection != null) {
            try {
                Class<?> build=Class.forName("org.geysermc.geyser.BuildData",false,connection.getClass().getClassLoader());
                var version=build.getDeclaredField("VERSION"); version.trySetAccessible();
                var number=build.getDeclaredField("BUILD_NUMBER"); number.trySetAccessible();
                put(out,"geyserVersion",String.valueOf(version.get(null))+" build "+String.valueOf(number.get(null)));
            } catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        }
        put(out,"bedrockProtocolVersion",read(connection,"protocolVersion","getProtocolVersion"));
        put(out,"joinAddress",read(connection,"joinAddress","getJoinAddress"));
        put(out,"joinPort",read(connection,"joinPort","getJoinPort"));
        Map<String,String> raw = Map.of();
        String state = "unavailable";
        Object original = call(clientData,"getOriginalString");
        if (original instanceof String && !((String)original).isBlank()) {
            try { raw = decodeClientData((String)original); state = "geyser-clientdata-jwt"; }
            catch (IllegalArgumentException error) { state = "unreadable-clientdata-jwt"; }
        }
        for (Map.Entry<String,String> field : CLAIMS.entrySet()) {
            String key = field.getValue();
            if (COSMETICS.contains(key) && !cosmetics || PARTY.contains(key) && !party || MISC.contains(key) && !misc) continue;
            String name = field.getKey();
            String value = raw.get(name);
            // Prefer raw claims for primitives: a missing claim must not become a default false/zero.
            if (value == null && original == null) value = read(clientData,"get"+name,"is"+name);
            if (value != null && !value.isBlank()) put(out,key,value);
        }
        put(out,"collectionSource",state);
        put(out,"snapshotPhase",session ? "SESSION" : "LOGIN");
        put(out,"observedAt",Long.toString(System.currentTimeMillis()));
        String loginInput = read(clientData,"getCurrentInputMode","currentInputMode");
        put(out,"loginInputMode",loginInput);
        if (session) {
            put(out,"currentInputMode",read(connection,"inputMode","getInputMode"));
            String requested = read(connection,"getClientRenderDistance","clientRenderDistance");
            if (positive(requested)) { put(out,"requestedViewDistance",requested); put(out,"viewDistance",requested); }
        } else out.remove("viewDistance"); // Avoid publishing an early placeholder as a measured radius.
        put(out,"ping",read(connection,"ping","getPing"));
        // Current graphics is only claimed if Geyser genuinely exposes a live property.
        Object graphics = call(connection,"getGraphicsMode");
        if (session && graphics != null) put(out,"currentGraphicsMode",String.valueOf(graphics));
        String version = read(connection,"version","gameVersion","getGameVersion");
        if (!version.isBlank()) put(out,"gameVersion",version);
        String os = out.getProperty("deviceOs",read(clientData,"getDeviceOs","deviceOs"));
        String id = out.getProperty("deviceId",read(clientData,"getDeviceId","deviceId"));
        if (!id.isBlank()) {
            String format = format(id), expected = expectedFormat(os);
            put(out,"deviceIdFormat",format); put(out,"deviceIdFormatExpected",expected);
            put(out,"deviceIdFormatValid",expected.equals("UNDOCUMENTED") ? "UNKNOWN" : Boolean.toString(matchesFormat(id,expected)));
        }
        put(out,"metadataTrust","client-reported; not hardware-attested");
    }
    private static boolean positive(String raw) { try { return Integer.parseInt(raw)>0; } catch (Exception e) { return false; } }
    public static boolean matchesFormat(String id,String expected) {
        if (expected.equals("LOWER_HEX")) return id.matches("[0-9a-f]{32}");
        if (expected.equals("UPPER_HEX")) return id.matches("[0-9A-F]{32}");
        return format(id).equals(expected);
    }
    public static String format(String id) {
        if (id == null || id.isBlank()) return "MISSING";
        if (id.matches("[0-9a-f]{32}")) return "LOWER_HEX";
        if (id.matches("[0-9A-F]{32}")) return "UPPER_HEX";
        if (id.matches("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")) return "UUID";
        try { if (Base64.getDecoder().decode(id).length==32) return "BASE64"; } catch (IllegalArgumentException ignored) { }
        return "UNRECOGNIZED";
    }
    public static String expectedFormat(String os) {
        String s = os == null ? "" : os.toUpperCase(Locale.ROOT);
        switch(s) {
            case "1": case "ANDROID": return "LOWER_HEX";
            case "2": case "IOS": return "UPPER_HEX";
            case "15": case "WIN32": return "LOWER_HEX";
            case "11": case "ORBIS": case "PLAYSTATION": return "UUID";
            case "12": case "XBOX": return "BASE64";
            default: return "UNDOCUMENTED";
        }
    }
    public static Map<String,String> decodeClientData(String token) {
        if (token == null || token.length()>6*1024*1024) throw new IllegalArgumentException("ClientData too large");
        int first = token.indexOf('.'), last = token.indexOf('.', first+1);
        if (first<1 || last<=first+1 || token.indexOf('.',last+1)>=0) throw new IllegalArgumentException("Invalid ClientData shape");
        byte[] bytes = Base64.getUrlDecoder().decode(token.substring(first+1,last));
        if (bytes.length>4*1024*1024) throw new IllegalArgumentException("ClientData too large");
        return new Json(new String(bytes,StandardCharsets.UTF_8)).parse();
    }
    public static Object call(Object target, String method) {
        if (target==null) return null;
        try { Method m=target.getClass().getMethod(method); m.trySetAccessible(); return m.invoke(target); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }
    public static String read(Object target,String... names) {
        for(String name:names) { Object value=call(target,name); if(value!=null) { String text=String.valueOf(value).trim(); if(!text.isBlank()&&!text.equalsIgnoreCase("unknown")&&!text.equalsIgnoreCase("null")) return text; } }
        return "";
    }
    public static void put(Properties out,String key,String value) { if(value!=null&&!value.isBlank()) out.setProperty(key,clean(value)); }
    public static String clean(String raw) {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<raw.length()&&b.length()<512;i++) { char c=raw.charAt(i); if(!Character.isISOControl(c)&&Character.getType(c)!=Character.FORMAT&&c!='§') b.append(c); }
        return b.toString().trim();
    }
    /** Parses JSON while retaining only allowlisted top-level primitive values. Arrays/skin blobs are skipped. */
    private static final class Json {
        final String s; int i;
        Json(String s) { this.s=s; }
        Map<String,String> parse() {
            Map<String,String> result=new LinkedHashMap<>(); ws(); expect('{'); ws();
            if(take('}')) return result;
            do {
                ws(); String key=string(true); ws(); expect(':'); ws();
                String value=value(0,CLAIMS.containsKey(key));
                if(CLAIMS.containsKey(key)&&value!=null&&!value.isBlank()) result.put(key,value);
                ws(); if(take('}')) break; expect(',');
            } while(true);
            ws(); if(i!=s.length()) fail(); return result;
        }
        String value(int depth,boolean keep) {
            if(depth>64||i>=s.length()) { fail(); return null; }
            char c=s.charAt(i);
            if(c=='"') return string(keep);
            if(c=='{'||c=='[') {
                i++; ws(); char end=c=='{'?'}':']'; if(take(end)) return null;
                do { ws(); if(c=='{') { string(false); ws(); expect(':'); ws(); } value(depth+1,false); ws(); if(take(end)) break; expect(','); } while(true);
                return null;
            }
            int start=i; while(i<s.length()&&",]} \t\r\n".indexOf(s.charAt(i))<0) i++;
            String raw=s.substring(start,i);
            if(!raw.equals("true")&&!raw.equals("false")&&!raw.equals("null")&&!raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) fail();
            return keep&&!raw.equals("null")?raw:null;
        }
        String string(boolean keep) {
            expect('"'); StringBuilder b=keep?new StringBuilder():null;
            while(i<s.length()) {
                char c=s.charAt(i++); if(c=='"') return keep?b.toString():null;
                if(c<' ') fail();
                if(c=='\\') {
                    if(i>=s.length()) fail(); c=s.charAt(i++);
                    switch(c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c='\b'; break; case 'f': c='\f'; break; case 'n': c='\n'; break; case 'r': c='\r'; break; case 't': c='\t'; break;
                        case 'u': if(i+4>s.length()) fail(); try { c=(char)Integer.parseInt(s.substring(i,i+4),16); } catch(Exception e) { fail(); } i+=4; break;
                        default: fail();
                    }
                }
                if(keep&&b.length()<512) b.append(c);
            }
            fail(); return null;
        }
        void ws() { while(i<s.length()&&" \t\r\n".indexOf(s.charAt(i))>=0) i++; }
        boolean take(char c) { if(i<s.length()&&s.charAt(i)==c) { i++; return true; } return false; }
        void expect(char c) { if(!take(c)) fail(); }
        void fail() { throw new IllegalArgumentException("Invalid ClientData JSON"); }
    }
}
