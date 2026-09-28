#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/pcc4-core-test"
mkdir -p "$OUT"
SRC="$ROOT/app/src/main/java/com/localcharacter/chat"
FILES=(SocialBrain SocialModels SocialReplyPolicy PhotoSharePolicy Models RoleplaySettings SceneDirector TurnInterpreter ReplyPipeline BrainEngine MemoryManager ContextUnderstandingEngine FastContextCache ConversationEditor RelationshipPolicy PerformanceController CharacterPhotoSelector HumanTypingStyle EngineReply)
ARGS=(); for name in "${FILES[@]}"; do ARGS+=("$SRC/$name.kt"); done
kotlinc "$ROOT/tests/support/org/json/Json.kt" "${ARGS[@]}" "$ROOT/tests/core/CoreRegression.kt" "$ROOT/tests/core/SocialRegression.kt" -include-runtime -d "$OUT/tests.jar"
java -cp "$OUT/tests.jar" CoreRegressionKt
java -cp "$OUT/tests.jar" SocialRegression
