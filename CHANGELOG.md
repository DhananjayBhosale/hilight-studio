# Changelog

All notable changes to HiLight Studio are documented here.

## [1.0.12-experimental] - 2026-09-07

- Fixed helper heartbeat scheduling and freshness across wall-clock corrections, and added safe
  retry for a disconnected root renderer. Renderer implementation revision is now 6.
- Added complete saved presets and gradient endpoints to app rules, preserving existing rule looks.
- Added silent-notification filtering and app exclusions for the Any app rule.
- Added optional brief reminders for pending notifications, with dismissal/unlock cleanup and
  existing quiet/battery guards. Device sleep may delay reminders.
- Added optional day-specific quiet hours while retaining the existing daily schedule.
- Added optional charging indicators and DND activation signals, plus incoming-call indication for
  apps that supply Android's explicit incoming-call notification marker.
- Made delayed notification tests report known blockers before their countdown, including DND.
- Clarified that camera/microphone activity does not identify video recording. The one-minute
  activity limit remains unchanged. See [the complete feedback audit](docs/FEEDBACK-2026-09.md).

## [1.0.11-experimental] - 2026-09-02

- Kept an ordinary notification alert running when the notification itself wakes the screen, while
  still stopping it on unlock and still stopping per-rule **Only while the screen is off** alerts as
  soon as the screen wakes. This addresses the lifecycle sequence reported for Discord's first alert
  and was confirmed working by the reporter on September 6.
- Added a clear message when an in-app preview or end-to-end notification test is currently blocked
  by quiet hours, Battery Saver, low battery, or the applicable face-down guard.
- Split the former **When to stay dark** settings into **When HiLight can glow** and **When HiLight
  should pause**, with each live suppression reason shown beside the section that controls it.
- Added a compact Dhananjay Tech attribution link at the top of Settings.

## [1.0.10-experimental] - 2026-08-29

- Bumped the privileged renderer implementation revision to 5 so Shizuku, ADB, and root restart an
  older revision instead of silently reusing the released v1.0.9 renderer.
- Made cleanup readback treat nonzero RGB as shadowing, RGB-dark but inexact ARGB as Binder-accepted
  and unverified, and only an exact ARGB match as framework-effective. This changes diagnostics only;
  LED writes, cleanup attempts, session release, and timing remain unchanged.
- Ignored late state deliveries after renderer shutdown so they cannot mutate release revisions,
  re-arm cleanup without a render thread, or restart the privacy observer.
- Made the app picker include installed packages remembered from named notifications, even when they
  have no launcher activity. Forgetting remembered chats removes these extra picker entries.
- Made notification and while-open rules discoverably coexist for the same app, and added a safe
  **Copy settings to another app** action for whole-app rules without carrying chat or keyword data.
- Added exact typed entry to time-slider value pills. Typed seconds reject non-finite values and are
  clamped to the slider's currently unlocked range, so duration safety confirmations still apply.
- Added a five-second delayed self-test notification so the screen can be locked before it posts.
- Added **Only while the phone is face down** as both a global guard and an option on individual
  notification rules. The first use requires a shared **Needs testing — use with care** acknowledgement.
  A foreground service watches the gravity/accelerometer signal with hysteresis and a settling window;
  missing, stale, or unavailable readings fail closed and leave the LED array fully off. Lifting the
  phone cancels a gated alert through the normal renderer release path.

## [1.0.9-experimental] - 2026-08-27

- Added a bounded stuck-LED mitigation. After the existing pre-release black sequence, the renderer
  closes its normal session and repeats alpha-black → canonical black through three fresh,
  low-priority sessions. The same three-pass recovery runs before a new renderer reports ready.
- Added a hard renderer compatibility fence: the 1.0.9 candidate uses renderer revision 4, status
  schema 6, and Shizuku service version 1004. Visible/current state is never replayed to an unknown
  or mismatched daemon. A minimal disabled state may be sent to hold output dark while Shizuku removes
  and rebinds a mismatch once, then fails closed if ownership cannot be resolved.
- Split state receipt, render-thread settlement, and full release into separate revisions. Handoffs
  now wait for a closed session and terminal cleanup, while ADB and root helpers use cooperative
  shutdown cleanup before their process exits.
- Required every ADB/root helper launch to carry a valid explicit instance ID and added a singleton
  process lock. After the old process or binder is proven gone, the app makes the new renderer finish
  one disabled cleanup request before replaying desired output. A newly observed bridge instance is
  retargeted and receives the saved state once with `arm=false`, so a restarted helper does not stay
  dark or restart the auto-off clock.
- Added truthful cleanup outcomes for Binder acceptance, framework readback, shadowing, I/O failure,
  and exhaustion. **Copy LED diagnostics** exports only allowlisted lifecycle data, and **Retry LED
  cleanup** runs the black-only three-pass recovery while HiLight is off and idle.
- Enforced the five-minute ambient ceiling in the renderer itself, including raw bridge documents.
- Kept temporary dark frames inside an animation separate from terminal shutdown: a Breathe trough
  closes only the normal visible session, while expiry, master-off, source changes, and a configuration
  that never produced visible output run the full recovery cycle once.

The mitigation never asks an RGB channel to light, but framework acceptance and readback do not prove
that the physical panel switched off. Pixel 11 Pro testing remains required before claiming the
reported hardware latch is eliminated.

## [1.0.8-experimental] - 2026-08-25

- Released the light session whenever a rendered frame is dark, so an idle or temporarily dark
  HiLight effect no longer masks Android's own effects such as Gemini lighting.
- Restored saved **While open** rules when Android reconnects HiLight after a reboot or the app
  process returns, and replaced the ten-second event snapshot with retained activity lifecycle
  tracking. Already-open apps and temporary system overlays are now handled reliably. Usage access
  remains required.
- Sent alpha-only black followed by canonical black before releasing an animated alert. This made
  Android process two distinct black states, but did not eliminate every reported Pixel 11 Pro latch.
- Cleared and released privacy-activity output as soon as microphone or camera use ends, including
  when the array was already idle before that activity started.

## [1.0.7-experimental] - 2026-08-23

- Enabled reproducible, developer-signed builds for the initial F-Droid submission. This release
  contains no app behavior changes from 1.0.6.

## [1.0.6-experimental] - 2026-08-23

- Added a manual **Check for updates** action under Setup. It includes experimental GitHub
  prereleases, reports whether the installed version is current, and opens the matching release page
  when an update is available. It never checks in the background.
- Added **privacy activity rules** for microphone and camera use. A rule can cover any app or one
  selected app, stops immediately when the activity ends, and defaults to 10 seconds lit followed by
  a 10-second cooldown for at most one minute per continuous use. The animation, colours, timing,
  speed, and brightness are all configurable.
- Added **automatic root support**. Rooted phones use a root-owned renderer after the root manager's
  one-time approval, with no Shizuku or ADB setup. Failed or denied root access falls back cleanly to
  the existing setup choices.
- Made renderer handoff fail closed: the next root, Shizuku, or ADB renderer cannot drive the array
  until the previous one has acknowledged an idle state or stopped.
- Enabled R8 code shrinking and optimized resource shrinking for release builds. Removed the duplicate
  full-size launcher bitmap and stored the in-app copy as a smaller lossless WebP, cutting the local
  release artifact from about 45 MB to about 2.7 MB.
- Added release checks that build the standalone helper and prove R8 kept the ADB and Shizuku entry
  points.

## [1.0.5-experimental] - 2026-08-22

- Added **Japanese**. Every user-visible string moved out of the code and into resources, and the app
  now declares its languages, so Android's own per-app language picker can show HiLight in Japanese
  while the rest of the phone stays in English.
- Terms are fixed by a glossary rather than translated string by string, so the same English word does
  not become two Japanese ones. Product names (HiLight, Shizuku, ADB, LED) stay in Latin script, and
  Android's own Japanese is followed for the system features HiLight talks about, so a button and the
  Settings screen it opens agree with each other.
- Two things extraction turned up that were bugs in English too: the Quick Settings tile chose its
  accent colour by comparing a *label* to the word "Rainbow", and the catch-all rule stored its own
  name, so a rule created in one language kept that name in the other.
- Added **per-contact rules**: a colour for one person or one chat, so a message from a chosen contact
  lights the array differently from everything else in the same app. Works with WhatsApp, Google
  Messages, Telegram, Signal, Slack, Discord and anything else that names the sender in its
  notification, and needs no permission beyond the notification access the app already asks for.
- Chats are never typed in. HiLight offers the chats it has already seen, the system contact picker,
  or a "learn the next message" mode that captures the name exactly as the app writes it. A rule
  remembers the chat's stable id on first sighting, so renaming a contact no longer breaks it.
- Added a **notification inspector** under Setup, which shows what HiLight reads from each
  notification and can be copied or shared to explain why a rule is not firing. Message text is never
  shown and never exported.
- Added **Forget remembered chats** under Setup, which clears the remembered chat names without
  touching existing rules.
- Rule cards now show when a rule last matched, so a rule that never fires is visibly a rule that
  never matched rather than an array that is broken.

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
