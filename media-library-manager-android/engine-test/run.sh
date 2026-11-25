#!/usr/bin/env bash
# Runs the torrent engine tests (TorrentEngine.kt) on a desktop JVM against a local WebTorrent
# seeder — no Android device, internet peers, trackers or DHT involved.
# Then checks "Send to another device" between this app's RemoteQueue.kt and the desktop app's
# remoteQueue.ts on a shared folder.
# Needs: Java 17+, Node 20+, and `npm install && npm run build` done in ../../media-library-manager
# (WebTorrent for the seeder, the compiled desktop code for the interop check).
# Jars (Kotlin compiler, libtorrent4j + its Linux x86_64 native build) are fetched from Maven
# Central into .cache/ on first run.
set -euo pipefail
cd "$(dirname "$0")"
LT=2.1.0-37   # keep in step with gradle/libs.versions.toml
KT=2.0.21
CACHE=.cache
mkdir -p "$CACHE"
fetch() { # group artifact version
  local f="$CACHE/$2-$3.jar"
  [ -s "$f" ] || curl -sSfL -o "$f" "https://repo1.maven.org/maven2/${1//.//}/$2/$3/$2-$3.jar"
  echo "$f"
}
COMPILER_CP="$(fetch org.jetbrains.kotlin kotlin-compiler-embeddable $KT):$(fetch org.jetbrains.kotlin kotlin-stdlib $KT):$(fetch org.jetbrains.kotlin kotlin-script-runtime $KT):$(fetch org.jetbrains.kotlin kotlin-reflect 1.6.10):$(fetch org.jetbrains.kotlin kotlin-daemon-embeddable $KT):$(fetch org.jetbrains.intellij.deps trove4j 1.0.20200330):$(fetch org.jetbrains annotations 13.0):$(fetch org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.6.4)"
STDLIB="$CACHE/kotlin-stdlib-$KT.jar"
LT_JAR="$(fetch org.libtorrent4j libtorrent4j $LT)"
LT_LINUX="$(fetch org.libtorrent4j libtorrent4j-linux $LT)"
JSON="$(fetch org.json json 20240303)"
rm -rf "$CACHE/native" && mkdir -p "$CACHE/native" && unzip -q -j "$LT_LINUX" 'lib/x86_64/*' -d "$CACHE/native"

rm -rf "$CACHE/out"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn \
  -classpath "$STDLIB:$LT_JAR:$JSON" -d "$CACHE/out" \
  ../app/src/main/java/com/medialibrary/manager/torrent/TorrentEngine.kt EngineTest.kt \
  ../app/src/main/java/com/medialibrary/manager/remote/RemoteQueue.kt RemoteInterop.kt

WORK="$(mktemp -d)"
trap 'kill $SEEDER 2>/dev/null || true; rm -rf "$WORK"' EXIT
mkdir -p "$WORK/seed" "$WORK/dl"
node seeder.mjs "$WORK/seed" > "$WORK/seed.json" &
SEEDER=$!
for _ in $(seq 1 120); do [ -s "$WORK/seed.json" ] && break; sleep 0.5; done
java -Djava.library.path="$CACHE/native" -cp "$CACHE/out:$STDLIB:$LT_JAR" EngineTestKt "$WORK/seed" "$WORK/dl" "$WORK/seed.json"

echo "--- Send to another device: Android <-> desktop"
BOX="$WORK/mailbox"
mkdir -p "$BOX"
KT_RUN="java -cp $CACHE/out:$STDLIB:$JSON RemoteInteropKt $BOX"
node remote-interop.mjs "$BOX" send
$KT_RUN receive
node remote-interop.mjs "$BOX" receive
$KT_RUN replies
