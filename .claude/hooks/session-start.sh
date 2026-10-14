#!/bin/bash
# Prepares a Claude Code cloud session to build/lint the Android app:
# installs the Android SDK (if missing), writes local.properties and warms the
# Gradle cache. Idempotent and non-interactive.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-9862592_latest.zip"
SDK_PACKAGES=("platforms;android-35" "build-tools;35.0.0" "platform-tools")

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "Installing Android command-line tools into $ANDROID_HOME" >&2
  tmp="$(mktemp -d)"
  curl -sSfL --retry 4 -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q -o "$tmp/clt.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

missing=()
for pkg in "${SDK_PACKAGES[@]}"; do
  [ -d "$ANDROID_HOME/${pkg//;//}" ] || missing+=("$pkg")
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "Installing SDK packages: ${missing[*]}" >&2
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" "${missing[@]}" >/dev/null
fi

echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=\"$ANDROID_HOME\"" >> "$CLAUDE_ENV_FILE"
  echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\"" >> "$CLAUDE_ENV_FILE"
fi
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

# Warm the Gradle wrapper, plugin and dependency caches (also runs the license
# gate). Maven Central sometimes answers 429, so retry with backoff.
cd "$PROJECT_DIR"
for attempt in 1 2 3 4; do
  if ./gradlew :app:assembleDebug --console=plain -q >/tmp/pocket-transpose-warmup.log 2>&1; then
    echo "Gradle warm-up build succeeded" >&2
    exit 0
  fi
  if ! grep -q "429" /tmp/pocket-transpose-warmup.log; then
    break
  fi
  echo "Maven rate limit (429); retrying in $((attempt * 30))s" >&2
  sleep $((attempt * 30))
done

# A failed warm-up must not block the session; the log explains why.
echo "Gradle warm-up build failed; see /tmp/pocket-transpose-warmup.log" >&2
tail -20 /tmp/pocket-transpose-warmup.log >&2 || true
exit 0
