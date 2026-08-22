# Changelog

All notable changes to HiLight Studio are documented here.

## [Unreleased]

## [1.1.0-experimental]

- Added built-in setup: HiLight Studio now pairs with the phone's own Wireless debugging service
  and starts its renderer itself, so no computer, no Shizuku install, and no typed shell commands
  are needed.
- Pairing is entered from a reply notification, so the Settings pairing dialog can stay open while
  the six-digit code is sent.
- The renderer is re-attached automatically after a reboot or an app update, and whenever the app
  finds it is no longer running.
- Added `INTERNET`, `ACCESS_NETWORK_STATE`, and `ACCESS_LOCAL_NETWORK`, used only to find and reach
  the phone's own debug daemon, and `RECEIVE_BOOT_COMPLETED` for the automatic reconnect. Android 17
  requires the local-network permission at runtime and asks for it on first launch.
- Shizuku and the two-command ADB flow are still available as alternatives.

## [1.0.4-experimental] - 2026-08-20

- Released the first APK signed with HiLight Studio's permanent release certificate, establishing
  a stable update identity for future GitHub releases.
- Released the HiLight session as soon as the array goes dark, so system effects such as calls and
  Gemini can resume without waiting for the helper to stop or the phone to reboot.

## [1.0.3-experimental] - 2026-08-20

- Fixed notification alerts that could leave the LEDs lit indefinitely, end early after an
  unrelated settings update, or continue after the phone was unlocked.
- Added a **Pause in Battery Saver** option and changed the default low-battery pause from 20% to
  10%.
- Reset the brightness taper after the array has been dark, so a newly armed effect starts at full
  brightness.
- Made renderer handoff explicit so only one renderer drives the array at a time.
- Changed ADB setup to a two-line reset-then-start flow, with separate commands for PowerShell and
  Windows Command Prompt.

## [1.0.2-experimental] - 2026-08-19

- Corrected the ADB command shown in the app's setup screen.
- Added automated tests for LED duty-cycle, taper, rest, and quiet-hours safety behavior.
- Hardened the release workflow, build verification, and contributor resources.

## [1.0.1-experimental]

- Added the unified HiLight Studio logo across the app and repository.

## [1.0.0-experimental]

- First experimental GitHub release.
