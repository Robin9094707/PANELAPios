package eu.simplexsmp.adminsuite;

import com.destroystokyo.paper.ClientOption;
import org.bukkit.entity.Player;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate diagnostic storage; deliberately contains no Alt score or punishment APIs. */
final class DeviceDiagnostics {
    static final LinkedHashMap<String,String> LABELS = new LinkedHashMap<>();
    static {
        String[][] pairs = {{"memoryTier","Speicherklasse (0–4; kein exakter RAM)"},{"maxViewDistance","Maximale Gerätesichtweite"},{"requestedViewDistance","Angeforderte Sichtweite"},
            {"guiScale","GUI-Skalierung (inkl. negativer Bedrock-Werte)"},{"platformType","Plattformtyp (Protokollwert)"},{"loginGraphicsMode","Grafikmodus beim Login"},{"currentGraphicsMode","Grafikmodus aktuell (falls verfügbar)"},{"clientSideChunkGen","Client-Chunkgenerierung möglich"},
            {"bedrockProtocolVersion","Bedrock-Protokoll"},{"joinAddress","Tatsächliche Join-Adresse"},{"joinPort","Join-Port"},{"loginInputMode","Eingabe beim Login"},
            {"deviceIdFormat","Geräte-ID-Format"},{"deviceIdFormatExpected","Dokumentiertes Format"},{"deviceIdFormatValid","Format stimmt überein (nur Hinweis)"},
            {"platformUserId","Platform User ID (experimentell)"},{"profileHash","Persona-Profilhash (veränderlich)"},{"skinId","Skin-ID (schwaches Signal)"},
            {"personaSkin","Persona-Skin"},{"premiumSkin","Premium-Skin"},{"skinColor","Skin-Farbwert"},{"skinImageWidth","Skinbreite"},{"skinImageHeight","Skinhöhe"},
            {"capeId","Cape-ID"},{"capeOnClassicSkin","Cape auf klassischem Skin"},{"trustedSkin","TrustedSkin (Clientmeldung)"},{"overrideSkin","OverrideSkin (Clientmeldung)"},
            {"partyId","Party-ID (optional)"},{"isPartyLeader","Party-Leitung"},{"filterProfanity","Bedrock-Sprachfilter"},{"clientEditorConnectionIntent","Editor-Verbindungsabsicht"},{"clientIsEditorCapable","Editor-Unterstützung"},
            {"javaBrand","Java-Client-Brand"},{"javaProtocol","Java-Protokoll"},{"locale","Client-Sprache"},{"chatVisibility","Chat-Anzeige"},{"chatColors","Chatfarben"},
            {"mainHand","Haupthand"},{"skinParts","Aktive Skin-Layer (Bitmaske)"},{"textFiltering","Java-Textfilter"},{"serverListings","Serverlisten-Freigabe"},{"particleVisibility","Partikel-Anzeige"},
            {"channelCount","Registrierte Kanäle"},{"channelNamespaces","Kanal-Namensräume"},{"channelSignature","Kanal-Signatur (SHA-256; veränderlich)"},
            {"linkedJavaUuid","Floodgate: offiziell verknüpfte Java-UUID"},{"linkedJavaName","Floodgate: verknüpfter Java-Name"},
            {"geyserVersion","Geyser-Version (falls bereitgestellt)"},{"schemaVersion","Payload-Schema"},{"snapshotPhase","Erfassungsphase"},{"collectionSource","Datenquelle"},
            {"metadataTrust","Vertrauenshinweis"},{"observedAt","Letzte Zusatzdatenerfassung (Zeitstempel)"},{"availableFields","Felder im letzten Snapshot"}};
        for(String[] pair:pairs) LABELS.put(pair[0],pair[1]);
    }
    static Map<String,String> from(Properties p) {
        Map<String,String> result=new LinkedHashMap<>();
        for(String key:LABELS.keySet()) {
            String value=p.getProperty(key);
            if(value==null||value.isBlank()) continue;
            value=key.equals("availableFields")?value.substring(0,Math.min(value.length(),4096)):BedrockMetadata.clean(value);
            if(valid(key,value)) result.put(key,value);
        }
        return result;
    }
    private static boolean valid(String key,String value) {
        if(key.equals("memoryTier")) return number(value,0,4);
        if(key.equals("maxViewDistance")||key.equals("requestedViewDistance")) return number(value,1,1024);
        if(key.equals("joinPort")) return number(value,1,65535);
        if(Set.of("clientSideChunkGen","personaSkin","premiumSkin","capeOnClassicSkin","trustedSkin","overrideSkin","isPartyLeader","filterProfanity","clientIsEditorCapable","chatColors","textFiltering","serverListings").contains(key)) return value.equals("true")||value.equals("false");
        return true;
    }
    private static boolean number(String value,int minimum,int maximum) { try { int n=Integer.parseInt(value); return n>=minimum&&n<=maximum; } catch(Exception e) { return false; } }
    static String encode(Map<String,String> map) {
        Properties p=new Properties(); map.forEach((k,v)->{if(LABELS.containsKey(k)&&v!=null) p.setProperty(k,k.equals("availableFields")?v.substring(0,Math.min(v.length(),4096)):BedrockMetadata.clean(v));});
        try { ByteArrayOutputStream bytes=new ByteArrayOutputStream(); p.store(bytes,null); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()); }
        catch(Exception e) { return ""; }
    }
    static Map<String,String> decode(String value) {
        if(value==null||value.isBlank()||value.length()>65536) return new LinkedHashMap<>();
        try { Properties p=new Properties(); p.load(new ByteArrayInputStream(Base64.getUrlDecoder().decode(value))); return from(p); }
        catch(Exception e) { return new LinkedHashMap<>(); }
    }
    static long observed(Map<String,String> map) { try { return Long.parseLong(map.getOrDefault("observedAt","0")); } catch(Exception e) { return 0; } }
    static boolean merge(Map<String,String> into,Map<String,String> incoming) {
        if(incoming.isEmpty()||observed(incoming)<observed(into)) return false;
        Map<String,String> before=new LinkedHashMap<>(into);
        into.putAll(incoming);
        into.put("availableFields",incoming.getOrDefault("availableFields",String.join(",",incoming.keySet().stream().filter(k->!Set.of("observedAt","availableFields").contains(k)).toList())));
        return !before.equals(into);
    }
    static boolean materialChange(Map<String,String> before,Map<String,String> after) {
        for(String key:List.of("memoryTier","maxViewDistance","platformType","loginGraphicsMode","profileHash","skinId","platformUserId","javaBrand","channelSignature"))
            if(!Objects.equals(before.get(key),after.get(key))) return true;
        return before.isEmpty()&&!after.isEmpty();
    }
    static List<String> lines(Map<String,String> map) {
        List<String> lines=new ArrayList<>();
        Set<String> available=new HashSet<>(Arrays.asList(map.getOrDefault("availableFields","").split(",")));
        for(String key:LABELS.keySet()) {
            if(key.equals("availableFields")||key.equals("observedAt")||!map.containsKey(key)) continue;
            String value=map.get(key);
            if(value.equals("true")) value="Ja"; else if(value.equals("false")) value="Nein";
            if(key.equals("memoryTier")) value += " / " + List.of("Sehr niedrig","Niedrig","Mittel","Hoch","Sehr hoch").get(Integer.parseInt(map.get(key)));
            lines.add(LABELS.get(key)+": "+value+(!available.isEmpty()&&!available.contains(key)?" [historisch]":""));
        }
        if(lines.isEmpty()) lines.add("Noch keine Zusatzdaten – erneuter Beitritt mit aktualisierter Brücke erforderlich.");
        return lines;
    }
    static Map<String,String> javaOptions(Player player) {
        Properties p=new Properties();
        option(player,ClientOption.LOCALE,"locale",p); option(player,ClientOption.VIEW_DISTANCE,"requestedViewDistance",p);
        option(player,ClientOption.CHAT_VISIBILITY,"chatVisibility",p); option(player,ClientOption.CHAT_COLORS_ENABLED,"chatColors",p);
        option(player,ClientOption.MAIN_HAND,"mainHand",p); option(player,ClientOption.TEXT_FILTERING_ENABLED,"textFiltering",p);
        option(player,ClientOption.ALLOW_SERVER_LISTINGS,"serverListings",p); option(player,ClientOption.PARTICLE_VISIBILITY,"particleVisibility",p);
        try { var parts=player.getClientOption(ClientOption.SKIN_PARTS); if(parts!=null) p.setProperty("skinParts",Integer.toString(parts.getRaw())); } catch(RuntimeException|LinkageError ignored) { }
        BedrockMetadata.put(p,"javaBrand",ClientProbeDiagnostics.brand(player));
        Object connection=BedrockMetadata.call(player,"getConnection");
        String protocol=BedrockMetadata.read(connection,"getProtocolVersion","protocolVersion");
        if(protocol.isBlank()) protocol=BedrockMetadata.read(player,"getProtocolVersion");
        BedrockMetadata.put(p,"javaProtocol",protocol);
        p.setProperty("observedAt",Long.toString(System.currentTimeMillis())); p.setProperty("collectionSource","paper-client-options");
        p.setProperty("snapshotPhase","SESSION"); p.setProperty("metadataTrust","client-reported; no hardware identifier");
        return from(p);
    }
    private static <T> void option(Player player,ClientOption<T> option,String key,Properties p) {
        try { Object value=player.getClientOption(option); if(value!=null) BedrockMetadata.put(p,key,String.valueOf(value)); } catch(RuntimeException|LinkageError ignored) { }
    }
    static List<String> overlap(Map<String,String> one,Map<String,String> two,boolean sameEdition) {
        List<String> matches=new ArrayList<>();
        List<String> keys=sameEdition ? List.of("platformUserId","profileHash","skinId","memoryTier","maxViewDistance","platformType","loginGraphicsMode","javaBrand","channelSignature","mainHand","particleVisibility") : List.of();
        for(String key:keys) {
            String value=one.getOrDefault(key,"");
            if(!value.isBlank()&&!Set.of("0","00000000-0000-0000-0000-000000000000").contains(value)&&value.equals(two.get(key))) matches.add(LABELS.get(key));
        }
        return matches;
    }
    static String channelSignature(Collection<String> channels) {
        if(channels==null||channels.isEmpty()) return "";
        try { String text=String.join("\n",new TreeSet<>(channels)); return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { return ""; }
    }
}
