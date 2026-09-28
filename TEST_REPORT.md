# Private Character Chat 5.0 — actual validation report

Date: 2026-09-28 UTC. Project: Local Circle / Private Character Chat.

## Result

**No APK was produced. Full Android compilation did not start.**
The build preflight returned exit code 2 because Gradle was not installed. The
Android SDK, sdkmanager, aapt2 and apksigner were also absent. Attempts to fetch
Gradle and Android command-line tools returned curl exit code 6 (DNS resolution
failure). See `build-evidence/APK_BUILD_ATTEMPT.txt` and
`build-evidence/android-build.log` for the actual command output.

A GitHub build-runner connector was searched and suggested but was not installed
at the last check. No private repository was accessed or changed, and no remote
Actions build was started or downloaded in this session.

## Executed checks

| Check | Observed result | Scope |
|---|---|---|
| Existing core regressions | 37 passed | Real JVM Kotlin compilation and execution; host JSON fixture |
| New social regressions | 64 passed | Core data, policy, direction, photo and context behavior; host only |
| Native orchestration regressions | 5 passed | Real coroutines/locks with a **fake inference backend** |
| Production Kotlin syntax | 29 files parsed | Syntax only; does not resolve Android/library types |
| Android XML | 11 files parsed | XML well-formedness, not AAPT/resource linking |
| Existing keystore | keytool inspection succeeded | Key is byte-identical to supplied v4.0; not an APK signature |
| Android shell build script | bash syntax passed; execution blocked | No Gradle tasks ran |

## New regression coverage

The 64 social tests include identity and style serialization; independent cast
profiles; exact comment reply targets; no cross-post context leakage; preserving
full storage beyond 500 messages; retrieving an explicitly selected older target;
bounded context size; comment/descendant deletion; mood Off, Manual and Automatic;
scene overlay/persistence/clear/cast/user-role behavior; one-reply directions;
internal reasoning and action-only output rejection; speaker-boundary handling;
unfinished native-limit output rejection; and appropriate photo attachment rules.

Photo tests include opt-in public sharing, refusal over marker, user prohibition,
clarification and deferral, positive acceptance, cooldown/repetition avoidance,
no random substitution on specific unmatched requests and photo-claim detection.
All nine typing styles are checked for preservation of names, numbers and negation.

The five lock tests verify shared simultaneous load, serialized generation,
unload-after-generation, no reload for unchanged settings, and atomic model
selection + completion across competing callers. They do not test actual tokens,
GGUF compatibility, CPU throughput or Android lifecycle behavior.

## Evidence files

- `tests/core-v5-final.log`, exit status in `tests/core-v5-final.exit`.
- `tests/native-v5-final.log`, exit status in `tests/native-v5-final.exit`.
- `tests/android-syntax-v5.log`.
- `tests/xml-v5.log`.
- `build-evidence/signing-key-certificate.txt`.
- `build-evidence/key-preservation.txt`.
- `build-evidence/APK_BUILD_ATTEMPT.txt`.

Core tests compile production pure Kotlin with the included host JSON fixture.
The Gradle JUnit bridge instead uses its declared `org.json` test dependency;
that Android/Gradle invocation has not run here.

## Not tested / not established

- Full Kotlin type checking against Android SDK and the actual native AAR.
- APK linking, APK signature, install/update, Android process/lifecycle behavior.
- Real keyboard layout, scrolling, tap targets, photo picker or EXIF device behavior.
- Android Keystore / AtomicFile roundtrip on device or cross-version migration.
- Foreground-service notifications, device battery policies or process death.
- Actual local model quality, public roleplay adherence, image selection quality,
  latency, token speed, memory consumption or stability on the user's phone.
- Independent security audit of the precompiled third-party native library.

The source implements the requested controls. Tests of deterministic routing and
prompt construction are not proof that an LLM obeys every instruction. Complete
Android build and physical-device testing are required before calling it working.
