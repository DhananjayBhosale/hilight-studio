# Integrations

- Android `NotificationListenerService`: per-app and per-conversation triggers.
- Shizuku: daemon user service running as shell/root, identified by the configured version code.
- ADB/root: `AdbHelper` reads `state.json` and writes `helper_status.json` in the app-owned external-files directory.
- Android `ILightsManager`: session arbitration and per-light state writes.
- GitHub Releases API: manual update check only.

No telemetry or background network integration is present. GitHub issue reports and attached diagnostics are external evidence, not production telemetry.
