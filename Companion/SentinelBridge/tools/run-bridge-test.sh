#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
bash "$ROOT/tools/build-test-fixtures.sh"
CP="$ROOT/target/offline-build/stubs:$ROOT/target/offline-build/extension"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"${JAVAC[@]}" --release 17 -encoding UTF-8 -cp "$CP" -d "$TMP" "$ROOT/tools/tests/BridgeHarness.java"
java -cp "$TMP:$CP" BridgeHarness
echo 'Bridge-Ende-zu-Ende-Test erfolgreich.'
