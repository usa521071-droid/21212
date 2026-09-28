#!/usr/bin/env bash
# Builds an actual signed Android package. No source ZIP is ever renamed to APK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p build-evidence
# Stale evidence must never imply that this invocation succeeded.
rm -f build-evidence/apk.sha256 build-evidence/signature.txt
blocked() { echo "BUILD BLOCKED: $*" | tee build-evidence/android-build.log >&2; exit 2; }
command -v java >/dev/null 2>&1 || blocked "JDK 17 or compatible JDK required."
if [ -f ./gradlew ]; then GRADLE=(bash ./gradlew)
elif command -v gradle >/dev/null 2>&1; then GRADLE=(gradle)
else blocked "Gradle 8.9 is not installed. No Android compilation has occurred."; fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
  SDK="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1)"
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || blocked "Android SDK path missing or invalid (ANDROID_HOME or local.properties)."
set +e
"${GRADLE[@]}" --no-daemon :app:testDebugUnitTest :app:assembleDebug --stacktrace --console=plain 2>&1 | tee build-evidence/android-build.log
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"
APK="app/build/outputs/apk/debug/app-debug.apk"
[ -s "$APK" ] || { echo "Build did not produce an APK." >&2; exit 3; }
python3 - "$APK" <<'CHECK'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    assert z.testzip() is None, "APK CRC failed"
    for name in ("AndroidManifest.xml", "classes.dex"):
        assert name in z.namelist(), f"Missing APK payload: {name}"
    assert any(n.startswith("lib/arm64-v8a/") and n.endswith(".so") for n in z.namelist()), "Missing native inference libraries"
CHECK
SIGNER="$(find "$SDK/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$SIGNER" ]; then SIGNER="$(command -v apksigner || true)"; fi
[ -n "$SIGNER" ] || { echo "APK exists, but apksigner is missing: signature NOT verified." >&2; exit 4; }
"$SIGNER" verify --verbose --print-certs "$APK" | tee build-evidence/signature.txt
sha256sum "$APK" | tee build-evidence/apk.sha256
printf '\nBuilt, payload-checked and signature-verified: %s\n' "$APK"
