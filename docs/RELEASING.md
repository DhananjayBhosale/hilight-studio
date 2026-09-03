# Releasing HiLight Studio

GitHub releases are experimental prereleases signed with HiLight Studio's permanent release
certificate.

Keep signing files, passwords, and APKs out of Git. Android installs an update only when both APKs use
the same application ID and signing identity.

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add the user-visible changes to `CHANGELOG.md`.
3. Run the release checks:

   ```bash
   ./gradlew --no-daemon :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest :app:build :app:lint
   ```

4. On the maintainer Mac, build the optimized APK with the permanent keystore and passwords already
   saved by Android Studio in macOS Keychain:

   ```bash
   hilight-release
   ```

   The script retrieves both passwords without printing them, passes them only to a no-daemon Gradle
   process, rejects an unsigned or differently signed APK, and copies the verified artifact to
   `~/Library/Application Support/HiLight Studio/releases/v<version>/`. To install the verified APK
   after building, add `--device <serial>`; installation then uses Android CLI delta deployment.
   Run the command from the HiLight checkout being released. The repository copy remains available
   as `./scripts/build-signed-release.sh` if the global helper is not installed.

   A different machine can still provide an ignored `key.properties` file or the
   `HILIGHT_STORE_FILE`, `HILIGHT_STORE_PASSWORD`, `HILIGHT_KEY_ALIAS`, and
   `HILIGHT_KEY_PASSWORD` environment variables. Never paste their values into logs or commits.
5. Refuse the release if Gradle produced `app-github-release-unsigned.apk`.
6. Verify the certificate, privileged entry points, and SHA-256 digest:

   ```bash
   apksigner verify --verbose --print-certs HiLight-Studio-v<version>-experimental-signed.apk
   "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" dex packages HiLight-Studio-v<version>-experimental-signed.apk | grep -E 'com.hilight.core.AdbHelper|com.hilight.studio.HiLightUserService'
   shasum -a 256 HiLight-Studio-v<version>-experimental-signed.apk
   ```

7. Install it over the previous permanently signed release on a supported Pixel. Verify root when a
   rooted device is available, plus Shizuku, ADB, one notification rule, and one privacy activity
   rule.
8. Create an annotated `v<version>-experimental` tag, push `main` and the tag, then create a GitHub
   prerelease with the APK attached.
9. Copy the matching changelog entry into the release notes and include the SHA-256 digest.

Do not upload an unsigned APK, signing material, or an APK signed by a different identity.
