#!/bin/sh
# Builds TVRemote.apk with the Android SDK tools only (no Gradle).
# Optional env: ANDROID_SDK, VERSION_NAME, VERSION_CODE,
#               KEYSTORE / KS_PASS / KEY_PASS (defaults: the local debug keystore).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.1.0
JAR=$SDK/platforms/android-36/android.jar
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_PASS=${KS_PASS:-android}
KEY_PASS=${KEY_PASS:-$KS_PASS}

VERSION_ARGS=""
[ -n "$VERSION_NAME" ] && VERSION_ARGS="$VERSION_ARGS --version-name $VERSION_NAME"
[ -n "$VERSION_CODE" ] && VERSION_ARGS="$VERSION_ARGS --version-code $VERSION_CODE"

rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml $VERSION_ARGS build/res.zip
javac -source 11 -target 11 -nowarn -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" --out TVRemote.apk build/aligned.apk
echo "Built $(pwd)/TVRemote.apk"
