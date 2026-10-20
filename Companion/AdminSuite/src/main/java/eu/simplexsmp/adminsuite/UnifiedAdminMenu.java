package eu.simplexsmp.adminsuite;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import java.util.*;

/** One navigation tree for inventory and Floodgate forms; existing storage and actions are retained. */
final class UnifiedAdminMenu {
    private final SimplexAdminSuite plugin;
    private record Link(String group, int slot, String title, String detail, Material icon, String permission) {}
    private static final List<Link> LINKS = List.of(
        l("Spieler",10,"Online-Spieler","Schnellaktionen für aktive Spieler",Material.PLAYER_HEAD,"admin"),
        l("Spieler",12,"Spieler-Datenbank","Gespeicherte Profile, Suche, Rechte und Spielerkontrolle",Material.BOOKSHELF,"admin"),
        l("Spieler",30,"Spielerdaten & Statistiken","NBT, Spielzeit, Hunger und letzte 24 Stunden",Material.WRITABLE_BOOK,"playerdata"),
        l("Spieler",26,"Alternative Accounts","Geräte, Identitäten und Verknüpfungen",Material.SPYGLASS,"alts"),
        l("Moderation",11,"Reports","Meldungen, Beweise und Bearbeitungsstatus",Material.WRITABLE_BOOK,"reports"),
        l("Moderation",29,"Anti-Spam","Filter, Limits und Protokoll",Material.SHIELD,"antispam"),
        l("Moderation",44,"KI & Moderation","Optionale KI, Limits und bestätigte Vorschläge",Material.COMMAND_BLOCK,"ai.admin"),
        l("Beobachtung",19,"AdminSpy umschalten","Öffentlichen Chat und private Nachrichten beobachten",Material.ENDER_EYE,"adminspy"),
        l("Beobachtung",21,"Stealth-Einstellungen","Join/Leave und Sichtbarkeit konfigurieren",Material.ENDER_PEARL,"stealth"),
        l("Server",15,"Server-Steuerung","Globale Chat- und Serverfunktionen",Material.REDSTONE_BLOCK,"admin"),
        l("Sicherheit",17,"VPN-/Proxy-Schutz","Whitelist, Provider und Protokoll",Material.SHIELD,"vpn.view"),
        l("Sicherheit",20,"Admin-Sicherheit","Passwortschutz und Sitzungen",Material.TRIPWIRE_HOOK,"security"),
        l("Server",23,"AFK-System","Automatische Abwesenheit und Entfernung",Material.CLOCK,"afk"),
        l("Server",25,"Einsatzleitstelle","Slowmode, Lockdown, Duty, Watchlist und Diagnose",Material.REDSTONE_TORCH,"operations"),
        l("Server",31,"Wartungsmodus","Serverzugang verwalten",Material.IRON_DOOR,"maintenance"),
        l("Server",39,"Lag-Inspector","Bereits geladene Chunks analysieren; nur OP",Material.SPYGLASS,"powertools"),
        l("Server",42,"Aktivitätsintelligenz","AFK- und Punktebewertung",Material.RECOVERY_COMPASS,"afk.admin"),
        l("Server",14,"Status, Audit & Integrationen","LiteBans, Floodgate und Verbindungen",Material.MAP,"admin"),
        l("Server",33,"Geyser/Floodgate-Updates","Offizielle Updates bereitstellen",Material.HOPPER,"updates"),
        l("Server",32,"Suite neu laden","Konfiguration, Daten und Timer neu laden",Material.CLOCK,"reload"),
        l("Welt",27,"Weltenverwaltung","Zeit, Wetter, Schlafquote und Gamerules",Material.GRASS_BLOCK,"worlds"),
        l("Welt",28,"Server-Warps","Erstellen, sortieren und verwalten",Material.COMPASS,"warps.admin"),
        l("Welt",37,"Welten-Trimmer","Scan, Countdown, Verlauf und Recovery; nur OP",Material.NETHERITE_AXE,"admin"),
        l("Community",24,"Startnachrichten","Öffentliche englische Begrüßungen",Material.FIREWORK_ROCKET,"startmessages"),
        l("Community",34,"Serverregeln","Versionen und Zustimmungen",Material.BOOK,"rules.admin"),
        l("Community",35,"Umfragen & Votes","Abstimmungen und Ergebnisse",Material.PAPER,"polls.admin"),
        l("Community",36,"Simplex Points","Ränge, Punkte und Leaderboard",Material.EXPERIENCE_BOTTLE,"points.admin"),
        l("Community",40,"Discord-Statusdesign","Texte, Farben und dynamische Serverwerte",Material.MAP,"discord.design"),
        l("Community",41,"Fun & Community","Sounds, Partikel und Statistik",Material.SLIME_BALL,"fun.admin"),
        l("Community",43,"Server-Scoreboard","Titel, Zeilen, Welten und Spieltage",Material.ITEM_FRAME,"scoreboard.admin"),
        l("Team",13,"Staff-Arbeitsbereich","Nachrichten, Team und LuckPerms",Material.LECTERN,"admin"),
        l("Team",38,"Erweiterte Werkzeuge","Spielerkontrolle, Alt-Rechte, Aktionsverlauf und Status-Refresh",Material.NETHERITE_PICKAXE,"powertools"),
        l("Team",45,"Team-Dashboard","Aufgaben, Jobs und Arbeitsübersicht",Material.NETHER_STAR,"qol"),
        l("Team",46,"Staff-Aufgaben","Zuweisungen und Fortschritt",Material.WRITABLE_BOOK,"qol"),
        l("Team",47,"Zeitplaner","Geplante Aktionen",Material.CLOCK,"qol"),
        l("Team",48,"Vorfälle","Incidents verwalten",Material.REDSTONE_TORCH,"qol"),
        l("Team",49,"Server-Gesundheit","Health und Betriebszustand",Material.GOLDEN_APPLE,"qol"),
        l("Team",50,"Makros","Wiederkehrende Abläufe",Material.COMMAND_BLOCK,"qol"),
        l("Team",51,"Massenaktionen","Bulk-Werkzeuge",Material.CHEST,"qol"),
        l("Team",52,"Timeline","Aktionsverlauf",Material.BOOK,"qol")
    );
    private static Link l(String g,int s,String t,String d,Material m,String p){return new Link(g,s,t,d,m,"simplexadminsuite."+p);}
    UnifiedAdminMenu(SimplexAdminSuite p){plugin=p;}
    private AdminUpdateMenu.Button b(String t,String d,Material m,Runnable a){return new AdminUpdateMenu.Button(t,d,m,a);}
    void open(Player p){
        if(!plugin.adminMenuAllowed(p,"suite.openmain"))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        items.add(b("Spieler","Online-Spieler, Profile, Daten und Rechte",Material.PLAYER_HEAD,()->category(p,"Spieler")));
        items.add(b("Moderation","Bans, Mutes, Reports und Chat-Schutz",Material.SHIELD,()->category(p,"Moderation")));
        items.add(b("Beobachtung","Vanish, Live-Watch und temporäre X-Ray-Köder",Material.SPYGLASS,()->category(p,"Beobachtung")));
        items.add(b("Sicherheit","Client-Prüfungen, optionale Detektoren, VPN und Admin-Zugang",Material.SHIELD,()->category(p,"Sicherheit")));
        items.add(b("Server","Betrieb, Diagnose und Integrationen",Material.REDSTONE_BLOCK,()->category(p,"Server")));
        items.add(b("Welt","Gamerules, Warps und Welten-Trimmer",Material.GRASS_BLOCK,()->category(p,"Welt")));
        items.add(b("Community","Willkommen, Regeln, Discord und Punkte",Material.FIREWORK_ROCKET,()->category(p,"Community")));
        items.add(b("Team","Aufgaben, Zeitpläne und Admin-Werkzeuge",Material.LECTERN,()->category(p,"Team")));
        if(plugin.appApi!=null && plugin.adminAccess.master(p)) items.add(b("Simplex Panel verbinden","Optionale HTTPS-App-API und widerrufbare Zugangsschlüssel",Material.TRIPWIRE_HOOK,()->plugin.appApi.menu(p)));
        items.add(b("Schließen","Menü verlassen",Material.BARRIER,p::closeInventory));
        plugin.adminUpdate.show(p,"Admin-Zentrale",items,0);
    }
    void category(Player p,String group){
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        if(group.equals("Moderation") && (plugin.has(p,"simplexadminsuite.bans")||plugin.has(p,"simplexadminsuite.mutes")))
            items.add(b("Bans & Mutes prüfen","LiteBans, Simplex und Minecraft • Quellen getrennt",Material.IRON_BARS,()->plugin.moderationOverview.select(p)));

        for(Link link:LINKS)if(link.group.equals(group)&&plugin.has(p,link.permission)){
            if((link.slot==37||link.slot==39)&&!p.isOp())continue;
            items.add(b(link.title,link.detail,link.icon,()->{if(plugin.has(p,link.permission))plugin.openUnifiedAction(p,link.slot);}));
        }
        if(group.equals("Moderation")&&plugin.has(p,"simplexadminsuite.restrictions"))items.add(b("Einschränkungsvorlagen","Text, PvP, Interaktionen • Dauer & Vererbung",Material.WRITABLE_BOOK,()->plugin.moderationControl.players(p,"",true)));
        if(group.equals("Server")&&plugin.has(p,"simplexadminsuite.commands.admin"))items.add(b("Befehlsverwaltung","Spielerbefehle sperren • Vorschläge nach Berechtigung bereinigen",Material.COMMAND_BLOCK,()->plugin.playerCommands.open(p)));
        if(group.equals("Server")&&plugin.has(p,"simplexadminsuite.settings.advanced"))items.add(b("Suite-Einstellungen","Bestehende Einstellungen durchsuchen und nativ bearbeiten",Material.COMPARATOR,()->plugin.moderationControl.settings(p,"")));
        if(group.equals("Spieler")&&plugin.adminAccess.master(p))items.add(b("Adminrollen & Einzelrechte","Bestehende Rollen und Berechtigungen verwalten",Material.TRIPWIRE_HOOK,()->plugin.adminUpdate.players(p,true)));
        if(group.equals("Spieler")&&plugin.has(p,"simplexadminsuite.nickname"))items.add(b(plugin.playerText(p,"Spielernamen & Diagnose","Player names & diagnostics"),plugin.playerText(p,"LuckPerms, AFK und sichtbare Nicknames","LuckPerms, AFK and display nicknames"),Material.NAME_TAG,()->plugin.qualityOfLife.menu(p)));
        if(group.equals("Beobachtung")&&plugin.has(p,"simplexadminsuite.bluemap"))items.add(b("BlueMap",plugin.playerText(p,"Dauerhaft verbergen oder mit Vanish koppeln","Hide permanently or follow vanish"),Material.MAP,()->plugin.qualityOfLife.mapMenu(p)));
        if(group.equals("Sicherheit")&&plugin.securityHub!=null&&plugin.has(p,"simplexadminsuite.clientsecurity.admin"))items.add(b("Sicherheitszentrale","Ausnahmen, private Protokolle, Alt-Alerts, Standortdiagnose und Neustarts",Material.COMPARATOR,()->plugin.securityHub.menu(p)));
        if(group.equals("Sicherheit")&&plugin.has(p,"simplexadminsuite.clientcheck"))items.add(b(plugin.playerText(p,"Client-Prüfung","Client probes"),plugin.playerText(p,"Übersetzungs-/Tastenhinweise • nur Java","Translation/keybind signals • Java only"),Material.SHIELD,()->plugin.qualityOfLife.probes.menu(p)));
        if(group.equals("Sicherheit")&&plugin.has(p,"simplexadminsuite.clientsecurity.admin")){
            items.add(b(plugin.playerText(p,"Optionale Sicherheitsdetektoren","Optional security detectors"),plugin.playerText(p,"Fingerprint, Verschleierung, Klickmuster und Interaktionen","Fingerprint, concealment, click patterns and interactions"),Material.SHIELD,()->plugin.detectors.home(p)));
            items.add(b(plugin.playerText(p,"Client-Daten & Kanäle","Client data & channels"),plugin.playerText(p,"Erfassung, Diagnose und Crossplay-Einstellungen","Capture, diagnostics and crossplay settings"),Material.NAME_TAG,()->plugin.openClientSecuritySettings(p)));
        }
        if(group.equals("Beobachtung")){
            if(plugin.has(p,"simplexadminsuite.vanish"))items.add(b("Vanish umschalten","Unsichtbarkeit mit wiederherstellbarem Flugzustand",Material.ENDER_EYE,()->plugin.adminRuntime.toggle(p)));
            if(plugin.has(p,"simplexadminsuite.watch")){
                items.add(b("Live-Watch starten","Spieler auswählen und Aktionen beobachten",Material.SPYGLASS,()->plugin.adminUpdate.players(p,false)));
                items.add(b("Live-Watch beenden","Actionbar freigeben",Material.BARRIER,()->plugin.adminRuntime.watch(p,null)));
            }
            if(plugin.has(p,"simplexadminsuite.xraybait"))items.add(b("X-Ray-Köder","Nur Fake-Blöcke • 5–60 Sekunden • keine Weltänderung",Material.DIAMOND_ORE,()->plugin.xrayBait.menu(p)));
        }
        if(group.equals("Community")&&plugin.has(p,"simplexadminsuite.shop.admin"))items.add(b("Simplex-Shop","Artikel, Preise, Bestand und Kauflimits",Material.CHEST,()->plugin.shop.adminHome(p)));
        if(group.equals("Moderation")&&plugin.has(p,"simplexadminsuite.delivery.admin"))items.add(b("Teamnachrichten","Offline vormerken • Vorschau • Sprache • Lesebestätigung",Material.WRITABLE_BOOK,()->plugin.deliveries.adminHome(p)));
        if(group.equals("Team")&&plugin.adminAccess.master(p))items.add(b("Branding & Präfix","Öffentlichen Anzeigenamen und Nachrichtenpräfix ändern",Material.NAME_TAG,()->plugin.openBranding(p)));
        if(group.equals("Community")&&plugin.has(p,"simplexadminsuite.community.admin"))items.add(b("Spielerprofile, Feedback & Ankündigungen","Eigene Profile • Feedback beantworten • DE/EN-Nachrichten mit Ablauf",Material.WRITABLE_BOOK,()->plugin.community.adminHome(p)));
        if(group.equals("Community")&&plugin.has(p,"simplexadminsuite.discord.links"))items.add(b("Discord-Verknüpfungen","Konten mit Seiten verwalten und entknüpfen",Material.TRIPWIRE_HOOK,()->plugin.accountLinks.openAdmin(p)));
        if(group.equals("Community")&&plugin.has(p,"simplexadminsuite.discordstatus"))items.add(b("Live-Serverstatistiken","Discord-Kanal, Aktualisierung und optionale KI-Prüfung",Material.COMPARATOR,()->plugin.discordRelay().openStatisticsSettings(p)));
        if(group.equals("Community")&&plugin.has(p,"simplexadminsuite.settings"))items.add(b("Discord-Einladungslink","Öffentlichen /discord-Link ändern",Material.PAPER,()->plugin.discordInvite.edit(p)));
        for(String setting:AdminUpdateMenu.SETTINGS.stream().map(AdminUpdateMenu.Setting::group).distinct().toList()){
            String destination=switch(setting){case "Willkommen","Discord & Bilder"->"Community";case "Vanish & Watch"->"Beobachtung";case "Admin-Alarme","Konsole (Hauptadmin)"->"Team";default->"Server";};
            if(group.equals(destination)&&plugin.has(p,"simplexadminsuite.settings")&&(!setting.startsWith("Konsole")||plugin.adminAccess.master(p)))
                items.add(b(setting+" • Einstellungen","Anzeige und Verhalten konfigurieren",setting.equals("Willkommen")?Material.FIREWORK_ROCKET:setting.startsWith("Konsole")?Material.COMMAND_BLOCK:setting.equals("Verifizierung")?Material.LIME_DYE:Material.MAP,()->plugin.adminUpdate.settings(p,setting)));
        }
        items.add(b("Zurück","Admin-Zentrale",Material.OAK_DOOR,()->open(p)));
        plugin.adminUpdate.show(p,group,items,0);
    }
}
