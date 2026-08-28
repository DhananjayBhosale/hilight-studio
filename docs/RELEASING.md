# Releasing HiLight Studio

GitHub releases are experimental prereleases signed with HiLight Studio's permanent release
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
   # The github flavour is what ships on GitHub and F-Droid: it is the one that can look up its own
   # releases. Use :app:assemblePlayRelease (or :app:bundlePlayRelease) for a Play upload, which has
   # neither the update check nor the INTERNET permission.
   ./gradlew --no-daemon :app:assembleGithubRelease
   ```

6. Refuse the release if Gradle produced `app-github-release-unsigned.apk`. Copy the signed APK outside the
   repository and name it `HiLight-Studio-v<version>-experimental-signed.apk`.
7. Verify the certificate, privileged entry points, and SHA-256 digest:

   ```bash
   apksigner verify --verbose --print-certs HiLight-Studio-v<version>-experimental-signed.apk
   "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" dex packages HiLight-Studio-v<version>-experimental-signed.apk | grep -E 'com.hilight.core.AdbHelper|com.hilight.studio.HiLightUserService'
   shasum -a 256 HiLight-Studio-v<version>-experimental-signed.apk
   ```

8. Install it over the previous permanently signed release on a supported Pixel. Verify root when a
   rooted device is available, plus Shizuku, ADB, one notification rule, and one privacy activity
   rule.
9. Create an annotated `v<version>-experimental` tag, push `main` and the tag, then create a GitHub
   prerelease with the APK attached.
10. Copy the matching changelog entry into the release notes and include the SHA-256 digest.

Do not upload an unsigned APK, signing material, or an APK signed by a different identity.
