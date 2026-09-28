# Build this exact source; do not substitute an implementation plan

Preserve `applicationId`, `app/local-chat.keystore`, model paths and existing chat
data. Do not regenerate the signing key and do not erase data to make a migration
appear successful.

1. Provide Java 17, Gradle 8.9 and an Android SDK with platform 35/build tools.
2. Run `bash tools/build-android.sh` from the repository root.
3. Fix any actual Android type/resource errors; rerun tests and build.
4. Keep the retained core and social tests. Do not change assertions just to pass.
5. The script must finish APK payload validation and `apksigner verify`.
6. Deliver the actual `app/build/outputs/apk/debug/app-debug.apk`, its SHA-256,
   signature evidence and test/build logs. Do not rename a ZIP or claim that
   syntax tests are an Android build.

Before release, execute the unperformed checks in ANDROID_QA_CHECKLIST.md. Network
access is needed to acquire Android/Gradle dependencies on the first build, but
ordinary use of a downloaded local model should not require an online AI API.
