GITHUB APK BUILD - PRIVATE CHARACTER CHAT v5.0

This package has two functional files:
.github/workflows/build-apk.yml
tools/build-android.sh

Extract this ZIP and upload .github and tools at the existing repository root,
replacing matching files. Do not delete app, your signing key, or the Gradle files.
The complete v5.0 app source and tests must already be in the repository.

Alternatively, edit the existing .github/workflows/build-apk.yml and replace its
contents with the downloaded build-apk.yml. Keep its existing path unchanged.
Do not create .github/workflows/.github/workflows/.

Commit to main. A new build is triggered automatically. For a manual run use:
Actions > Build Android APK > Run workflow > main.

On a successful workflow RUN SUMMARY, download the PrivateCharacterChat-debug
artifact. Extract it to get app-debug.apk. Full build logs and test reports are
in the android-build-log artifact. Do not upload model files to GitHub.

This workflow installs Java 17, Gradle 8.9 and Android SDK packages; runs the
project unit tests; builds a debug APK using the existing signing configuration;
checks the native libraries, APK payload and signature; and uploads the APK only
when all those steps succeed.

Validation here: YAML structure and embedded Bash syntax checked. The GitHub
hosted Android build was not executed from this session.
