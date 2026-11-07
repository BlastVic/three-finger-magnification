#!/usr/bin/env bash
# Manual APK build pipeline (no Gradle / Android Studio project required).
#
#   aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# Requirements:
#   - JDK 17 (the Android Studio JBR works fine)
#   - Android SDK platform android-35 + build-tools 36.0.0
#   - bash (Git Bash on Windows, or any Unix shell)
#
# Override tool locations with ANDROID_HOME / JAVA_HOME / BUILD_TOOLS_VERSION.
# Signing is opt-in. Without SIGN_KEYSTORE a throwaway local debug key is
# generated under build/ (git-ignored, never shipped). To produce a release:
#
#   SIGN_KEYSTORE=/path/to/your.keystore \
#   SIGN_STORE_PASS='...' SIGN_KEY_ALIAS=alias SIGN_KEY_PASS='...' \
#   bash build.sh
#
# Never commit your keystore or passwords.
set -euo pipefail

cd "$(dirname "$0")"

# ---------------------------------------------------------------- toolchain
is_windows() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}

if is_windows; then
  : "${ANDROID_HOME:=C:/Users/${USERNAME:-$USER}/AppData/Local/Android/Sdk}"
  : "${JAVA_HOME:=C:/Program Files/Android/Android Studio/jbr}"
  CP_SEP=';'
else
  : "${ANDROID_HOME:=$HOME/Android/Sdk}"
  if [ -z "${JAVA_HOME:-}" ] && command -v javac >/dev/null 2>&1; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  fi
  : "${JAVA_HOME:?set JAVA_HOME to your JDK}"
  CP_SEP=':'
fi

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-35}"

# Java tools need native paths; shell tools need shell paths.
to_unix() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi
}
# Native (Java-visible) absolute path of this checkout.
if is_windows && command -v cygpath >/dev/null 2>&1; then
  ROOT_NATIVE="$(cygpath -m "$PWD")"
else
  ROOT_NATIVE="$PWD"
fi
native() { printf '%s/%s' "$ROOT_NATIVE" "$1"; }

BT="$(to_unix "$ANDROID_HOME")/build-tools/$BUILD_TOOLS_VERSION"
JBR="$(to_unix "$JAVA_HOME")"
PLATFORM="$ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar"
D8="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/lib/d8.jar"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/lib/apksigner.jar"

JAVA="$JBR/bin/java"
JAVAC="$JBR/bin/javac"
JARTOOL="$JBR/bin/jar"
KEYTOOL="$JBR/bin/keytool"

# Shizuku 13.1.5 official artifacts (classes.jar extracted from the AARs).
# See THIRD_PARTY_NOTICES.md. The Binder interfaces are hand-written in
# app/src (aidl.exe has a Windows path-normalisation bug); app/aidl holds the
# interface contracts for reference.
LIBS="app/libs"
LIB_API="$LIBS/api.jar"
LIB_AIDL="$LIBS/aidl.jar"
LIB_SHARED="$LIBS/shared.jar"
LIB_PROVIDER="$LIBS/provider.jar"

VERSION_CODE=1
VERSION_NAME="1.0"
OUT="dist/three-finger-magnification-v$VERSION_NAME.apk"

rm -rf build
mkdir -p build/gen build/classes build/dex dist

echo "[1/8] aapt2 compile resources"
"$BT/aapt2" compile --dir app/res -o build/res.zip

echo "[2/8] aapt2 link manifest + resources"
"$BT/aapt2" link \
  -o build/base.apk \
  -I "$PLATFORM" \
  --manifest app/AndroidManifest.xml \
  -R build/res.zip \
  --java build/gen \
  --auto-add-overlay \
  --min-sdk-version 30 \
  --target-sdk-version 35 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME"

echo "[3/8] collect sources"
find app/src build/gen -name '*.java' | sort > build/sources.txt
wc -l < build/sources.txt

echo "[4/8] javac"
CP="$PLATFORM"
for j in "$LIB_API" "$LIB_AIDL" "$LIB_SHARED" "$LIB_PROVIDER"; do
  CP="$CP$CP_SEP$(native "$j")"
done
"$JAVAC" -encoding UTF-8 -nowarn \
  -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -cp "$CP" \
  -d build/classes \
  @build/sources.txt

echo "[5/8] unpack Shizuku dependency classes into dex inputs"
for j in "$LIB_API" "$LIB_AIDL" "$LIB_SHARED" "$LIB_PROVIDER"; do
  unzip -o -q "$j" -d build/classes
done
find build/classes -name '*.class' | wc -l

echo "[6/8] d8 -> classes.dex"
find build/classes -name '*.class' | sort > build/classes.txt
"$JAVA" -cp "$D8" com.android.tools.r8.D8 \
  --lib "$PLATFORM" \
  --min-api 30 \
  --output build/dex \
  $(cat build/classes.txt)
ls -l build/dex/classes.dex

echo "[7/8] add dex to APK + zipalign + sign"
"$JARTOOL" uf build/base.apk -C build/dex classes.dex
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk

if [ -n "${SIGN_KEYSTORE:-}" ]; then
  KS="$SIGN_KEYSTORE"
  KS_TYPE="${SIGN_KEYSTORE_TYPE:-PKCS12}"
  KS_PASS="${SIGN_STORE_PASS:?set SIGN_STORE_PASS when SIGN_KEYSTORE is used}"
  KEY_PASS="${SIGN_KEY_PASS:-$KS_PASS}"
  KS_ALIAS=()
  if [ -n "${SIGN_KEY_ALIAS:-}" ]; then
    KS_ALIAS=(--ks-key-alias "$SIGN_KEY_ALIAS")
  fi
  echo "      signing with $KS"
else
  echo "      SIGN_KEYSTORE not set -> local debug key (not for distribution)"
  KS="build/local-debug.p12"
  KS_TYPE="PKCS12"
  KS_PASS="android-debug"
  KEY_PASS="android-debug"
  KS_ALIAS=(--ks-key-alias localdebug)
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair \
      -alias localdebug \
      -keyalg RSA -keysize 2048 -validity 10950 \
      -storetype PKCS12 \
      -keystore "$KS" \
      -storepass "$KS_PASS" -keypass "$KEY_PASS" \
      -dname "CN=LocalDebug, O=ThreeFingerMagnification, C=US" >/dev/null 2>&1
  fi
fi

"$JAVA" -cp "$APKSIGNER" com.android.apksigner.ApkSignerTool sign \
  --ks "$KS" \
  --ks-type "$KS_TYPE" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" \
  "${KS_ALIAS[@]}" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$OUT" \
  build/aligned.apk

echo "[8/8] verify"
"$JAVA" -cp "$APKSIGNER" com.android.apksigner.ApkSignerTool verify --verbose "$OUT"
"$BT/aapt2" dump badging "$OUT" | head -12
echo
echo "BUILD OK -> $OUT ($(wc -c < "$OUT") bytes)"
