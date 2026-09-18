# Concerns

- Current reports prove a persistent physical LED after framework/session release, but do not include Shizuku renderer logs or HAL traces.
- The released v1.0.8 code could not clear again after closing its session; off/handoff/stop paths silently wrote nothing. The fix branch adds a bounded session-reclaim retry.
- Shizuku daemons outlive the app. Installed APK version is not proof of loaded renderer code; expose renderer build identity in status/logs.
- API 37 Pixel `LightsService` and the vendor light HAL are unavailable locally. API 36 emulator/AOSP behavior is supporting evidence only.
- Rendering uses a fixed 33 ms loop while the backend records the maximum advertised update period only for black-clear spacing. Do not change cadence without Pixel evidence and safety-accounting updates.
- Physical Pixel 11 validation remains required before closing issues 28/29 or publishing a fixed release.
