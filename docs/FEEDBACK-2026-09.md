# September GitHub feedback audit

Scope: all 15 issues open on 2026-09-07, checked again after implementation. Source base:
`13eec283642ad9de1f778ac976bfdaf11bd2064d` (GitHub main). Work is on
`codex/github-feedback-september`; the older F-Droid checkout and its local edits are preserved.
The audit was completed before release authorization. At that point no issue replies, closures,
commits, pushes, store submissions, or releases had been made. The user subsequently authorized
v1.0.12 experimental publication and a separate Play-lane release; the review results below
retain their original verification boundaries.

## Issue-by-issue result

| Issue | Result in this change | Remaining evidence or work |
| --- | --- | --- |
| [#19 Root disconnect](https://github.com/DhananjayBhosale/hilight-studio/issues/19) | Helper heartbeat scheduling and freshness use elapsed time, so a backwards wall-clock correction cannot stop heartbeats. A disconnected running root renderer exposes Retry root and uses bounded rechecks plus existing exact-instance restart safeguards. | Verify on the reporter's Pixel 11 Pro / KernelSU Next. The negative age matches this mechanism, but is not a physical reproduction. |
| [#22 F-Droid](https://github.com/DhananjayBhosale/hilight-studio/issues/22) | Rechecked metadata and job traces, downloaded the actual CI artifact/reference APK, and reproduced a byte-identical signed APK. Formatting and the historical binary mismatch are corrected. | Current upstream CI, merge, and publication remain external acceptance gates. |
| [#28 Stuck physical LED](https://github.com/DhananjayBhosale/hilight-studio/issues/28) | Preserved bounded black-clear passes, exact renderer ownership, fatal fencing, and manual cleanup. Existing cleanup and lifecycle regression suites remain required. | Affected Pixel 11 Pro confirmation is still absent. No additional speculative color workaround. |
| [#32 Discord first alert](https://github.com/DhananjayBhosale/hilight-studio/issues/32) | Retained the v1.0.11 distinction between a screen wake and an unlock. Reporter confirmed the fix on September 6. | No new fix needed; issue state left unchanged. |
| [#36 Face-down mode](https://github.com/DhananjayBhosale/hilight-studio/issues/36) | Retained global/per-rule face gates, unknown-sensor fail-closed behavior, and clarified settings groups. Users confirmed behavior. | Longer-term physical battery measurements remain outstanding. |
| [#40 Separate trigger rules](https://github.com/DhananjayBhosale/hilight-studio/issues/40) | Retained independent notification and while-open rules, including independent saved looks. Reporter confirmed both triggers work. | No new fix needed; issue state left unchanged. |
| [#41 Incoming calls](https://github.com/DhananjayBhosale/hilight-studio/issues/41) | Added an opt-in incoming-call indicator using Android's explicit incoming CallStyle marker. Short leases are renewed while ringing; ongoing/screening updates, removal, disabled settings, listener loss, and failed observations stop owned output. | **Partial app coverage**: Phone/WhatsApp/Teams/Messenger require real notification testing. Apps without the incoming marker are not inferred from text, category, or full-screen intents. No new phone-state permission. |
| [#42 Video tally](https://github.com/DhananjayBhosale/hilight-studio/issues/42) | Investigated recording detection and clarified English/Japanese help. Retained the original one-minute cap and all cleanup limits. | **Unresolved**: camera/microphone activity can begin in the viewfinder. A verified app-specific recording signal and supported-device safety evidence are required. |
| [#45 Charging indicator](https://github.com/DhananjayBhosale/hilight-studio/issues/45) | Added opt-in two-second charging signals every 15 seconds, separate charging/full colors, and adjustable full threshold. Unplugging/disabling cancels only the charging signal. | Physical lighting and battery impact unverified. Normal sleep may delay the timer. |
| [#46 Per-day quiet hours](https://github.com/DhananjayBhosale/hilight-studio/issues/46) | Added optional seven-day schedules. An overnight window belongs to its starting day and carries into the next morning. Legacy daily settings remain the default and are retained when per-day mode is switched off. | Physical timing observation optional; policy and persistence covered by tests. |
| [#47 Delayed-test warnings](https://github.com/DhananjayBhosale/hilight-studio/issues/47) | Known quiet/battery/master/permission blockers appear immediately and remain visible. Guards are checked again when the test fires. Face-up pose before the countdown does not prevent scheduling. | Real notification/LED end-to-end result still depends on access and supported hardware. |
| [#48 DND activation](https://github.com/DhananjayBhosale/hilight-studio/issues/48) | Added an opt-in finite animation on DND off-to-on transitions. Initial state, reconnects, and changes between active DND modes do not masquerade as activation. | It signals DND activation, not proof that Flip to Shhh specifically caused it. |
| [#51 Any-app exclusions](https://github.com/DhananjayBhosale/hilight-studio/issues/51) | Added silent-notification filtering and catch-all app exclusions. Exclusions do not suppress explicit app rules. A silent-filtered specific rule cannot leak through a less-specific fallback. | Device channel/ranking behavior requires representative notification testing. |
| [#53 Pending reminders](https://github.com/DhananjayBhosale/hilight-studio/issues/53) | Added opt-in per-rule one-second pulses at 5–60 second intervals for the latest eligible pending notification. Dismissal, unlock, listener loss, and master disable clear pending work. Receipt order survives clock changes. | Sleep may delay pulses; no permanent wake lock or exact alarm was added. Hardware gaps/release behavior unverified. |
| [#54 Gradient/presets in rules](https://github.com/DhananjayBhosale/hilight-studio/issues/54) | Rules can copy complete saved looks, including gradient endpoints and per-LED colors. Gradient endpoint editing and complete previews are supported. Preset edits/deletion do not change copied rules. | Physical color output remains unverified. Legacy one-color gradients and random timing are preserved. |

## Preservation and verification

- Existing rules, master switch, daily quiet hours, privacy limits, renderer duty limits, and
  notification/while-open separation remain. New features default off.
- Renderer implementation revision advances from 5 to 7 so old privileged processes cannot be
  silently reused after installing this code. The release preparation bumps the APK to version 1.0.12, code 13.
- The full repository gate is `./gradlew --no-daemon :app:testDebugUnitTest :app:build :app:lint`.
  Standalone helper compilation and optimized APK entry-point checks are separate gates.
- Final repository gate passed: **322 tests, zero failures/errors; debug and optimized unsigned
  release APK builds passed; lint reported zero errors and 47 warnings**. Standalone helper DEX
  compilation passed. The optimized APK retains `AdbHelper` and `HiLightUserService` entry points.
- Regression tests cover rule/preset migration, silent filtering and precedence, exclusions,
  reminder ordering/removal/intervals, quiet-window boundaries, clock corrections, legacy helper
  compatibility, and the existing renderer lifecycle/cleanup contracts.
- Emulator QA covers settings and editor behavior only. An API 37 emulator cannot validate the
  proprietary LED hardware, KernelSU behavior, physical battery use, or store acceptance.
- API 37 emulator checks exercised day-specific settings, overnight explanation, device signal
  controls, immediate blocked self-tests, app exclusions, reminder settings, and copying a saved
  gradient into a rule. Large-font label layout was corrected so switches and slider values fit.
- The earlier tested debug APK update installed successfully on the emulator with `adb install -r`. Saved per-day hours, legacy
  schedule mode, full gradient preset/rule, silent filtering, 15-second reminders, and Camera
  exclusion survived the update. Final large-font screenshots show contained switches and a
  visible interval value. The app remained running; optional DND trigger testing was not performed.
- Local evidence is in `build/feedback-checks/` and `build/feedback-qa/` (ignored build artifacts).

## F-Droid artifact verification

[Submission !46616](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46616) remains open.
The old upstream [build job 16058106674](https://gitlab.com/fdroid/fdroiddata/-/jobs/16058106674)
compiled successfully, then reported a reference-APK signature-copy mismatch in `classes.dex` and
`assets/dexopt/baseline.prof`. The current reference release asset was uploaded after that failure.
Downloaded the actual job artifact and the current reference: every shared ZIP entry now matches.
Using task-local apksigcopier 1.1.1 reconstructed the current reference APK byte for byte; apksigner
verification also passed with the certificate expected by the metadata.

Reconstructed/reference SHA-256:
`9ed1d1a4a51e7bc813abe9274670c551f8c4a711f1a0070b3275f389594a8f92`.
The formatting job's expected metadata also matches the current metadata. No speculative build
change is needed for those historical failures. Evidence is in
`build/feedback-checks/fdroid/verification.json`. The current fork pipeline has no jobs proving
successful current upstream CI; merge and catalog publication remain unconfirmed.

## Final independent recheck and reply readiness

Re-fetched all open issues and their latest comments: the same 15 remain open. Checked the
verified source manifest before review; no source had changed since the previous full gate.
Independent reviews covered device-signal ownership/DND, filtering and reminder lifecycle,
and saved-look persistence. Local review also checked root retry and quiet-schedule boundaries.

The recheck found and fixed one additional defect: losing the notification listener could turn
an unknown DND state into permission to play a charging signal. With Respect DND enabled,
unknown DND now suppresses output until a fresh state arrives. The UI only says DND is active
when that is actually known; reconnecting into active DND does not emit a false activation.
A regression covers startup, disconnect, reconnect and DND-off recovery. English and Japanese
charging help now explain this notification-access dependency when Respect DND is enabled.

The prior emulator QA remains evidence for unchanged editor/persistence behavior; the final
DND correction has source/test evidence and requires device transition testing. The connected
Pixel's installed v1.0.11 Wave check is a baseline only, not a test of this new build. Android
reported animated colors followed by all-zero colors and no session; it does not establish
physical darkness for the affected #28 hardware. New signed-build installation remains pending.

| Issues | Accurate status for a later reply |
| --- | --- |
| #32, #40 | Already fixed in a published version and confirmed by the reporter. |
| #36 | Face-down behavior already confirmed working; battery feedback is still outstanding. |
| #19 | Implemented a clock-related heartbeat correction and bounded retry recovery. Ask the affected KernelSU user to verify; do not claim their reboot/disconnect case is proven fixed. |
| #45, #46, #47, #48, #51, #54 | Requested behavior implemented and locally checked. After a test build is available, describe the controls and ask users to confirm their exact scenarios. |
| #53 | Implemented repeat pulses for pending notifications, with finite output and sleep-delayed scheduling. Explain that this is not a guarantee of uninterrupted pulses during Doze. |
| #41 | Partial incoming-call support using explicit Android incoming-call markers. Ask for app-specific testing; do not claim universal Phone/VoIP support. |
| #28 | Existing mitigation retained; affected-device confirmation still required. No new definitive fix claim. |
| #42 | Investigated but unresolved; recording-only detection and removal of the cap were not implemented. |
| #22 | Artifact reproducibility verified; F-Droid publication still pending external acceptance. |

No reply draft should direct users to v1.0.11 to test the new changes: they belong to v1.0.12. Once an exact signed build is available, link that build and its version in
any testing request. No comments or issue-state changes were sent during this recheck.

## Release upgrade correction

The signed Pixel upgrade exposed an AUTO-routing defect after exact old Shizuku exit: a healthy
successor connected, but the pending handoff still waited for the ADB fallback. Restarting only
the app recovered it. A focused correction now prefers the validated Shizuku successor only
after exact source exit and before any fallback cleanup has started. It retains the normal
fresh successor-cleanup gate and explicit transport choices. An independent review found no
additional issue; regression tests cover rejection cases. Renderer revision 7 forces a fresh
replacement of the already installed development revision 6 for device verification without
removing or downgrading user data. Final physical results are recorded with the release evidence.

## Published GitHub result

[v1.0.12 experimental](https://github.com/DhananjayBhosale/hilight-studio/releases/tag/v1.0.12-experimental)
is published from `985cc7e90b61865d893009db2faa729a00f4887b`. Final verification passed
323 tests, debug/release builds, lint (zero errors, 47 warnings), helper compilation and
[hosted CI](https://github.com/DhananjayBhosale/hilight-studio/actions/runs/34143387587).
The signed release retains the privileged entry points and the existing release certificate.
The downloaded GitHub APK matches SHA-256
`8efe199502b690cbc3364d25ba904e858d9ee6e9fd0ca97f534a2c8418ba7bf5`.

On the connected Pixel 11 Pro XL, an in-place final update replaced a live revision-6 Shizuku
renderer with revision 7 automatically, without force-stop or manual reconnection. The original
Airtel rule and master/ambient settings survived. Wave animated and then the framework reported
zero colors and no light sessions; scoped app/renderer crash buffers were empty. Temporary
notification-test rule, notification and listener permission were removed/restored. A self-test
matched its rule on the preceding revision-6 build. This does not prove physical darkness on
the affected Pixel 11 Pro, actual calling-app coverage, or root/KernelSU recovery. The Play
edition's release is tracked separately in the existing PlayStore Release task.
