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
  ../app/src/main/java/com/medialibrary/manager/remote/RemoteQueue.kt RemoteInterop.kt \
  ../app/src/main/java/com/medialibrary/manager/cast/CastProtocols.kt ../app/src/main/java/com/medialibrary/manager/cast/MediaHttpServer.kt CastInterop.kt \
  ../app/src/main/java/com/medialibrary/manager/tv/FizzTvClient.kt TvInterop.kt

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

echo "--- TV tab: Fizz sign-in and guide (fake Fizz server)"
node ../../media-library-manager/test/fakeFizz.mjs serve > "$WORK/fizz.json" &
FIZZ=$!
for _ in $(seq 1 40); do [ -s "$WORK/fizz.json" ] && break; sleep 0.25; done
java -cp "$CACHE/out:$STDLIB:$JSON" TvInteropKt "$(cat "$WORK/fizz.json")"
kill $FIZZ 2>/dev/null || true

echo "--- Casting: Google Cast (fake TV on castv2) and DLNA (gmediarender, if installed)"
CAST="$WORK/cast"
mkdir -p "$CAST"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$CAST/key.pem" -out "$CAST/cert.pem" -days 2 -subj "/CN=Chromecast" 2>/dev/null
head -c 3000000 /dev/urandom > "$CAST/movie.mkv"
node fake-chromecast.mjs 18009 "$CAST/key.pem" "$CAST/cert.pem" "$CAST/report.json" > "$CAST/fake.log" 2>&1 &
FAKE=$!
for _ in $(seq 1 40); do grep -q ready "$CAST/fake.log" 2>/dev/null && break; sleep 0.25; done
java -cp "$CACHE/out:$STDLIB:$JSON" CastInteropKt cast 18009 "$CAST/movie.mkv" "$CAST/report.json"
kill $FAKE 2>/dev/null || true

# Live TV cast: launches Fizz's own receiver (559B0340) with the DRM token/type/content-id metadata.
node fake-chromecast.mjs 18010 "$CAST/key.pem" "$CAST/cert.pem" "$CAST/report-live.json" > "$CAST/fake-live.log" 2>&1 &
FAKE_LIVE=$!
for _ in $(seq 1 40); do grep -q ready "$CAST/fake-live.log" 2>/dev/null && break; sleep 0.25; done
java -cp "$CACHE/out:$STDLIB:$JSON" CastInteropKt castlive 18010 "$CAST/report-live.json"
kill $FAKE_LIVE 2>/dev/null || true
if command -v gmediarender >/dev/null; then
  python3 - "$CAST/tone.wav" <<'PY'
import math, struct, sys, wave
w = wave.open(sys.argv[1], "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000)
w.writeframes(b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * i / 8000))) for i in range(8000 * 30))); w.close()
PY
  IFACE="$(awk '$2 == "00000000" { print $1; exit }' /proc/net/route)"
  gmediarender -f "Living Room Samsung" -I "$IFACE" --gstout-audiopipe "fakesink sync=true" --gstout-videosink=fakesink > "$CAST/gmr.log" 2>&1 &
  GMR=$!
  sleep 2
  java -cp "$CACHE/out:$STDLIB:$JSON" CastInteropKt dlna "$CAST/tone.wav"
  kill $GMR 2>/dev/null || true
else
  echo "(skipped DLNA: install gmediarender to run it)"
fi
