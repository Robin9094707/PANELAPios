# SimplexSentinelBridge 1.6.0 bauen

```bash
python3 tools/build-real.py
# Nach dem ersten Download mit bereits verifiziertem Cache:
python3 tools/build-real.py --offline
```

Python 3 und Java 17 oder neuer. Der Compiler und die echte Geyser-Distribution sind in `tools/build-dependencies.json` fest mit Downloadadresse und SHA-256 dokumentiert. Die fertige JAR liegt in `target/SimplexSentinelBridge-1.6.0.jar`. Nur eigene Erweiterungsklassen und Ressourcen werden gepackt.

```bash
# End-to-End-HTTP/HMAC-Test mit ausschließlich isolierten API-Test-Fakes:
bash tools/run-bridge-test.sh
# Collector mit echter Geyser-API und echter BedrockClientData-Klasse (Java 25 verwendet):
JAVA=/pfad/zu/java25/bin/java python3 tools/test-real.py
```

Die Test-Fakes erzeugen ausschließlich temporäre Testklassen; sie können keine Release-JAR erstellen. Ein Live-Test auf einer laufenden Minecraft-/Geyser-Installation ist ergänzend erforderlich.
