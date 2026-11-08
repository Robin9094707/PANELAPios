# PowerBot-Stichwortregeln · Panel 5.4.1 und Admin Suite 10.11.1

## Stichwortregeln in App und Web

Unter **PowerBot-Fallzentrale → Stichwortregeln** kannst du Wörter und Phrasen anlegen, bearbeiten, suchen und löschen. Dafür brauchst du Vollzugriff sowie die Rechte `data.powerbot`, `powerbot.manage` und `powerbot.settings`.

- Standardmäßig ist `SMP` mit einer Prüfung auf fremde Eigenwerbung hinterlegt. Eine klare Einladung kann auch ohne IP oder Link blockiert werden. Normale SMP-Erwähnungen werden durchgelassen.
- Ein Treffer als ganzes Wort oder ganze Phrase löst bei aktiver KI-Moderation eine Kontextprüfung aus, selbst wenn die erste Moderationsprüfung nichts Auffälliges findet. Groß- und Kleinschreibung spielen dabei keine Rolle.
- PowerBot kombiniert die passenden Stichwortregeln mit den allgemeinen Moderationsregeln. Das Ergebnis kann Durchlassen, Warnen oder Blockieren sein. Ein leeres Regelfeld verwendet die allgemeinen Regeln.
- Im öffentlichen Chat und Discord-Relay kann PowerBot eine Nachricht vor der Zustellung zurückhalten. Flüsternachrichten werden weiterhin im Hintergrund geprüft. Eine reine Stichwort-Ablehnung wird protokolliert und zählt für sich allein nicht zum AutoMod-Mute-Score.
- Gelöschte Stichwörter bleiben nach einem Neustart gelöscht.

## iPhone-Startseite

**Aktuelle Ereignisse** zeigt die fünf neuesten Servermeldungen und relevanten PowerBot-Einträge aus deinen sichtbaren Verbindungen. Tippe auf einen Eintrag, um den jeweiligen Bereich des richtigen Servers zu öffnen. Für die Anzeige braucht die App Zugriff auf die Serverereignisse oder die PowerBot-Fallzentrale.

## Update

`SimplexAdminSuite-10.11.1.jar` installieren und den Server neu starten. Das Webpanel steckt in der JAR. Danach `Simplex-Panel-5.4.1-unsigned.ipa` wie gewohnt signieren und installieren.
