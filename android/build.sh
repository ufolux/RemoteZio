#!/bin/sh
# Builds TVRemote.apk with the Android SDK tools only (no Gradle).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.1.0
JAR=$SDK/platforms/android-36/android.jar

rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip
javac -source 11 -target 11 -nowarn -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out TVRemote.apk build/aligned.apk
echo "Built $(pwd)/TVRemote.apk"
