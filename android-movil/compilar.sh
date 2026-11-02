#!/usr/bin/env bash
# Compila la APK de SpaceMusic para teléfonos Android sin Android Studio ni Gradle.
# Requisitos: Android SDK (platforms/android-34 y build-tools/34.0.0) y un JDK.
# Uso (Git Bash):  ./android-movil/compilar.sh   (usa la misma llave de firma que la app de TV)
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/c/Users/Josue/Android}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-/c/Users/Josue/.jdks/liberica-full-21.0.8}"
JBIN="$JAVA_HOME/bin"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/build"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/6 Recursos…"
"$BT/aapt2" compile --dir "$DIR/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$DIR/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 23 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"

echo "2/6 Java…"
"$JBIN/javac" -encoding UTF-8 --release 8 -nowarn -Xlint:-options -classpath "$JAR" \
  -d "$OUT/classes" $(find "$DIR/src" "$OUT/gen" -name '*.java')

echo "3/6 Dex…"
"$SDK/cmdline-tools/latest/bin/d8.bat" --release --min-api 23 --lib "$JAR" --output "$OUT/dex.zip" $(find "$OUT/classes" -name '*.class') 2>&1 | grep -v "^Warning" || true
mkdir -p "$OUT/dex" && (cd "$OUT/dex" && "$JBIN/jar" xf "$OUT/dex.zip" classes.dex)

echo "4/6 Empaquetando…"
cp "$OUT/base.apk" "$OUT/sin-firmar.apk"
(cd "$OUT/dex" && "$JBIN/jar" uf "$OUT/sin-firmar.apk" classes.dex)

echo "5/6 Alineando…"
"$BT/zipalign" -p -f 4 "$OUT/sin-firmar.apk" "$OUT/alineada.apk"

echo "6/6 Firmando…"
FIRMA="$DIR/../android-tv/firma"
mkdir -p "$FIRMA"
if [ ! -f "$FIRMA/spacemusic.jks" ]; then
  CLAVE="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
  echo "$CLAVE" > "$FIRMA/clave.txt"
  "$JBIN/keytool" -genkeypair -keystore "$FIRMA/spacemusic.jks" -alias spacemusic -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$CLAVE" -keypass "$CLAVE" -dname "CN=SpaceMusic, O=SpaceMusic, C=SV" >/dev/null 2>&1
  echo "   Llave de firma creada en android-tv/firma/ (guárdala: sin ella no se pueden instalar actualizaciones)"
fi
CLAVE="$(cat "$FIRMA/clave.txt")"
"$BT/apksigner.bat" sign --ks "$FIRMA/spacemusic.jks" --ks-key-alias spacemusic \
  --ks-pass "pass:$CLAVE" --key-pass "pass:$CLAVE" --out "$DIR/SpaceMusic.apk" "$OUT/alineada.apk"
"$BT/apksigner.bat" verify "$DIR/SpaceMusic.apk" && echo "✓ APK lista: android-movil/SpaceMusic.apk ($(du -h "$DIR/SpaceMusic.apk" | cut -f1))"
