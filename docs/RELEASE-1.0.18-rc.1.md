# HiLight Studio 1.0.18-rc.1

Version code 20. GitHub package: `com.hilight.studio`. The separately built Play edition uses `com.highlight.studio` and targets Open testing.

Changes: charging style selection, guarded Shizuku reconnection after old-renderer exit, cancellation of queued reconnection, and root recovery around cached status and cold discovery. Existing rules, settings, renderer ownership checks and charging display limits are preserved. No new permissions were added by the shared patch.

Verification: 388 unit tests passed with no failures, errors or skips. Debug and signed release builds passed. Lint passed with 0 errors and 61 existing warnings. The standalone renderer compiled to DEX. The signed APK is non-debuggable, uses the permanent certificate, includes both privileged renderer entry points, and passes 16 KB ZIP alignment verification. Matching R8 mapping and full logs are retained alongside local release artifacts.

APK SHA-256: `2c698215ee6553e4c9f9ff26ae546145dce9721d8ecf220951b585a5b211401d`.

Limits: no physical device was connected. Shizuku and KernelSU behavior still require affected-device confirmation. The reported KernelSU cleanup timeout is not confirmed resolved. Android sleep can delay periodic charging indicators. This remains an experimental GitHub prerelease; Play upload, review and availability are separate evidence tiers.

If startup failures, lost settings or persistent LEDs are reported, stop further rollout and investigate before publishing a higher-version correction. Keep the preceding release available; do not advise clearing app data as a workaround.
