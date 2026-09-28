#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/pcc4-core-test"
KOTLIN_HOME="$(cd "$(dirname "$(command -v kotlinc)")/.." && pwd)"
COROUTINES="$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar"
[ -f "$OUT/tests.jar" ] || bash "$ROOT/tools/test-core.sh"
kotlinc -cp "$OUT/tests.jar:$COROUTINES" \
 "$ROOT/tests/native-support/dev/ffmpegkit/llama/Llama.kt" \
 "$ROOT/app/src/main/java/com/localcharacter/chat/LocalLlmEngine.kt" \
 "$ROOT/tests/NativeLockRegression.kt" -d "$OUT/native-lock.jar"
java -cp "$OUT/native-lock.jar:$OUT/tests.jar:$COROUTINES" NativeLockRegression
