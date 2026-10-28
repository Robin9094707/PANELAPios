package eu.simplexsmp.adminsuite;
import java.util.*;
/** German labels are presentation only; stable permission identifiers remain unchanged. */
final class AdminPermissionText {
    private static final Map<String,String> NAMES=Map.ofEntries(
        Map.entry("admin","Admin-Zentrale öffnen"),Map.entry("bans","Bannübersicht öffnen"),Map.entry("mutes","Stummschaltungen verwalten"),Map.entry("warns","Verwarnungen verwalten"),
        Map.entry("action.kick","Spieler kicken"),Map.entry("action.ban","Dauerhaft bannen"),Map.entry("action.tempban","Zeitweise bannen"),Map.entry("action.unban","Account-Bann aufheben"),Map.entry("action.mute","Dauerhaft stummschalten"),Map.entry("action.tempmute","Zeitweise stummschalten"),Map.entry("action.unmute","Stummschaltung aufheben"),
        Map.entry("history","Spielerverlauf ansehen"),Map.entry("reports","Spielermeldungen bearbeiten"),Map.entry("notes","Teamnotizen bearbeiten"),Map.entry("staffchat","Teamchat verwenden"),Map.entry("watch","Spieler live beobachten"),
        Map.entry("alts","Alternative Accounts ansehen"),Map.entry("alts.cascade","Strafen auf Alt-Accounts übertragen"),Map.entry("ultraban","Alt-Accounts und Identitäten bannen"),Map.entry("ultraban.unban","Alt-Account-Banngruppen aufheben"),Map.entry("alt-rights","Rechte an Alt-Accounts vererben"),
        Map.entry("settings","Suite-Einstellungen bearbeiten"),Map.entry("settings.advanced","Erweiterte Suite-Einstellungen bearbeiten"),Map.entry("reload","Suite neu laden"),Map.entry("maintenance","Wartungsmodus schalten"),Map.entry("restart","Serverneustart planen"),Map.entry("operations","Server-Einsatzleitstelle öffnen"),Map.entry("restrictions","Spielerrechte einschränken"),Map.entry("moderation.control","Moderationsregeln verwalten"),
        Map.entry("teleport","Admin-Teleport verwenden"),Map.entry("freeze","Spieler einfrieren"),Map.entry("vanish","Unsichtbarkeit verwenden"),Map.entry("invsee","Inventare ansehen und bearbeiten"),Map.entry("playerdata","Gespeicherte Spielerdaten bearbeiten"),Map.entry("data","Profildaten verwalten"),Map.entry("export","Spielerdaten exportieren"),
        Map.entry("gamemode","Spielmodus ändern"),Map.entry("worlds","Welten verwalten"),Map.entry("security","Admin-Sicherheit öffnen"),Map.entry("powertools","Erweiterte Adminwerkzeuge"),Map.entry("qol","Teamaufgaben und Zeitpläne"),Map.entry("adminspy","Chat-Beobachtung verwenden"),Map.entry("stealth","Unsichtbare Adminaktionen"),Map.entry("chattoggle","Öffentlichen Chat schalten"),Map.entry("chatclear","Öffentlichen Chat leeren"),Map.entry("antispam","Chat-Schutz verwalten"),Map.entry("clientcheck","Client-Prüfung starten"),Map.entry("vpn.view","VPN-Schutz ansehen"),Map.entry("updates","Plugin-Updates verwalten"),
        Map.entry("bluemap","Sichtbarkeit auf BlueMap steuern"),
        Map.entry("afk","AFK-System verwalten"),
        Map.entry("afk.admin","Aktivitätsbewertung verwalten"),
        Map.entry("ai.admin","KI-Verwaltung und Vorschläge"),
        Map.entry("ai.unlimited","KI-Anfragen ohne Tageslimit"),
        Map.entry("alts.identities","Geräte- und Identitätsdaten ansehen"),
        Map.entry("alts.link","Alt-Accounts verknüpfen und trennen"),
        Map.entry("announcements","Serverankündigungen senden"),
        Map.entry("check","Spielerdiagnose ansehen"),
        Map.entry("check.identifiers","Spielerkennungen und Rohdaten ansehen"),
        Map.entry("client.channels","Client-Kanäle ansehen"),
        Map.entry("clientsecurity.admin","Client-Sicherheit verwalten"),
        Map.entry("commandlock","Befehle für Spieler sperren"),
        Map.entry("commandspy","Spielerbefehle beobachten"),
        Map.entry("discord.design","Discord-Status gestalten (Hauptadmin)"),
        Map.entry("discordstatus","Discord-Status und Statistiken verwalten"),
        Map.entry("duty","Teamdienst umschalten"),
        Map.entry("fun.admin","Spaßfunktionen verwalten"),
        Map.entry("homes","Spieler-Homes verwalten"),
        Map.entry("laginspector","Lag-Ursachen untersuchen (Operator)"),
        Map.entry("nickname","Spielernamen bearbeiten"),
        Map.entry("points.admin","Punkte und Ranglisten verwalten"),
        Map.entry("polls.admin","Umfragen erstellen und verwalten"),
        Map.entry("powertools.history","Erweiterten Aktionsverlauf ansehen"),
        Map.entry("powertools.inventory","Erweiterte Inventarwerkzeuge verwenden"),
        Map.entry("powertools.moderation","Erweiterte Moderationswerkzeuge verwenden"),
        Map.entry("powertools.player","Erweiterte Spielersteuerung verwenden"),
        Map.entry("qol.bulk","Massenaktionen ausführen"),
        Map.entry("qol.health","Server-Gesundheit ansehen"),
        Map.entry("qol.incidents","Teamvorfälle verwalten"),
        Map.entry("qol.macros","Aktionsvorlagen verwalten"),
        Map.entry("qol.scheduler","Teamaktionen planen"),
        Map.entry("qol.tasks","Teamaufgaben verwalten"),
        Map.entry("qol.timeline","Team-Aktionsverlauf ansehen"),
        Map.entry("rights","Spielerrechte verwalten"),
        Map.entry("rules.admin","Serverregeln bearbeiten"),
        Map.entry("scoreboard.admin","Scoreboard und Spieltage bearbeiten"),
        Map.entry("slowmode","Chat-Intervall begrenzen"),
        Map.entry("snapshots","Spieler-Sicherungen verwalten"),
        Map.entry("startmessages","Begrüßungsnachrichten bearbeiten"),
        Map.entry("trimmer","Weltdaten bereinigen (Operator)"),
        Map.entry("ultraban.reset","Alt-Account-Identitäten zurücksetzen"),
        Map.entry("ultraban.view","Alt-Account-Banngruppen ansehen"),
        Map.entry("utilities","Server-Hilfswerkzeuge verwenden"),
        Map.entry("vpn.admin","VPN-Schutz konfigurieren"),
        Map.entry("vpn.check","VPN-Prüfung ausführen"),
        Map.entry("vpn.logs","VPN-Protokolle ansehen"),
        Map.entry("vpn.manage","VPN-Regeln bearbeiten"),
        Map.entry("vpn.whitelist","VPN-Ausnahmen verwalten"),
        Map.entry("warps.admin","Server-Warps verwalten"),
        Map.entry("watchlist","Spieler-Beobachtungsliste verwalten"),
        Map.entry("xraybait","Temporäre X-Ray-Köder setzen"),
        Map.entry("playerdata.edit","Gespeicherte Spielerdaten bearbeiten"),
        Map.entry("devices.models","Gerätemodelle sperren und freigeben"),
        Map.entry("discord.links","Discord-Kontoverknüpfungen verwalten"),
        Map.entry("community.admin","Profile, Feedback und Community verwalten"),
        Map.entry("commands.admin","Befehlsregeln verwalten")
    );
    private static final Properties DETAILS=details();
    private static Properties details(){Properties result=new Properties();try(var stream=AdminPermissionText.class.getResourceAsStream("/admin-permission-descriptions.properties")){if(stream!=null)result.load(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));}catch(java.io.IOException e){throw new IllegalStateException(e);}return result;}
    static String key(String node){return node.replace("simplexadminsuite.","");}
    static String group(String node){String k=key(node);if(k.startsWith("action.")||Set.of("bans","mutes","warns","reports","restrictions","moderation.control").contains(k))return "Moderation";if(k.contains("alt")||k.contains("ultraban"))return "Alt-Accounts";if(k.startsWith("menu."))return "Erweiterte Menüregeln";if(k.contains("world")||k.contains("warp")||k.contains("teleport"))return "Welt & Teleport";if(k.contains("client")||k.contains("vpn")||k.contains("security")||k.contains("device"))return "Sicherheit";if(k.contains("discord")||k.contains("community")||k.contains("shop")||k.contains("points")||k.contains("poll")||k.contains("scoreboard"))return "Community & Integrationen";return "Server & Team";}
    static String name(String node){String k=key(node);return NAMES.getOrDefault(k,group(node)+" · "+human(k));}
    static String description(String node){String k=key(node);if(k.equals("bans"))return "Öffnet die Bannverwaltung. Kicken, Zeitbann und Dauerbann werden zusätzlich einzeln geprüft.";if(k.equals("action.tempban"))return "Erlaubt einen Account-Zeitbann innerhalb des eingestellten Rollenlimits. Kein dauerhafter Bann.";if(k.equals("alts.cascade")||k.equals("ultraban"))return "Kann mehrere Konten oder Identitäten betreffen. Erfordert zusätzlich die Freigabe für Alt-Account-Banns.";if(k.startsWith("menu."))return "Zusätzliche Menüfreigabe. Fachliche Aktionsrechte und Bannlimits gelten weiterhin.";return DETAILS.getProperty(k,name(node)+" erlauben oder ausdrücklich sperren.")+"  Eine Sperre hat Vorrang vor geerbten LuckPerms-Rechten; Hauptoperatoren bleiben geschützt.";}
    static String human(String key){String value=key;String[][] words={{"open"," öffnen "},{"settings","Einstellungen"},{"admin","Verwaltung"},{"player","Spieler"},{"permissions","Rechte"},{"history","Verlauf"},{"view","Ansehen"},{"delete","Löschen"},{"create","Erstellen"},{"update","Bearbeiten"},{"read","Lesen"},{"write","Schreiben"},{"models","Gerätemodelle"},{"channels","Kanäle"},{"commands","Befehle"},{"diagnostics","Diagnose"},{"alerts","Hinweise"},{"log","Protokoll"},{"link","Verknüpfung"},{"identities","Identitäten"},{"delivery","Teamnachrichten"},{"connection","Verbindungsdiagnose"},{"announcements","Ankündigungen"},{"nickname","Spitznamen"},{"freeze","Einfrieren"},{"use","Verwenden"},{"bypass","Ausnahme"}};for(String[] word:words)value=value.replace(word[0],word[1]);return value.replace('.',' ').replace('_',' ').trim();}
}
