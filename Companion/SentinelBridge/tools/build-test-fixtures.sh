#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/target/offline-build"
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
if command -v jar >/dev/null 2>&1; then JAR=(jar); else JAR=(java -m jdk.jartool/sun.tools.jar.Main); fi
rm -rf "$BUILD"
mkdir -p "$BUILD/stubs" "$BUILD/extension" "$ROOT/target"
find "$ROOT/stubs" -name '*.java' -print > "$BUILD/stub-sources.txt"
find "$ROOT/src/main/java" -name '*.java' -print > "$BUILD/extension-sources.txt"
"${JAVAC[@]}" --release 17 -encoding UTF-8 -d "$BUILD/stubs" @"$BUILD/stub-sources.txt"
"${JAVAC[@]}" --release 17 -encoding UTF-8 -Xlint:all -cp "$BUILD/stubs" -d "$BUILD/extension" @"$BUILD/extension-sources.txt"
