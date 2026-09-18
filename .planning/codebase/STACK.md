# Stack

- Android app: Kotlin and Java, Jetpack Compose, API 37 only. Evidence: `app/build.gradle.kts`.
- Build: Gradle 9.6.1, AGP 9.3.1, JDK 17 source/target. Evidence: `gradle/wrapper/gradle-wrapper.properties`, `build.gradle.kts`.
- Privileged transport: Shizuku 13.1.5 or an ADB/root `app_process` helper. Evidence: `app/src/main/java/com/hilight/studio/Backends.kt`, `core/src/com/hilight/core/AdbHelper.java`.
- Hardware API: hidden `ILightsManager` binder reflected by `core/src/com/hilight/core/LightsBackend.java`.
- Tests: JUnit 4 local JVM tests under `app/src/test`.

Verification entrypoint: `ANDROID_HOME=<sdk> ./gradlew --no-daemon :app:testDebugUnitTest`.
