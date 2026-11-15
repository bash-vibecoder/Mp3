#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ANDROID_JAR="${POCKET_ANDROID_JAR:-$PWD/.tools/android.jar}"
RESOURCE_JAR="${POCKET_RESOURCE_JAR:-$PWD/.tools/android-34.jar}"
R8_JAR="${POCKET_R8_JAR:-$PWD/.tools/r8.jar}"
for tool in java javac jar aapt zipalign apksigner keytool zip sed; do
    command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done
POCKET_VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/src/main/AndroidManifest.xml)"
if ! [[ "$POCKET_VERSION" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "The manifest versionName must be a nonempty filename-safe version." >&2
    exit 1
fi
POCKET_APK="dist/APocketMp3-v${POCKET_VERSION}.apk"
test -f "$ANDROID_JAR" || { echo "Missing Android API jar: $ANDROID_JAR" >&2; exit 1; }
test -f "$RESOURCE_JAR" || { echo "Missing Android resource jar: $RESOURCE_JAR" >&2; exit 1; }
test -f "$R8_JAR" || { echo "Missing R8/D8 jar: $R8_JAR" >&2; exit 1; }
# Recreate generated outputs so renamed packages cannot leave stale classes in the APK.
rm -rf build/generated build/classes build/dex build/tests
mkdir -p build/generated build/classes build/dex build/tests dist .tools
aapt package -f -m -M app/src/main/AndroidManifest.xml -S app/src/main/res -I "$RESOURCE_JAR" -J build/generated
find app/src/main/java build/generated -name '*.java' -print > build/sources.txt
javac --release 8 -classpath "$ANDROID_JAR" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" --output build/dex build/classes.jar
aapt package -f -M app/src/main/AndroidManifest.xml -S app/src/main/res -I "$RESOURCE_JAR" -F build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes*.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
if ! test -f .tools/pocket-dev.jks; then
    keytool -genkeypair -keystore .tools/pocket-dev.jks -storepass android -keypass android -alias pocket -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=APocketMp3 Development' -noprompt
fi
apksigner sign --ks .tools/pocket-dev.jks --ks-key-alias pocket --ks-pass pass:android --key-pass pass:android --out "$POCKET_APK" build/aligned.apk
apksigner verify --verbose "$POCKET_APK"
zipalign -c -p 4 "$POCKET_APK"
javac -d build/tests app/src/main/java/com/apocketmp3/player/Track.java app/src/main/java/com/apocketmp3/player/PlaybackQueue.java tests/com/apocketmp3/player/PlaybackQueueTest.java
java -cp build/tests com.apocketmp3.player.PlaybackQueueTest
ls -lh "$POCKET_APK"
