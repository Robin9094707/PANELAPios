package eu.simplexsmp.adminsuite;

import java.util.*;

/** Explicit UI translations. Admin and Discord interfaces retain their own language. */
final class PlayerLanguages {
  private static final LinkedHashMap<String, String> TEXT = new LinkedHashMap<>();

  private static void add(String en, String de) {
    TEXT.put(en, de);
  }

  static {
    add("Simplex Points", "Simplex-Punkte");
    add("Simplex Point progress", "Fortschritt bei den Simplex-Punkten");
    add("Your current input pattern does not count as active gameplay. You are marked AFK and Simplex Point progress is paused until varied, active play resumes.", "Dein aktuelles Eingabemuster zählt nicht als aktives Spielen. Du wurdest als AFK markiert. Dein Fortschritt bei den Simplex-Punkten pausiert, bis du wieder abwechslungsreich aktiv spielst.");
    add("Active gameplay was detected again. Simplex Point progress has resumed.", "Du spielst wieder aktiv. Dein Fortschritt bei den Simplex-Punkten läuft weiter.");
    add("Your Simplex Points balance was updated by staff. New balance:", "Dein Punktestand wurde vom Team geändert. Neuer Punktestand:");
    add("Balance:", "Guthaben:");
    add("Rank:", "Rang:");
    add("Active play time:", "Aktive Spielzeit:");
    add("Use /points top", "Nutze /points top");
    add("to view the leaderboard.", "für die Rangliste.");
    add("No points have been earned yet.", "Es wurden noch keine Punkte verdient.");
    add("You earned", "Du hast");
    add("for active play time.", "für aktive Spielzeit erhalten.");
    add("Newcomer", "Neuling");
    add("Explorer", "Entdecker");
    add("Regular", "Stammspieler");
    add("Veteran", "Veteran");
    add("Legend", "Legende");
    add("First join:", "Erster Beitritt:");
    add("Last seen:", "Zuletzt gesehen:");
    add("Platform:", "Plattform:");
    add("Client language:", "Client-Sprache:");
    add("Current position:", "Aktueller Standort:");
    add("Logout position:", "Auslogg-Standort:");
    add("Playtime:", "Spielzeit:");
    add("Sessions:", "Sitzungen:");
    add("Points:", "Punkte:");
    add("World:", "Welt:");
    add("Server day", "Servertag");
    add("ING-Days:", "Spiel-Tage:");
    add("Tiny squeak", "Mini-Quietscher");
    add("Light fart", "Leichter Furz");
    add("Bubbly fart", "Blubberfurz");
    add("Medium fart", "Mittlerer Furz");
    add("Suspiciously wet fart", "Verdächtig feuchter Furz");
    add("Ridiculously long fart", "Unglaublich langer Furz");
    add("Trumpet fart", "Trompetenfurz");
    add("Thunder fart", "Donnerfurz");
    add("Ultra fart", "Ultra-Furz");
    add("Nuclear fart", "Nuklearfurz");
    add("Small burp", "Kleiner Rülpser");
    add("Loud burp", "Lauter Rülpser");
    add("Deep burp", "Tiefer Rülpser");
    add("Monster burp", "Monster-Rülpser");
    add("Fart messages and sounds from other players are now hidden.", "Furzmeldungen und Sounds anderer Spieler sind jetzt ausgeblendet.");
    add("Fart messages and sounds from other players are visible again.", "Furzmeldungen und Sounds anderer Spieler sind wieder sichtbar.");
    add("Burp messages and sounds from other players are now hidden.", "Rülpsmeldungen und Sounds anderer Spieler sind jetzt ausgeblendet.");
    add("Burp messages and sounds from other players are visible again.", "Rülpsmeldungen und Sounds anderer Spieler sind wieder sichtbar.");
    add("Your next fart is ready in", "Dein nächster Furz ist bereit in");
    add("Your next burp is ready in", "Dein nächster Rülpser ist bereit in");
    add("seconds.", "Sekunden.");
    add("The fart command is currently disabled by staff.", "Der Furzbefehl ist momentan vom Team deaktiviert.");
    add("The burp command is currently disabled by staff.", "Der Rülpsbefehl ist momentan vom Team deaktiviert.");
    add("You do not have permission to use /fart.", "Du darfst /fart nicht verwenden.");
    add("You do not have permission to use /burp.", "Du darfst /burp nicht verwenden.");
    add("You do not have permission to view Simplex Points.", "Du darfst die Simplex-Punkte nicht ansehen.");
    add("This command can only be used by a player.", "Dieser Befehl kann nur im Spiel verwendet werden.");
    add("Could not save your link. Please contact staff.", "Die Verknüpfung konnte nicht gespeichert werden. Bitte das Team informieren.");
    add("An account is already linked. Unlink it first.", "Ein Konto ist bereits verknüpft. Entknüpfe es zuerst.");
    add("Invalid or expired code. Use /link in Discord for a new code.", "Ungültiger oder abgelaufener Code. Mit /link in Discord erhältst du einen neuen Code.");
    add("Too many attempts. Please wait one minute.", "Zu viele Versuche. Bitte eine Minute warten.");
    add("No server warps are currently available.", "Es sind derzeit keine Server-Warps verfügbar.");
    add("Choose a destination.", "Wähle ein Ziel.");
    add("Page", "Seite");
    add("Click to teleport.", "Zum Teleportieren anklicken.");
    add("Use /warps to view available destinations.", "Mit /warps kannst du die verfügbaren Ziele ansehen.");
    add("The report system is currently disabled.", "Das Meldesystem ist momentan deaktiviert.");
    add("Report cancelled.", "Meldung abgebrochen.");
    add("You are blocked from using the report system. Reason:", "Du darfst das Meldesystem momentan nicht verwenden. Grund:");
    add("Your report was saved as report #", "Deine Meldung wurde gespeichert als Meldung Nr.");
    add("Thank you for helping the server.", "Danke für deine Hilfe für den Server.");
    add("You do not have permission to use the report system.", "Du darfst das Meldesystem nicht verwenden.");
    add("You do not have permission to use server warps.", "Du darfst Server-Warps nicht verwenden.");
    add("Available warps:", "Verfügbare Warps:");
    add("That server warp does not exist.", "Dieser Server-Warp existiert nicht.");
    add("Admin authentication is required before commands can be used.", "Vor der Befehlsnutzung ist die Admin-Anmeldung erforderlich.");
    add("Character", "Zeichen");
    add("Online:", "Online:");
    add("Unknown", "Unbekannt");
    add("Be respectful to every player and staff member.", "Behandle alle Spieler und Teammitglieder respektvoll.");
    add("Do not grief, steal, scam, or intentionally destroy other players' work.", "Griefe, stehle und betrüge nicht und zerstöre keine Bauwerke anderer Spieler.");
    add("Do not use cheats, exploits, duplication bugs, or unauthorized modifications.", "Verwende keine Cheats, Exploits, Duplizierungsfehler oder unerlaubten Modifikationen.");
    add("Do not spam, advertise, share prohibited links, or bypass the chat filter.", "Spamme nicht, mache keine Werbung, teile keine verbotenen Links und umgehe keine Chatfilter.");
    add("Follow staff instructions and report bugs instead of abusing them.", "Befolge die Anweisungen des Teams und melde Fehler, statt sie auszunutzen.");
    add("You are sending messages too quickly. Please slow down.", "Du sendest Nachrichten zu schnell. Bitte schreibe langsamer.");
    add("You are sending too many messages. Please wait a moment.", "Du sendest zu viele Nachrichten. Bitte warte kurz.");
    add("Please do not repeat the same message.", "Bitte wiederhole nicht dieselbe Nachricht.");
    add("Links are not allowed in public chat.", "Links sind im öffentlichen Chat nicht erlaubt.");
    add("You can reconnect in", "Du kannst dich wieder verbinden in");
    add("World trimming is scheduled. All players will be disconnected in", "Weltbereinigung geplant. Alle Spieler werden getrennt in");
    add("World trimming starts in", "Die Weltbereinigung beginnt in");
    add("The scheduled world trim has been cancelled.", "Die geplante Weltbereinigung wurde abgebrochen.");
    add("World trimming is starting now. The server will restart automatically when it is complete.", "Die Weltbereinigung beginnt jetzt. Danach startet der Server automatisch neu.");
    add("World maintenance is continuing after an operator safety override. The server remains locked.", "Die Weltwartung wird nach einer Sicherheitsentscheidung eines Operators fortgesetzt. Der Server bleibt gesperrt.");
    add("World maintenance is waiting for the initiating server operator to complete a safety decision.", "Die Weltwartung wartet auf eine Sicherheitsentscheidung des zuständigen Operators.");
    add("World maintenance is in progress.", "Die Weltwartung läuft.");
    add("Target:", "Ziel:");
    add("Progress:", "Fortschritt:");
    add("Estimated time remaining:", "Geschätzte Restzeit:");
    add("Please reconnect after the automatic server restart.", "Bitte verbinde dich nach dem automatischen Serverneustart erneut.");
    add("The server is currently under maintenance.", "Der Server befindet sich momentan im Wartungsmodus.");
    add("Server maintenance", "Serverwartung");
    add("Please try again later.", "Bitte versuche es später erneut.");
    add("The server will restart in", "Der Server startet neu in");
    add("Server restart in", "Serverneustart in");
    add("Server restart scheduled by staff.", "Das Team hat einen Serverneustart geplant.");
    add("Server restarting in", "Serverneustart in");
    add("Restarting in", "Neustart in");
    add("Server is restarting now.", "Der Server startet jetzt neu.");
    add("Please reconnect shortly", "Bitte verbinde dich gleich erneut");
    add("Server restart was cancelled by staff.", "Das Team hat den Serverneustart abgebrochen.");
    add("SERVER RESTART", "SERVERNEUSTART");
    add("RESTARTING NOW", "NEUSTART JETZT");
    add("You are permanently blocked by an identity-wide ban.", "Du bist durch eine kontenübergreifende Sperre dauerhaft blockiert.");
    add("Reference:", "Referenz:");
    add("This device has been blocked.", "Dieses Gerät wurde gesperrt.");
    add("Contact the server staff if you believe this is a mistake.", "Kontaktiere das Serverteam, wenn du einen Fehler vermutest.");
    add("This IP address has been blocked.", "Diese IP-Adresse wurde gesperrt.");
    add("You have been kicked for being AFK for too long.", "Du wurdest getrennt, weil du zu lange inaktiv warst.");
    add("You are now AFK.", "Du bist jetzt AFK.");
    add("You are no longer AFK.", "Du bist nicht mehr AFK.");
    add("Thank you. You accepted server rules version", "Danke. Du hast folgende Version der Serverregeln akzeptiert:");
    add("You may now continue playing.", "Du kannst jetzt weiterspielen.");
    add("Your rules acceptance could not be saved. Please contact staff.", "Deine Regelzustimmung konnte nicht gespeichert werden. Bitte das Team informieren.");
    add("The server rules changed before your confirmation. Please read the current version.", "Die Regeln wurden vor deiner Bestätigung geändert. Bitte lies die aktuelle Version.");
    add("I have read and agree to", "Ich habe gelesen und akzeptiere");
    add("server rules version", "Version der Serverregeln");
    add("You can select up to", "Du kannst bis zu");
    add("option(s).", "Antwort(en) auswählen.");
    add("The vote has ended.", "Die Abstimmung ist beendet.");
    add("Your vote was saved.", "Deine Stimme wurde gespeichert.");
    add("You already voted in this poll.", "Du hast an dieser Abstimmung bereits teilgenommen.");
    add("You cannot change your vote.", "Du kannst deine Stimme nicht ändern.");
    add("Select at least one option.", "Wähle mindestens eine Antwort.");
    add("This server vote has already ended.", "Diese Serverabstimmung ist bereits beendet.");
    add("There is no active server vote.", "Es läuft derzeit keine Serverabstimmung.");
    add("No votes", "Keine Stimmen");
    add("vote(s)", "Stimme(n)");
    add("voter(s), ends in", "Teilnehmer, endet in");
    add("Discord invite is currently unavailable.", "Die Discord-Einladung ist momentan nicht verfügbar.");
    add("Join our Discord community!", "Tritt unserer Discord-Community bei!");
    add("Invite code:", "Einladungscode:");
    add("Open Discord → Join a Server → enter the invite code.", "Öffne Discord → Server beitreten → gib den Einladungscode ein.");
    add("Done", "Fertig");
    add("Use /fart", "Nutze /fart");
    add("Use /burp", "Nutze /burp");
    add("VPN detected", "VPN erkannt");
    add("Please wait a moment before joining again. You were just blocked for VPN/proxy usage.", "Bitte warte kurz vor dem nächsten Beitritt. Du wurdest gerade wegen VPN-/Proxy-Nutzung gesperrt.");
    add("Please disable your VPN and try again.", "Bitte deaktiviere dein VPN und versuche es erneut.");
    add("VPNs, proxies, Tor or hosting networks are not allowed on SimplexSMP.", "VPNs, Proxys, Tor und Hosting-Netzwerke sind auf SimplexSMP nicht erlaubt.");
    add("If this is a mistake, contact Discord support.", "Wenn das ein Fehler ist, kontaktiere den Discord-Support.");
    add("VPN protection is temporarily unavailable.", "Der VPN-Schutz ist vorübergehend nicht verfügbar.");
    add("Please disable your VPN before joining.", "Bitte deaktiviere dein VPN vor dem Beitritt.");
    add("Failed to connect to the server. Connection timed out. Please try again later.", "Verbindung zum Server fehlgeschlagen. Zeitüberschreitung. Bitte versuche es später erneut.");
    add("You do not have permission to change the scoreboard.","Du darfst das Scoreboard nicht ändern.");
    add("Rule", "Regel");
    add("Choose up to", "Wähle bis zu");
    add("Click to select.", "Zum Auswählen anklicken.");
    add("Click to remove.", "Zum Entfernen anklicken.");
    add("Your Discord account has been unlinked.", "Dein Discord-Konto wurde entknüpft.");
    add("You are banned from this server.", "Du bist auf diesem Server gesperrt.");
    add("You are muted.", "Du bist stummgeschaltet.");
    add("Reason:", "Grund:");
    add("Remaining:", "Verbleibend:");
    add("Permanent", "Dauerhaft");
    add("permanent", "dauerhaft");
    add("You have been frozen by staff.", "Du wurdest vom Team eingefroren.");
    add("You are no longer frozen.", "Du bist nicht mehr eingefroren.");
    add("You have been healed and fed by staff.", "Du wurdest vom Team geheilt und gesättigt.");
    add(
        "Your game mode was changed to {gamemode} by staff.",
        "Dein Spielmodus wurde vom Team auf {gamemode} geändert.");
    add("has been temporarily banned by staff for", "wurde vom Team vorübergehend gesperrt für");
    add(
        "has been temporarily muted by staff for",
        "wurde vom Team vorübergehend stummgeschaltet für");
    add("has been banned by staff.", "wurde vom Team gesperrt.");
    add("has been unbanned by staff.", "wurde vom Team entsperrt.");
    add("has been muted by staff.", "wurde vom Team stummgeschaltet.");
    add("has been unmuted by staff.", "wurde vom Team wieder freigegeben.");
    add("has been removed by staff.", "wurde vom Team entfernt.");
    add("Welcome • Player verification", "Willkommen • Spieler-Verifizierung");
    add("Player verification", "Spieler-Verifizierung");
    add(
        "Please confirm that you are a real player.",
        "Bitte bestätige, dass du ein echter Spieler bist.");
    add(
        "Select 'I am not a robot' to unlock movement and chat.",
        "Wähle ‚Ich bin kein Roboter‘, um Bewegung und Chat freizuschalten.");
    add(
        "Welcome! Please confirm that you are not a robot to start playing.",
        "Willkommen! Bestätige bitte, dass du kein Roboter bist, um zu spielen.");
    add("I am not a robot", "Ich bin kein Roboter");
    add("Not yet", "Noch nicht");
    add("Select the green button to continue.", "Wähle die grüne Schaltfläche, um fortzufahren.");
    add("Click here to start playing!", "Klicke hier, um zu spielen!");
    add("Verified! Enjoy your time on the server.", "Verifiziert! Viel Spaß auf dem Server.");
    add(
        "Please complete player verification first.",
        "Bitte schließe zuerst die Spieler-Verifizierung ab.");
    add("Verification timed out.", "Zeit für die Verifizierung abgelaufen.");
    add("Server Rules", "Serverregeln");
    add("Server rules", "Serverregeln");
    add("Confirm Server Rules", "Serverregeln bestätigen");
    add("I accept the rules", "Ich akzeptiere die Regeln");
    add("Accept rules", "Regeln akzeptieren");
    add("I agree", "Ich stimme zu");
    add("You accepted the server rules.", "Du hast die Serverregeln akzeptiert.");
    add(
        "Please read and accept the current server rules.",
        "Bitte lies und akzeptiere die aktuellen Serverregeln.");
    add("Server Vote", "Serverabstimmung");
    add("No active vote", "Keine aktive Abstimmung");
    add("You have already voted.", "Du hast bereits abgestimmt.");
    add("Thank you for voting!", "Danke für deine Stimme!");
    add("Server Warps", "Server-Warps");
    add("Teleport", "Teleportieren");
    add("This server warp is not available.", "Dieser Server-Warp ist nicht verfügbar.");
    add(
        "This warp's world is currently unavailable.",
        "Die Welt dieses Warps ist derzeit nicht verfügbar.");
    add("Teleported to {warp}.", "Zu {warp} teleportiert.");
    add("Teleportation to {warp} failed.", "Teleportation zu {warp} fehlgeschlagen.");
    add("Report a player", "Spieler melden");
    add("Report player", "Spieler melden");
    add("Report submitted", "Meldung gesendet");
    add("Report details", "Details der Meldung");
    add("Chat abuse / insults", "Chatmissbrauch / Beleidigungen");
    add("Griefing / theft", "Griefing / Diebstahl");
    add("Cheating / exploits", "Cheating / Exploits");
    add("PvP harassment", "PvP-Belästigung");
    add("Spam / advertising", "Spam / Werbung");
    add("Other", "Sonstiges");
    add("Submit report", "Meldung absenden");
    add("Description", "Beschreibung");
    add("Category", "Kategorie");
    add("Evidence", "Beweise");
    add(
        "Your chat permission is currently disabled on this server.",
        "Du darfst auf diesem Server momentan nicht im Chat schreiben.");
    add(
        "Your command permission is currently disabled on this server.",
        "Du darfst auf diesem Server momentan keine Befehle verwenden.");
    add(
        "That message is not allowed on this server.",
        "Diese Nachricht ist auf diesem Server nicht erlaubt.");
    add(
        "Your message was held for staff review.",
        "Deine Nachricht wurde zur Prüfung durch das Team zurückgehalten.");
    add(
        "Chat moderation is temporarily unavailable. Please try again.",
        "Die Chatprüfung ist derzeit nicht verfügbar. Bitte versuche es erneut.");
    add("This action is currently restricted.", "Diese Aktion ist momentan eingeschränkt.");
    add(
        "You do not have permission to use this command.",
        "Du hast keine Berechtigung für diesen Befehl.");
    add("Previous page", "Vorherige Seite");
    add("Next page", "Nächste Seite");
    add("Back", "Zurück");
    add("Close", "Schließen");
    add("Cancel", "Abbrechen");
    add("Confirm", "Bestätigen");
    add("Submit", "Absenden");
    add("Online players", "Online-Spieler");
    add("Search", "Suchen");
    add("No data", "Keine Daten");
    add("Loading", "Wird geladen");
    add("Leaderboard", "Rangliste");
    add("Your rank", "Dein Rang");
    add("Your points", "Deine Punkte");
    add("Search by player name", "Nach Spielername suchen");
    add("Enter any part of the player's name.", "Gib einen Teil des Spielernamens ein.");
    add("Only players seen within the last", "Nur Spieler, die zuletzt innerhalb von");
    add("Click to enter a partial name.", "Klicken, um einen Teilnamen einzugeben.");
    add("How reporting works", "So funktioniert das Melden");
    add(
        "Search first, select the matching player,",
        "Suche zuerst und wähle den passenden Spieler,");
    add(
        "False or abusive reports may lead to a block.",
        "Falsche oder missbräuchliche Meldungen können zur Sperre des Meldesystems führen.");
    add("Close the report menu.", "Meldemenü schließen.");
    add(
        "This player is no longer inside the allowed reporting window.",
        "Dieser Spieler kann nicht mehr innerhalb des erlaubten Zeitfensters gemeldet werden.");
    add("Enter at least", "Gib mindestens");
    add("Current page:", "Aktuelle Seite:");
    add("Maximum page:", "Letzte Seite:");
    add("Search information", "Suchinformationen");
    add("Matches:", "Treffer:");
    add("New search", "Neue Suche");
    add("Enter another partial player name.", "Gib einen anderen Teilnamen ein.");
    add("Currently online", "Gerade online");
    add("Recently online", "Zuletzt online");
    add(
        "Eligible for reporting within the configured time window.",
        "Innerhalb des eingestellten Zeitfensters meldbar.");
    add("Click to report this player.", "Klicken, um diesen Spieler zu melden.");
    add("No matching player found", "Kein passender Spieler gefunden");
    add("No eligible player matched", "Kein meldbarer Spieler passt zu");
    add("Try another part of the name.", "Versuche einen anderen Teil des Namens.");
    add(
        "The selected player can no longer be reported.",
        "Der ausgewählte Spieler kann nicht mehr gemeldet werden.");
    add("Create report:", "Meldung erstellen:");
    add("Not selected", "Nicht ausgewählt");
    add("Not entered", "Nicht eingegeben");
    add("Selected", "Ausgewählt");
    add("Click to select this category.", "Klicken, um diese Kategorie auszuwählen.");
    add("Describe the incident", "Vorfall beschreiben");
    add("No description entered yet.", "Noch keine Beschreibung eingegeben.");
    add("Click and type the description in chat.", "Klicke und gib die Beschreibung im Chat ein.");
    add("Evidence snapshot enabled", "Beweissicherung aktiviert");
    add("Evidence snapshot disabled", "Beweissicherung deaktiviert");
    add(
        "Only authorized staff can view saved evidence:",
        "Nur berechtigtes Teammitglied kann gespeicherte Beweise sehen:");
    add("Click to toggle.", "Klicken zum Umschalten.");
    add(
        "The category and description will be validated.",
        "Kategorie und Beschreibung werden geprüft.");
    add(
        "Reports are visible only to authorized staff.",
        "Meldungen sind nur für berechtigtes Team sichtbar.");
    add("Discard this report.", "Diese Meldung verwerfen.");
    add(
        "You do not have permission to use the report system.",
        "Du darfst das Meldesystem nicht verwenden.");
    add("Select a report category first.", "Wähle zuerst eine Kategorie aus.");
    add("Describe the incident using at least", "Beschreibe den Vorfall mit mindestens");
    add(
        "Describe what happened. Type 'cancel' to return to the report.",
        "Beschreibe, was passiert ist. Mit cancel kehrst du zur Meldung zurück.");
    add("Your report was saved as report #", "Deine Meldung wurde gespeichert als #");
    add("Thank you for helping the server.", "Danke, dass du dem Server hilfst.");
    add("You are blocked from using the report system.", "Du bist für das Meldesystem gesperrt.");
    add("Please read all server rules carefully.", "Bitte lies alle Serverregeln aufmerksam.");
    add("Rules version:", "Regelversion:");
    add(
        "No server rules have been published yet.",
        "Es wurden noch keine Serverregeln veröffentlicht.");
    add(
        "You must accept the current server rules before you can continue playing.",
        "Du musst die aktuellen Serverregeln akzeptieren, bevor du weiterspielen kannst.");
    add(
        "By confirming, you state that you have read and agree to the displayed server rules.",
        "Mit der Bestätigung erklärst du, dass du die angezeigten Regeln gelesen hast und ihnen"
            + " zustimmst.");
    add("Yes, I agree", "Ja, ich stimme zu");
    add("Back to the rules", "Zurück zu den Regeln");
    add("CONFIRM RULES", "Regeln bestätigen");
    add("I have read all rules", "Ich habe alle Regeln gelesen");
    add("I do not agree", "Ich stimme nicht zu");
    add("Player name or partial name", "Spielername oder Teilname");
    add("Example: Miss or Cait", "Beispiel: Miss oder Cait");
    add("Eligible report target", "Meldbarer Spieler");
    add("Close menu", "Menü schließen");
    add(
        "Choose the category that best matches the incident. You can describe the details on the"
            + " next screen.",
        "Wähle die passende Kategorie. Auf der nächsten Seite kannst du Details beschreiben.");
    add("Back to search", "Zurück zur Suche");
    add("Describe what happened", "Beschreibe, was passiert ist");
    add(
        "Give staff enough detail to review the case",
        "Gib dem Team genügend Details, um den Fall zu prüfen");
    add(
        "Save recent location, chat, actions and combat events for authorized staff",
        "Letzten Standort, Chat, Aktionen und Kampfereignisse für berechtigtes Team speichern");
    add("Report search:", "Spielersuche:");
    add(
        "The server rules were updated. You must read and accept them before continuing.",
        "Die Serverregeln wurden aktualisiert. Bitte lies und akzeptiere sie, um fortzufahren.");
    add(
        "The server rules changed while this menu was open. Please read the current version.",
        "Die Regeln wurden geändert, während dieses Menü offen war. Bitte lies die neue Version.");
    add("Please read every rule carefully.", "Bitte lies jede Regel aufmerksam.");
    add("Current version:", "Aktuelle Version:");
    add("Continue to the final confirmation.", "Weiter zur abschließenden Bestätigung.");
    add("You accepted the current rules.", "Du hast die aktuellen Regeln akzeptiert.");
    add("Read the rules again.", "Regeln erneut lesen.");
    add(
        "There is no active server vote right now. Latest result:",
        "Derzeit läuft keine Abstimmung. Letztes Ergebnis:");
    add(
        "The server vote changed while this menu was open. The current vote is now displayed.",
        "Die Abstimmung wurde geändert. Die aktuelle Abstimmung wird angezeigt.");
    add("Choose one option", "Wähle eine Antwort");
    add(
        "Click an answer above to submit immediately.",
        "Klicke oben auf eine Antwort, um sofort abzustimmen.");
    add("Submit vote", "Stimme absenden");
    add("Close without submitting changes.", "Ohne Absenden schließen.");
    add("Current votes:", "Aktuelle Stimmen:");
    add("Players voted:", "Spieler abgestimmt:");
    add("Ends in:", "Endet in:");
    add("You have received a warning from staff:", "Du hast eine Verwarnung vom Team erhalten:");
    add("You were teleported to a staff member.", "Du wurdest zu einem Teammitglied teleportiert.");
    add("is now AFK.", "ist jetzt AFK.");
    add("is no longer AFK.", "ist nicht mehr AFK.");
    add(
        "Global chat is currently disabled by staff.",
        "Der globale Chat ist momentan vom Team deaktiviert.");
    add("Global chat has been disabled by staff.", "Der globale Chat wurde vom Team deaktiviert.");
    add("Global chat has been enabled by staff.", "Der globale Chat wurde vom Team aktiviert.");
    add(
        "Chat moderation is unavailable. Please contact staff.",
        "Die Chatprüfung ist nicht verfügbar. Bitte kontaktiere das Team.");
    add(
        "Your message was held because moderation is unavailable. Please try later.",
        "Deine Nachricht wurde zurückgehalten, da die Chatprüfung nicht verfügbar ist. Bitte"
            + " versuche es später.");
    add(
        "Chat moderation is temporarily unavailable. Please try again.",
        "Die Chatprüfung ist vorübergehend nicht verfügbar. Bitte versuche es erneut.");
    add(
        "The additional chat review could not finish. Your message was not sent. Please try again"
            + " or contact staff.",
        "Die zusätzliche Chatprüfung konnte nicht abgeschlossen werden. Deine Nachricht wurde nicht"
            + " gesendet. Bitte versuche es erneut oder kontaktiere das Team.");
    add("Please keep the chat respectful.", "Bitte bleibe im Chat respektvoll.");
    add(
        "Your message was held by the optional chat moderation system.",
        "Deine Nachricht wurde von der Chatprüfung zurückgehalten.");
    add("Please wait", "Bitte warte");
    add(
        "seconds before sending another message.",
        "Sekunden, bevor du die nächste Nachricht sendest.");
    add("No reason provided", "Kein Grund angegeben");
    add("Public chat has been cleared by staff.", "Der öffentliche Chat wurde vom Team geleert.");
    add(
        "Server maintenance has been enabled by staff.",
        "Der Wartungsmodus wurde vom Team aktiviert.");
    add(
        "Server maintenance has been disabled by staff.",
        "Der Wartungsmodus wurde vom Team deaktiviert.");
    add(
        "The scheduled server restart was cancelled by staff.",
        "Der geplante Serverneustart wurde vom Team abgebrochen.");
    add(
        "Commands have been temporarily restricted by staff.",
        "Befehle wurden vom Team vorübergehend eingeschränkt.");
    add(
        "Command restrictions have been lifted by staff.",
        "Die Befehlseinschränkungen wurden vom Team aufgehoben.");
    add(
        "Chat slowmode has been disabled by staff.",
        "Der Chat-Slowmode wurde vom Team deaktiviert.");
    add("Chat slowmode is now", "Der Chat-Slowmode beträgt jetzt");
    add(
        "The server rules have been updated to version",
        "Die Serverregeln wurden aktualisiert auf Version");
    add("Use /rules to read them.", "Lies sie mit /rules.");
    add("A new server vote has started:", "Eine neue Serverabstimmung wurde gestartet:");
    add("Use /vote to participate.", "Mit /vote kannst du teilnehmen.");
    add("The server vote has ended.", "Die Serverabstimmung wurde beendet.");
  }

  private record Translation(String needle, java.util.regex.Pattern pattern, String german) {}

  private static final List<Translation> TRANSLATIONS =
      TEXT.entrySet().stream()
          .sorted(
              java.util.Comparator.<Map.Entry<String, String>>comparingInt(e -> e.getKey().length())
                  .reversed())
          .map(
              e ->
                  new Translation(
                      e.getKey().toLowerCase(Locale.ROOT),
                      java.util.regex.Pattern.compile(
                          "(?:(?<![\\p{L}])|(?<=[§&][0-9a-fk-or]))"
                              + java.util.regex.Pattern.quote(e.getKey())
                              + "(?![\\p{L}])",
                          java.util.regex.Pattern.CASE_INSENSITIVE
                              | java.util.regex.Pattern.UNICODE_CASE),
                      e.getValue()))
          .toList();
  private static final java.util.regex.Pattern RESTRICTED =
      java.util.regex.Pattern.compile(
          "(?i)Your [^.!\\n]{1,80} permission is currently disabled on this server\\.");

  static String translate(String english) {
    String result = english == null ? "" : english, lower = result.toLowerCase(Locale.ROOT);
    for (Translation entry : TRANSLATIONS)
      if (lower.contains(entry.needle)) {
        result =
            entry
                .pattern
                .matcher(result)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(entry.german));
      }
    if (lower.contains("permission is currently disabled"))
      result =
          RESTRICTED
              .matcher(result)
              .replaceAll("Diese Funktion ist für dich auf diesem Server momentan deaktiviert.");
    return result;
  }

  static String localeName(String code, boolean de) {
    String normalized = code.replace('-', '_').toLowerCase(Locale.ROOT);
    Locale locale = Locale.forLanguageTag(normalized.replace('_', '-'));
    String name = locale.getDisplayName(de ? Locale.GERMAN : Locale.ENGLISH);
    return (name.isBlank() ? normalized : name) + " [" + normalized + "]";
  }

  private static final Map<String, String> LABELS =
      Map.ofEntries(
          Map.entry("Data version", "Datenversion"),
          Map.entry("Dimension", "Dimension"),
          Map.entry("Position", "Position"),
          Map.entry("Rotation", "Blickrichtung"),
          Map.entry("Health", "Gesundheit"),
          Map.entry("Food", "Hunger"),
          Map.entry("Saturation", "Sättigung"),
          Map.entry("Exhaustion", "Erschöpfung"),
          Map.entry("XP level", "XP-Level"),
          Map.entry("XP total", "XP gesamt"),
          Map.entry("XP progress", "XP-Fortschritt"),
          Map.entry("Score", "Punktestand"),
          Map.entry("Game mode", "Spielmodus"),
          Map.entry("Fire ticks", "Feuer-Ticks"),
          Map.entry("Air", "Luft"),
          Map.entry("Fall distance", "Fallhöhe"),
          Map.entry("On ground", "Am Boden"),
          Map.entry("Invulnerable", "Unverwundbar"),
          Map.entry("Portal cooldown", "Portal-Abklingzeit"),
          Map.entry("Sleep timer", "Schlaf-Timer"),
          Map.entry("Selected slot", "Ausgewählter Slot"),
          Map.entry("Inventory stacks", "Inventar-Stapel"),
          Map.entry("Ender chest stacks", "Enderchest-Stapel"),
          Map.entry("Known recipes", "Bekannte Rezepte"),
          Map.entry("Spawn dimension", "Spawn-Dimension"),
          Map.entry("Spawn", "Spawn"),
          Map.entry("Last death", "Letzter Tod"),
          Map.entry("Bukkit first played", "Datei-Erstjoin"),
          Map.entry("Bukkit last played", "Datei-Letztjoin"),
          Map.entry("Bukkit last known name", "Letzter gespeicherter Name"),
          Map.entry("Time since file first join", "Zeit seit Datei-Erstjoin"),
          Map.entry("May fly", "Darf fliegen"),
          Map.entry("Flying", "Fliegt"),
          Map.entry("Invulnerable ability", "Unverwundbarkeit"),
          Map.entry("Walk speed", "Laufgeschwindigkeit"),
          Map.entry("Fly speed", "Fluggeschwindigkeit"),
          Map.entry("minecraft:play_time", "Spielzeit (Ticks)"),
          Map.entry("minecraft:deaths", "Tode"),
          Map.entry("minecraft:jump", "Sprünge"),
          Map.entry("minecraft:mob_kills", "Kreaturen getötet"),
          Map.entry("minecraft:player_kills", "Spieler getötet"),
          Map.entry("minecraft:walk_one_cm", "Gelaufen (cm)"),
          Map.entry("minecraft:sprint_one_cm", "Gesprintet (cm)"),
          Map.entry("minecraft:swim_one_cm", "Geschwommen (cm)"),
          Map.entry("minecraft:fly_one_cm", "Geflogen (cm)"));

  static String label(String key, boolean de) {
    if (de) return LABELS.getOrDefault(key, key);
    return switch (key) {
      case "Bukkit first played" -> "File first join";
      case "Bukkit last played" -> "File last join";
      default -> key.replace("minecraft:", "").replace('_', ' ');
    };
  }
}
