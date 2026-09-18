# Structure

- `app/src/main/java/com/hilight/studio`: app UI, notification/foreground triggers, state store, and transport clients.
- `core/src/com/hilight/core`: privileged renderer shared by Shizuku, ADB, and root hosts.
- `app/src/main/aidl`: Shizuku binder contract.
- `app/src/test`: host-side unit and regression tests.
- `scripts`: standalone helper build/start tooling.
- `docs/TECHNICAL.md`: hardware and runtime evidence.
- `.github/workflows/android.yml`: public test/build/lint/helper verification.

Entrypoints: `MainActivity`, `NotificationTrigger`, `HiLightUserService`, and `AdbHelper`.
