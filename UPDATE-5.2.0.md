# Simplex Panel 5.2.0 und Admin Suite 10.9.0

Öffne pro Server **Admin → Server & Diagnose → KI-Assistent** oder nutze ✨ im Staff-Chat. Im Webpanel gibt es den Einstieg unter **Übersicht → KI-Assistent** und im Staff-Chat über **Privater KI-Chat**.

- Die privaten Chatverläufe werden je Suite-Konto gespeichert. App und Webpanel sehen mit demselben Konto dieselben Chats. Unter den Chat-Einstellungen lassen sich Verläufe benennen, löschen oder nach 1, 7 oder 30 Tagen Inaktivität automatisch entfernen. Die Vorschläge eines gelöschten Chats sind nicht mehr freigebbar.
- Folgefragen berücksichtigen einen kurzen Ausschnitt desselben Chats. Antworten aus Datenquellen, für die ein Konto inzwischen kein Recht mehr hat, werden ausgeblendet und für Folgefragen übersprungen.
- Sprachaufnahmen bis zwei Minuten lassen sich vorhören, als Text transkribieren und bearbeiten. Gesendet wird erst über „Senden“. Die Sprachaufnahme wird zur Transkription an OpenAI übertragen und von der Admin Suite nicht gespeichert. Antworten lassen sich mit der Sprachsynthese von iOS oder des Browsers vorlesen. Die Webaufnahme braucht HTTPS und einen Browser mit Mikrofonzugriff.
- Die Spracherkennung verwendet den vorhandenen OpenAI-Schlüssel und standardmäßig `gpt-transcribe`. Das Transkriptionsmodell ist separat unter `openai.admin-assistant.transcription-model` konfigurierbar. Eine Transkription zählt als KI-Anfrage.
- Der Assistent schlägt zusätzlich Rechte-Sperren für Spieler, Bereichssperren und Befehlsregeln vor. Die Vorschau zeigt Ziele, Dauer und die geltende Alt-Vererbung. Die Rechte des anfragenden Kontos und die Freigabe des Hauptadmins gelten weiter. Änderungen an LuckPerms-Rängen und Zugangsdaten erfolgen über die dafür vorgesehenen manuellen Werkzeuge.

Die App mit derselben App-ID signieren. Bei gestopptem Server die JAR ersetzen und den vorhandenen Datenordner weiterverwenden. Mit Admin Suite 10.8.0 verwendet die neue App den bisherigen KI-Assistenten.
