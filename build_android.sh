#!/usr/bin/env bash
# Builds a debug Android APK from the GDevelop project in this repo, entirely
# from the command line (no GDevelop Desktop app / GUI required).
#
# Pipeline:
#   GDevelop project (.json)
#     -> gdexport (headless GDCore, npm package "gdexporter") --build cordova
#     -> Cordova project (platforms/android added)
#     -> Gradle build (via cordova-android)
#     -> APK
#
# Usage:
#   ./build_android.sh              # build debug APK into dist/
#   ./build_android.sh --install    # also `adb install -r` onto a connected device
#
# Requirements (checked below, with guidance if missing):
#   - Node.js + npm
#   - Java (JDK 17+ recommended)
#   - An Android SDK (ANDROID_HOME/ANDROID_SDK_ROOT), with a recent
#     build-tools/platform installed. If unset, this script will try to
#     fall back to an Android SDK bundled with a local Unity Editor install
#     (Unity's Android module ships a full SDK/NDK/JDK), since that's a
#     common thing to already have on a machine used for Android game dev.
#   - Gradle. If not found on PATH, this script downloads a pinned version
#     into build/gradle-cache/ (one-time, ~140MB) to bootstrap the Cordova
#     project's own Gradle wrapper.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$ROOT/build"
DIST_DIR="$ROOT/dist"
PROJECT_FILE="$ROOT/Flats 6 Rebooted.json"
GRADLE_VERSION="8.13"

INSTALL_AFTER=0
for arg in "$@"; do
  case "$arg" in
    --install) INSTALL_AFTER=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log() { echo ">> $*"; }

# --- 1. Basic tool checks -----------------------------------------------
command -v node >/dev/null || { echo "node not found. Install Node.js (v18+)." >&2; exit 1; }
command -v npm >/dev/null || { echo "npm not found. Install Node.js (v18+)." >&2; exit 1; }
command -v java >/dev/null || { echo "java not found. Install a JDK (17+ recommended)." >&2; exit 1; }
log "node $(node --version), npm $(npm --version), $(java -version 2>&1 | head -1)"

[[ -f "$PROJECT_FILE" ]] || { echo "Could not find '$PROJECT_FILE'." >&2; exit 1; }

# --- 2. Resolve Android SDK ----------------------------------------------
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  # Fall back to a Unity-bundled Android SDK if one is present, since Unity's
  # Android module ships a complete standalone SDK/NDK/JDK that works fine
  # for this too.
  CANDIDATE="$(find "$HOME"/Unity/Hub/Editor/*/Editor/Data/PlaybackEngines/AndroidPlayer/SDK -maxdepth 0 2>/dev/null | head -1 || true)"
  if [[ -n "$CANDIDATE" ]]; then
    export ANDROID_HOME="$CANDIDATE"
    log "ANDROID_HOME not set; using Unity-bundled SDK: $ANDROID_HOME"
  else
    echo "ANDROID_HOME/ANDROID_SDK_ROOT is not set and no Unity-bundled SDK was found." >&2
    echo "Install the Android SDK (e.g. via Android Studio or sdkmanager) and set ANDROID_HOME." >&2
    exit 1
  fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
[[ -d "$ANDROID_HOME/platform-tools" ]] && export PATH="$ANDROID_HOME/platform-tools:$PATH"
CMDLINE_TOOLS_BIN="$(find "$ANDROID_HOME/cmdline-tools" -maxdepth 2 -type d -name bin 2>/dev/null | head -1 || true)"
[[ -n "$CMDLINE_TOOLS_BIN" ]] && export PATH="$CMDLINE_TOOLS_BIN:$PATH"
log "Using Android SDK: $ANDROID_HOME"

# --- 3. Resolve Gradle (only needed to bootstrap Cordova's own wrapper) --
if ! command -v gradle >/dev/null; then
  GRADLE_CACHE="$BUILD_DIR/gradle-cache/gradle-$GRADLE_VERSION"
  if [[ ! -x "$GRADLE_CACHE/bin/gradle" ]]; then
    log "Gradle not found on PATH; downloading Gradle $GRADLE_VERSION (one-time, ~140MB)..."
    mkdir -p "$BUILD_DIR/gradle-cache"
    curl -sSL -o "$BUILD_DIR/gradle-cache/gradle.zip" \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
    unzip -q -o "$BUILD_DIR/gradle-cache/gradle.zip" -d "$BUILD_DIR/gradle-cache"
    rm -f "$BUILD_DIR/gradle-cache/gradle.zip"
  fi
  export PATH="$GRADLE_CACHE/bin:$PATH"
fi
log "Using $(gradle --version | grep Gradle)"

# --- 4. Install the headless GDevelop exporter + Cordova CLI -------------
mkdir -p "$BUILD_DIR/tools"
if [[ ! -d "$BUILD_DIR/tools/node_modules" ]]; then
  log "Installing gdexporter + cordova (first run only)..."
  (cd "$BUILD_DIR/tools" && npm init -y >/dev/null && npm install gdexporter cordova >/dev/null)
fi

# --- 5. Export the GDevelop project to a Cordova project -----------------
EXPORT_DIR="$BUILD_DIR/exported_cordova"
log "Exporting GDevelop project (headless GDCore) -> Cordova project..."
(cd "$BUILD_DIR/tools" && node_modules/.bin/gdexport \
  --project "$PROJECT_FILE" \
  --out "$EXPORT_DIR" \
  --build cordova)

# --- 6. Add our local plugins (native gamepad bridge, etc.) --------------
CORDOVA="$BUILD_DIR/tools/node_modules/.bin/cordova"
for plugin_dir in "$ROOT"/cordova-plugins/*/; do
  [[ -f "$plugin_dir/plugin.xml" ]] || continue
  plugin_id="$(basename "$plugin_dir")"
  log "Adding local plugin: $plugin_id"
  (cd "$EXPORT_DIR" && "$CORDOVA" plugin add "$plugin_dir" --nofetch)
done

# --- 7. Add the Android platform (idempotent) and build -------------------
if [[ ! -d "$EXPORT_DIR/platforms/android" ]]; then
  log "Adding Cordova Android platform..."
  (cd "$EXPORT_DIR" && "$CORDOVA" platform add android)
else
  # Platform already existed (e.g. re-run) -- make sure newly-added plugins
  # actually get installed into it.
  (cd "$EXPORT_DIR" && "$CORDOVA" prepare android)
fi

# --- 7b. Sanity-check: confirm local plugin source files actually landed
# in the generated Android project. cordova can silently no-op a
# <source-file> replacement (e.g. wrong target-dir), so fail loudly instead
# of shipping a build that's missing the feature.
GAMEPAD_MAIN_ACTIVITY="$EXPORT_DIR/platforms/android/app/src/main/java/com/flats/mtsyntho/MainActivity.java"
if [[ -d "$ROOT/cordova-plugins/cordova-plugin-native-gamepad" ]]; then
  if ! grep -q "dispatchGenericMotionEvent" "$GAMEPAD_MAIN_ACTIVITY" 2>/dev/null; then
    echo "cordova-plugin-native-gamepad did not apply: $GAMEPAD_MAIN_ACTIVITY is still the stock MainActivity." >&2
    exit 1
  fi
fi

log "Building debug APK (Gradle, first run is slow — bootstraps its own wrapper)..."
(cd "$EXPORT_DIR" && "$CORDOVA" build android --debug)

BUILT_APK="$EXPORT_DIR/platforms/android/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$BUILT_APK" ]] || { echo "Build did not produce an APK at the expected path: $BUILT_APK" >&2; exit 1; }

mkdir -p "$DIST_DIR"
OUT_APK="$DIST_DIR/FLATS6-debug.apk"
cp "$BUILT_APK" "$OUT_APK"
log "APK ready: $OUT_APK"
sha256sum "$OUT_APK" || true

if [[ "$INSTALL_AFTER" -eq 1 ]]; then
  command -v adb >/dev/null || { echo "adb not found; cannot --install." >&2; exit 1; }
  log "Installing on connected device..."
  adb install -r "$OUT_APK"
fi
