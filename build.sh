#!/usr/bin/env bash
# Compila la app "Terminal Linux" a un APK instalable (sin Gradle).
# Usa el Android SDK de /home/user/android-sdk: aapt2, d8, apksigner y android.jar.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/home/user/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT="$HERE/build"
APP="$HERE/app/src/main"
FINAL="$(dirname "$HERE")/Terminal-Linux.apk"

for f in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$PLATFORM"; do
  [[ -e $f ]] || { echo "Falta $f. Instala el SDK (platform android-34, build-tools 34.0.0)." >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen"

echo "0/5 Ejemplos y utilidades dentro del APK"
# La copia a assets se genera al compilar; el repositorio no guarda duplicados.
ASSETS_EX="$APP/assets/examples"
mkdir -p "$ASSETS_EX"
find "$ASSETS_EX" -maxdepth 1 -name '*.py' -delete 2>/dev/null || true
cp -f "$HERE"/examples/*.py "$ASSETS_EX/" 2>/dev/null || true
ls "$ASSETS_EX" 2>/dev/null | sed 's/^/    /'

echo "1/5 Recursos (icono) y manifiesto"
"$BT/aapt2" compile -o "$OUT/res.zip" --dir "$HERE/app/src/main/res"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" -R "$OUT/res.zip" \
  -A "$APP/assets" --manifest "$APP/AndroidManifest.xml" --min-sdk-version 24 --target-sdk-version 28 \
  --version-code 5 --version-name 1.6.0

echo "2/5 Compilando Java"
javac --release 8 -g -encoding UTF-8 -Xlint:-options -cp "$PLATFORM" \
  -d "$OUT/classes" $(find "$APP/java" -name '*.java')

echo "3/5 Convirtiendo a DEX"
"$BT/d8" --min-api 24 --lib "$PLATFORM" --output "$OUT" $(find "$OUT/classes" -name '*.class')

echo "4/5 Empaquetando"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "5/5 Firmando"
KS="$HERE/debug.keystore"
if [[ ! -f $KS ]]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias androiddebug -keyalg RSA -keysize 2048 -validity 20000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebug --out "$FINAL" "$OUT/unsigned.apk"
"$BT/apksigner" verify --verbose "$FINAL"
ls -l "$FINAL"
