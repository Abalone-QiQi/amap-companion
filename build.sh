#!/bin/bash
set -e

echo "=== AMap Companion Build Script ==="

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [ -z "$SDK" ]; then
  echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT not set"
  echo "Please set your Android SDK path, e.g.:"
  echo "  export ANDROID_HOME=\$HOME/Android/Sdk"
  exit 1
fi

echo "SDK: $SDK"

# Find latest build-tools
if [ -n "$ANDROID_BUILD_TOOLS_VERSION" ]; then
  BUILD_TOOLS="$SDK/build-tools/$ANDROID_BUILD_TOOLS_VERSION"
else
  BUILD_TOOLS=$(ls -d "$SDK/build-tools/"* 2>/dev/null | sort -V | tail -1)
fi

# Find latest platform
if [ -n "$ANDROID_PLATFORM" ]; then
  PLATFORM_DIR="$SDK/platforms/$ANDROID_PLATFORM"
elif [ -n "$ANDROID_PLATFORM_VERSION" ]; then
  PLATFORM_DIR="$SDK/platforms/android-$ANDROID_PLATFORM_VERSION"
else
  PLATFORM_DIR=$(ls -d "$SDK/platforms/android-"* 2>/dev/null | sort -V | tail -1)
fi

ANDROID_JAR="$PLATFORM_DIR/android.jar"

AAPT="$BUILD_TOOLS/aapt"
D8="$ANDROID_HOME/cmdline-tools/latest-2/bin/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

# Verify tools exist
for tool in "$AAPT" "$D8" "$ZIPALIGN" "$APKSIGNER" "$ANDROID_JAR"; do
  if [ ! -f "$tool" ]; then
    echo "ERROR: Required build input not found: $tool"
    exit 1
  fi
done

echo "build-tools: $BUILD_TOOLS"
echo "platform: $PLATFORM_DIR"
echo "android.jar: $ANDROID_JAR"

BUILD_DIR="$ROOT/build"
GEN_DIR="$BUILD_DIR/gen"
CLASSES_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
SOURCES_FILE="$BUILD_DIR/sources.txt"
MANIFEST_SOURCE="$ROOT/app/src/main/AndroidManifest.xml"
MANIFEST_BUILD="$BUILD_DIR/AndroidManifest.xml"

# Clean
rm -rf "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR"
mkdir -p "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR"

# Copy and update manifest
cp "$MANIFEST_SOURCE" "$MANIFEST_BUILD"
if [ -n "$APP_VERSION_CODE" ]; then
  sed -i "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$APP_VERSION_CODE\"/" "$MANIFEST_BUILD"
fi
if [ -n "$APP_VERSION_NAME" ]; then
  sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$APP_VERSION_NAME\"/" "$MANIFEST_BUILD"
fi

echo "=> Generating R.java..."
"$AAPT" package -f -m -J "$GEN_DIR" -M "$MANIFEST_BUILD" -S app/src/main/res -I "$ANDROID_JAR"

echo "=> Collecting sources..."
SOURCES=""
while IFS= read -r -d '' file; do
  SOURCES="$SOURCES ${file#$ROOT/}"
done < <(find app/src/main/java -name '*.java' -print0)

while IFS= read -r -d '' file; do
  SOURCES="$SOURCES ${file#$ROOT/}"
done < <(find "$GEN_DIR" -name '*.java' -print0)

echo "$SOURCES" | tr ' ' '\n' | grep -v '^$' > "$SOURCES_FILE"
echo "  Found $(wc -l < "$SOURCES_FILE") source files"

echo "=> Compiling Java..."
javac -encoding UTF-8 --release 11 -classpath "$ANDROID_JAR" -d "$CLASSES_DIR" "@$SOURCES_FILE"

echo "=> Converting to DEX..."
CLASS_FILES=$(find "$CLASSES_DIR" -name '*.class' | tr '\n' ' ')
"$D8" --lib "$ANDROID_JAR" --min-api 23 --output "$DEX_DIR" $CLASS_FILES

echo "=> Packaging APK..."
UNSIGNED_APK="$BUILD_DIR/amap_companion_unsigned.apk"
ALIGNED_APK="$BUILD_DIR/amap_companion_aligned.apk"
SIGNED_APK="$ROOT/amap_companion_signed.apk"

"$AAPT" package -f -M "$MANIFEST_BUILD" -S app/src/main/res -I "$ANDROID_JAR" -F "$UNSIGNED_APK" "$DEX_DIR"

echo "=> Aligning APK..."
"$ZIPALIGN" -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"

echo "=> Signing APK..."
# Use debug keystore (create if not exists)
DEBUG_KEYSTORE="$ROOT/debug.keystore"
if [ ! -f "$DEBUG_KEYSTORE" ]; then
  echo "Creating debug keystore..."
  keytool -genkeypair -v \
    -keystore "$DEBUG_KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" \
    -noprompt 2>/dev/null
fi

"$APKSIGNER" sign \
  --ks "$DEBUG_KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$SIGNED_APK" \
  "$ALIGNED_APK"

echo "=> Verifying APK..."
"$APKSIGNER" verify --verbose "$SIGNED_APK"

echo ""
echo "=== Build Complete ==="
echo "Signed APK: $SIGNED_APK"
ls -lh "$SIGNED_APK"
echo ""
echo "To install: adb install -r $SIGNED_APK"
