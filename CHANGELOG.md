# Changelog

All notable changes to HiLight Studio are documented here.

## [Unreleased]

- Added typed entry on time sliders: tapping the value badge opens a dialog to type the time in
  seconds, for values the hundredth-stepped drag is too coarse to land on. Typed values are clamped
  to the slider's range, so the long-duration safety gates still apply.

## [1.0.8-experimental] - 2026-08-25

- Released the light session whenever a rendered frame is dark, so an idle or temporarily dark
  HiLight effect no longer masks Android's own effects such as Gemini lighting.
- Restored saved **While open** rules when Android reconnects HiLight after a reboot or the app
  process returns, and replaced the ten-second event snapshot with retained activity lifecycle
  tracking. Already-open apps and temporary system overlays are now handled reliably. Usage access
  remains required.
- Forced a second hardware-level black update before releasing an animated alert, preventing the
  Pixel 11 light driver from leaving one LED latched in the alert's last colour.
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
