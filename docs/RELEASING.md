# Releasing HiLight+

GitHub releases are experimental prereleases signed with HiLight+'s permanent release
certificate.

Keep signing files, passwords, and APKs out of Git. Android installs an update only when both APKs use
the same application ID and signing identity.

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add the user-visible changes to `CHANGELOG.md`.
3. Run the release checks:

   ```bash
   ./gradlew --no-daemon :app:testDebugUnitTest :app:build :app:lint
   ```

4. Provide the permanent signing material through the ignored `key.properties` file or the
   `HILIGHT_STORE_FILE`, `HILIGHT_STORE_PASSWORD`, `HILIGHT_KEY_ALIAS`, and
   `HILIGHT_KEY_PASSWORD` environment variables. Never paste their values into logs or commits.
5. Build the optimized, signed release APK:

   ```bash
   ./gradlew --no-daemon :app:assembleRelease
   ```

6. Refuse the release if Gradle produced `app-release-unsigned.apk`. Copy the signed APK outside the
   repository and name it `HiLight-Studio-v<version>.apk`.
7. Verify the certificate, privileged entry points, and SHA-256 digest:

   ```bash
   apksigner verify --verbose --print-certs HiLight-v<version>.apk
   "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" dex packages HiLight-v<version>.apk | grep -E 'com.hilight.core.AdbHelper|com.grimxero.hilightplus.HiLightUserService'
   shasum -a 256 HiLight-v<version>.apk
   ```

8. Install it over the previous permanently signed release on a supported Pixel. Verify root when a
   rooted device is available, plus Shizuku, ADB, one notification rule, and one privacy activity
   rule.
9. Create an annotated `v<version>-experimental` tag, push `main` and the tag, then create a GitHub
   prerelease with the APK attached.
10. Copy the matching changelog entry into the release notes and include the SHA-256 digest.

Do not upload an unsigned APK, signing material, or an APK signed by a different identity.
