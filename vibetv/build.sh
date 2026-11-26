#!/usr/bin/env bash
# Rebuilds the patched VibeTV app from the original XAPK.
#
#   ./build.sh                      # uses keystore/vibetv.jks (created if missing)
#   KEYSTORE=/path/key.jks KS_PASS=... KS_ALIAS=... ./build.sh
#   XAPK=original/Other.xapk ./build.sh
#
# Needs: Java 17+, python3, curl, unzip, zip. Tools are downloaded into tools/.
# Output: dist/VibeTV.apk (single installable APK)
#         dist/VibeTV.apks (split APKs, install with SAI)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD

XAPK=${XAPK:-original/app-3.1.37.xapk}
KEYSTORE=${KEYSTORE:-keystore/vibetv.jks}
KS_ALIAS=${KS_ALIAS:-vibetv}
KS_PASS=${KS_PASS:-vibetv}

APKTOOL_VER=2.10.0
APKEDITOR_VER=1.4.2
UBER_VER=1.3.0
R8_VER=8.5.35

T=$ROOT/tools
B=$ROOT/build
mkdir -p "$T"

fetch() { [ -s "$T/$1" ] || { echo "Downloading $1"; curl -fsSL -o "$T/$1" "$2"; }; }
fetch apktool.jar   "https://github.com/iBotPeaches/Apktool/releases/download/v$APKTOOL_VER/apktool_$APKTOOL_VER.jar"
fetch APKEditor.jar "https://github.com/REAndroid/APKEditor/releases/download/V$APKEDITOR_VER/APKEditor-$APKEDITOR_VER.jar"
fetch uber.jar      "https://github.com/patrickfav/uber-apk-signer/releases/download/v$UBER_VER/uber-apk-signer-$UBER_VER.jar"
fetch r8.jar        "https://maven.google.com/com/android/tools/r8/$R8_VER/r8-$R8_VER.jar"

rm -rf "$B" && mkdir -p "$B"/{xapk,helper,unsigned,merged}

echo "== 1. Extract XAPK"
unzip -q "$XAPK" -d "$B/xapk"
BASE=$(python3 -I -c 'import json,sys; m=json.load(open(sys.argv[1])); print(next(s["file"] for s in m["split_apks"] if s["id"]=="base"))' "$B/xapk/manifest.json")

echo "== 2. Compile log helper (VibeLog) to smali"
javac -nowarn -source 8 -target 8 -d "$B/helper/stubs" $(find helper/stubs -name '*.java')
javac -nowarn -source 8 -target 8 -cp "$B/helper/stubs" -d "$B/helper/classes" helper/src/ca/vibetv/*.java
java -cp "$T/r8.jar" com.android.tools.r8.D8 --min-api 26 --release \
     --output "$B/helper" --classpath "$B/helper/stubs" "$B"/helper/classes/ca/vibetv/*.class
# apktool only disassembles APKs, so wrap the dex in a throwaway one.
unzip -p "$B/xapk/$BASE" AndroidManifest.xml > "$B/helper/AndroidManifest.xml"
(cd "$B/helper" && zip -q helper.apk classes.dex AndroidManifest.xml)
java -jar "$T/apktool.jar" d -q -r -f -o "$B/helper/dec" "$B/helper/helper.apk"

echo "== 3. Decode app (code only, resources kept original)"
java -jar "$T/apktool.jar" d -q -r -f -o "$B/app" "$B/xapk/$BASE"
LAST=$(ls -d "$B"/app/smali* | sort -V | tail -1)
mkdir -p "$LAST/ca/vibetv"
cp "$B"/helper/dec/smali/ca/vibetv/*.smali "$LAST/ca/vibetv/"

echo "== 4. Apply patches"
python3 -I patches/patch.py "$B/app"

echo "== 5. Rebuild base APK"
java -jar "$T/apktool.jar" b -q "$B/app" -o "$B/unsigned/$BASE"
for f in "$B"/xapk/*.apk; do [ "$(basename "$f")" = "$BASE" ] || cp "$f" "$B/unsigned/"; done

echo "== 6. Signing key"
if [ ! -f "$KEYSTORE" ]; then
  echo "   No keystore at $KEYSTORE - creating a NEW one."
  echo "   (Installs signed with a different key can't update the old install: uninstall first.)"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
          -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=VibeTV" >/dev/null
fi
SIGN=(--ks "$KEYSTORE" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS" --allowResign)

echo "== 7. Merge splits into one APK, align + sign"
java -jar "$T/APKEditor.jar" m -i "$B/unsigned" -o "$B/merged/VibeTV.apk" >/dev/null
java -jar "$T/uber.jar" -a "$B/merged/VibeTV.apk" -o "$B/merged/signed" "${SIGN[@]}" >/dev/null
java -jar "$T/uber.jar" -a "$B/unsigned" -o "$B/signed" "${SIGN[@]}" >/dev/null

echo "== 8. Package"
rm -rf dist && mkdir -p dist "$B/apks"
cp "$B"/merged/signed/*-aligned-signed.apk dist/VibeTV.apk
for f in "$B"/signed/*-aligned-signed.apk; do cp "$f" "$B/apks/$(basename "$f" | sed 's/-aligned-signed//')"; done
(cd "$B/apks" && zip -q -0 "$ROOT/dist/VibeTV.apks" *.apk)
ls -la dist
echo "Done."
