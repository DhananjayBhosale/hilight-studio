# Testing

- Pure renderer/safety/lifecycle tests: `app/src/test/java/com/hilight/core`.
- App matching/state tests: `app/src/test/java/com/hilight/studio`.
- Focused unit command: `ANDROID_HOME=<sdk> ./gradlew --no-daemon :app:testDebugUnitTest`.
- Compile/lint command: `ANDROID_HOME=<sdk> ./gradlew --no-daemon :app:assembleDebug :app:lintDebug`.
- Standalone renderer command: `ANDROID_HOME=<sdk> ./scripts/build-helper.sh`.
- CI runs tests, full build, lint, helper build, and optimized-entrypoint inspection: `.github/workflows/android.yml`.

Host tests can prove session calls and state transitions. Only a Pixel 11 physical run can prove the panel went dark; `dumpsys lights` and renderer status remain framework evidence.
