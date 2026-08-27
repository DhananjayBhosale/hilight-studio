# Technical deep dive

This is the implementation detail that doesn't belong in the main [README](../README.md): how
the renderer gets privileged access, what the hardware actually is, and what's been verified on a
real device.

## What HiLight actually is

Findings from the device itself, not from the marketing pages:

| Property | Value |
|---|---|
| Hardware | **8 individually addressable RGB LEDs** in the array around the camera flash |
| Framework type | `Light.LIGHT_TYPE_APPLICATION` (`10`), new in API 37 |
| Light ids / ordinals | ids `1..8`, ordinals `0..7` (id `0` is the display backlight and is not exposed by `LightsManager`) |
| Capabilities | `hasRgbControl() = true`, `hasBrightnessControl() = false`, `hasAnimationControl() = true` |
| Min update period | `33 ms` per LED, i.e. ~30 fps |
| HAL | `android.hardware.light` **AIDL version 3** (`vendor.google.lights-service`), AOSP ships v2 |
| System feature name | `AmbientCue`: `/product/overlay/AmbientCueOverlay.apk`, plus `vendor.google.ambience_hub.*` HAL services |
| Stock features | custom colour per favourite contact (Phone by Google, WhatsApp) and a Gemini listening/thinking/responding indicator |

New public API in Android 17 (API 37), all in `android.hardware.lights`:

- `ColorSequence` + `ColorSequence.Builder`: keyframed colour ramps (`addControlPoint(delayMs, color)`,
  `INTERPOLATION_MODE_NONE` / `INTERPOLATION_MODE_LINEAR`)
- `MultiLightEffect` + `Builder`: one `ColorSequence` per LED, with `setIterations()` and `setPreemptive()`
- `LightsRequest.Builder.setEffect(...)`, `Light.hasAnimationControl()`, `Light.getMinUpdatePeriodMillis()`

Underlying binder interface (`ILightsManager`): `getLights()`, `openSession(IBinder, int priority)`,
`setLightStates(token, int[] ids, LightState[])`, `setLightEffect(token, MultiLightEffect)`,
`getLightState(id)`, `getLightSequence(id)`, `closeSession(token)`.

### Why a privileged helper process is required

`android.permission.CONTROL_DEVICE_LIGHTS` is `signature|privileged` on this build, and
`LightsService` enforces it on every call:

```
java.lang.SecurityException: Access denied, requires: android.permission.CONTROL_DEVICE_LIGHTS
  at android.hardware.lights.ILightsManager$Stub.getLights_enforcePermission
```

It is not a changeable permission, so `pm grant` refuses it, and the device is a retail unit with a
locked bootloader (`ro.boot.flash.locked=1`, `verifiedbootstate=green`, no root), so there is no way to
install an app as privileged.

However `android.uid.shell` (uid 2000) **already holds it** (`granted=true`). So the rendering runs in
a process owned by the shell UID. Everything else, including the UI, rules, and notification listener, is a normal
app.

## Architecture

The renderer core (`core/src`) is shared. It can run as root, or as the shell UID through Shizuku or
ADB.

```
HiLight Studio (normal app)                    privileged renderer (uid 0 or 2000)
┌─────────────────────────────────┐            ┌────────────────────────────────────┐
│ Compose UI: Live/Ambient/Apps   │  binder    │ Shizuku: HiLightUserService        │
│ NotificationTrigger (listener)  │ ─────────► │   com.hilight.studio:hilight       │
│ ForegroundWatcher (UsageStats)  │            ├────────────────────────────────────┤
│ Store: layering + rules         │  2 JSON    │ ADB: com.hilight.core.AdbHelper    │
│ Transport: Auto/Root/Shizuku/ADB│ ◄────────► │   run from the installed APK       │
└─────────────────────────────────┘  files     └────────────────────────────────────┘
                                                shared core: Engine + Renderer + LightsBackend
```

The normal app has one manual network path: tapping **Check for updates** fetches the public release
list from GitHub. It does not run in the background and does not send rule, notification, renderer,
or device state.

**Root transport (automatic when available).** The app checks for `su` without elevating. When
HiLight is turned on, it asks the root manager once, stages a release document, cooperatively stops
the PID- and owner-validated old renderer, and launches `AdbHelper` as uid 0. The app accepts the new
renderer only after its identity, PID, owner, released state revision, closed session, terminal
cleanup, and idle privacy state all match. A denied or failed request leaves output off and exposes
the Shizuku/ADB fallbacks.

**Shizuku transport (no computer).** Shizuku v12 or newer launches `HiLightUserService` into a
shell-UID process (`daemon(true)`, so it outlives the UI) and the app holds a real binder to it. State
is pushed straight in, no polling. The app first peeks at the existing user-service version so it does
not invoke a mismatched AIDL interface. Before visible/current state replay, the 1.0.9 candidate
requires renderer contract 1, implementation revision 4, status schema 6, clear algorithm 1, app
version code 10, and composite Shizuku service version 1004. A mismatch may receive only a minimal
disabled state to hold output dark; it is removed and rebound once, and every other transport remains
fenced until the rejected binder is confirmed disconnected. Verified running as `shell` uid 2000.

**ADB transport (fallback).** `AdbHelper` ships inside the APK, so the start command launches it with
no file to push. Every direct launch must include a valid explicit `--instance` value; the helper
refuses an absent/invalid identity and takes a singleton process lock before starting `Engine`. Its
synchronous startup cleanup can take roughly 2–3 seconds before it reports ready. Cross-UID binder is
not usable there: a shell-UID process that touches a `ContentProvider` is killed by ActivityManager
(verified), which rules out both a provider bridge and `ContentObserver` push. So that transport
exchanges two small JSON files instead. Its status carries the same renderer identity, and `SIGTERM`
runs `Engine.stop()` once before `app_process` exits.

**File ownership rule that matters** for the ADB transport: on external storage a file keeps the UID
of whoever created it. A file created by the shell is unreadable by the app, but the shell *can* write
into a file the app owns. So the app creates the directory and both files, and the helper only ever
overwrites in place.

Only one renderer may drive the array at a time. Every helper has a per-process renderer instance ID;
file-bridge state targets one exact instance, and a handoff first disables and terminates that exact
source before enabling its replacement. State receipt, render-thread settlement, and release are
distinct revisions: `receivedStateRevision` means the JSON parsed, `settledStateRevision` means the
render thread acted on it, and `releasedStateRevision` additionally requires a closed light session
and terminal cleanup. A transport change sends an idle state to the old renderer and requires the
matching released revision plus a stopped privacy observer. After the source's exact process or
binder exit is proven, the replacement first receives a fully disabled one-shot cleanup request. The
app waits for that exact request to be accepted and for its revision to become terminal, closed, and
released before replaying desired output. A timeout fails closed instead of treating a Binder return
or stale heartbeat as proof that the LEDs were released.

The app also watches file-bridge heartbeats. If a fresh current ADB/root instance appears while no
handoff or root transition is active, it retargets and re-pushes the saved current state exactly once
with `arm=false`. This lets a legitimately restarted helper resume without restarting the auto-off
clock. These are software/framework ownership guarantees; neither sequence observes the physical LED.

Output layering, highest first:

1. a finite notification alert
2. an active microphone or camera privacy rule
3. an infinite "while this app is open" override
4. the always-on ambient look

During a privacy-rule cooldown the renderer blanks the LEDs and closes its light session instead of
falling through to a lower layer. Turning control off does the same, handing HiLight back to Android.

## The device illustration

The Live tab draws the phone's own back with HiLight lit by the same pattern maths the hardware runs.
It is a vector reconstruction, not a bundled press image: Google's product renders are copyrighted, so
shipping them in an app is not an option, and a drawing can be animated by the live frame data anyway.

It follows `Build.MODEL`:

| Model | Layout |
|---|---|
| Pixel 11 Pro / Pro XL | full-width camera bar, three lenses, HiLight at the right-hand end |
| Pixel 11 Pro Fold | unfolded rear panel with the hinge seam, compact camera block top-left, HiLight inside it |
| Pixel 11 (non-Pro) | camera bar with a plain flash, and the card says HiLight is Pro-only |
| anything else | generic Pro-style layout |

The framing is a close crop on the camera bar. Only the top of the device is shown, running off the
bottom of the card, which is how Google frames the feature in its own material.

The array is drawn as one diffused disc rather than eight pinpoints, because the eight LEDs sit behind
a single flash window. Each LED still contributes its own colour from its position inside the window,
clipped to the window so the light keeps a crisp edge, so a chase or a rainbow visibly travels around
the lamp.

## Verified on device

- 8 LEDs enumerated with the capabilities in the table above
- solid, per-LED rainbow, comet, wave, breathe, pulse and random rendering on the real hardware
- alert layer expiring back to ambient, and an infinite override being cleared
- UI → hardware: picking Solid violet at 70% produced `ff5635b2` on all 8 LEDs
- notification path: a notification from a rule's package produced a green pulse within one frame
- foreground path: opening Chrome produced solid `ff2979ff`, returning home restored ambient
- privacy path: the v1.0.6 standalone helper observed Pixel Camera through AppOps, entered the camera
  rule's lit phase with a live LED session, then returned to inactive and closed the session when the
  camera process stopped
- animation keeps running with the screen off (`mState=DOZE`), including the face-down case
- turning control off closes the session and blanks the array
- Shizuku transport: user service starts as `shell` uid 2000 with 8 LEDs, binder connects, ambient and
  notification alerts render with no adb helper running at all
- ADB reset and start commands launch the renderer straight out of the installed APK
- failover: killing the Shizuku server mid-animation is detected, state is re-pushed, and the ADB
  helper picks the array up, with no overlap between sessions
- Shizuku 13.6.0 (official release, signer `CN=Rikka`) used for all of the above

## LED safety implementation

The safety guards summarised in the README live in `Engine`, not in the UI, so no state document can
opt out of them. In v1.0.9 the renderer itself clamps `ambientTimeoutMs` to five minutes; the bridge
cannot bypass the UI ceiling:

| Guard | Default | Ceiling |
|---|---|---|
| Ambient auto-off | 30 s | 5 min, behind two warnings |
| Per-app notification | 10 s | 1 min, behind two warnings |
| Alert hard clamp | Not configurable | 60 s, whatever the app asks for |
| Open-ended holds ("while open") | Not configurable | capped at the auto-off value |
| Duty cycle | Not configurable | at most 50% of any 10-minute window |
| Sustained brightness | Not configurable | eases to 55% after 10 s of unbroken light |

Two details that matter:

- **Only deliberate user action restarts the auto-off window.** A notification firing, a foreground
  override, or the app being backgrounded all push state with `arm: false`, so the array cannot be
  kept lit indefinitely in 30-second increments.
- **Leaving the app kills a running test.** `onStop` clears the preview immediately and does not hand
  ambient a fresh window on the way out.

Verified on device: brightness taper visible as `ff4d50 → 8c2a2c`; auto-off blanking at exactly 30 s;
duty guard tripping after 10 032 ms lit in a (temporarily shortened) 20 s window, resting, then
resuming when the window rolled over; a notification playing without extending the ambient window; and
a test stopping the moment the app went to the background.

What still cannot be measured here: actual power draw and LED junction temperature. Android does not
attribute either per-LED, so these figures are conservative by design rather than tuned to data.

## Per-contact rules

A rule can be scoped to one chat, so a message from one person lights a colour of their own. Nothing
about this needs a new permission: the sender's name is inside the notification the listener already
receives.

`NotificationPeek.read` turns a `StatusBarNotification` into a `MessageInfo`, and
`ConversationMatch` decides which rule that notification belongs to. The matcher is a ladder, tried
strongest first:

| Rung | Source | Survives a rename? |
|---|---|---|
| `KEY` | `Notification.shortcutId`, the app's own stable per-chat id | Yes |
| `NAME` | MessagingStyle sender, group title, or the notification title, normalised | No |
| `CONTAINS` | the rule's name inside the notification title, for apps that pack extra text in — Discord's `Sujay (#general, Server)` | No |

Two keys present and unequal means a different chat, so a key mismatch beats any name similarity.
Names are compared with case, punctuation and emoji stripped, because WhatsApp shows exactly what is
in the address book and a contact saved as `Sujay (work)` would otherwise never match. A rule created
from a name records the chat's `shortcutId` the first time it matches, after which renaming cannot
break it.

Resolution is most-specific-first: a conversation rule for the app, then a conversation rule on the
"any app" sentinel (the same person across WhatsApp and SMS), then the app's plain rule, then the
catch-all.

Things learned from the framework rather than assumed, both of which would have shipped bugs:

- `NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification` returns a
  `conversationTitle` for **one-to-one** chats too, because androidx writes the title into a hidden
  extra unconditionally and restores from it when the visible `EXTRA_CONVERSATION_TITLE` is absent.
  Reading it directly would mark ordinary chats as groups, and a person rule is refused inside a group
  unless it opted in — so every per-contact rule would have stayed dark. The group question is settled
  from `EXTRA_IS_GROUP_CONVERSATION`, or the visible extra, and nothing else.
- Messaging apps re-post the *same* notification on every change to the conversation, so a chat is
  ignored unless it carries a newer message stamp than the last one handled for that notification key.
  Group summaries (`FLAG_GROUP_SUMMARY`) are dropped outright, or a bundled app would flash twice and
  the summary's text would match a rule naming any one member.

Per-app coverage is uneven, and honestly so: WhatsApp, Google Messages and Telegram give a
`shortcutId` and a named `Person`; Slack gives MessagingStyle on recent versions and a title on older
ones; Discord gives neither, so only the title path works; and Signal with message content hidden
gives no sender at all, which no amount of code can recover. The **notification inspector** under
Setup exists for exactly this — it shows what was extracted from each notification, and can be copied
or shared without ever including message text.

## Privacy activity rules

Microphone and camera rules are separate from notification and foreground rules. A rule can target
one package or any app. The privileged renderer observes Android's active AppOps snapshot; it never
opens the microphone or camera and never receives their content.

Callbacks are treated only as invalidation signals. After each callback the watcher reads a fresh,
authoritative snapshot, so duplicate callbacks and process death cannot leave a reference count
stuck. Package names are used only in memory to match the user's rules and are not written to logs or
status files.

Each continuous use gets one monotonic one-minute episode. The default cycle is 10 seconds lit and 10
seconds released. If use stops after five seconds, output stops after five seconds. Overlapping apps
share the same episode for an any-app rule, so switching recorders cannot restart the one-minute cap.
Camera wins over microphone only when an eligible camera rule exists; an unconfigured camera cannot
silence a configured microphone rule.

## Known limits

- The renderer has to be restarted after every reboot. Rooted phones do this automatically when the
  app opens after the root manager has approved it. Locked phones must restart Shizuku or re-run the
  ADB command.
- Root startup is covered by deterministic host tests but is not maintainer-device verified because
  the maintainer's Pixel is intentionally unrooted. Root support is best effort across `su -c`
  compatible root managers; community device reports are welcome.
- If Shizuku is (re)started while HiLight Studio is already running, reopen the app so Shizuku can hand
  it access. Shizuku's own "Authorized applications" count also resets when its server restarts, so it
  may ask for approval again.
- While our normal session is open the system's own HiLight effects (calls, Gemini) are suppressed,
  so it is held only while there is actually something visible to show. Once cleanup settles, the
  renderer owns zero sessions while dark or off, and exactly one only while visibly driving.
- v1.0.8 already wrote alpha-only black (`0x01000000`) followed by canonical black (`0x00000000`)
  before closing the normal session. Reports from Pixel 11 Pro users show that a physical LED can
  still remain latched after Android reports black and no session, so that sequence was not proof of
  physical success.
- v1.0.9 keeps the pre-release sequence, waits for the hardware's advertised update period clamped to
  a bounded 1–250 ms interval after each accepted write, closes the normal session, then repeats the
  two black states through three
  fresh sessions at priority `-1000`, one borrow per second. A new renderer performs the same three
  post-release passes before reporting ready, and **Retry LED cleanup** can explicitly request one
  fresh three-pass cycle only while HiLight is off and idle. No production recovery stimulus asks an
  RGB channel to emit light. Android's
  [AOSP `LightsService`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/lights/LightsService.java#94)
  sorts sessions descending with `Integer.compare`, so priority `-1000` is below HiLight's normal
  `-10` to `10` range and below any other session whose priority is greater than `-1000`. We have not
  measured every system-session priority. The Setup priority applies only to HiLight's normal visible
  session.
- Cleanup status distinguishes Binder acceptance, effective framework readback, shadowing, I/O
  failure, and exhausted retries. `getLightState()` is still framework evidence rather than a sensor
  looking at the panel, so even `framework_effective_unverified` or `completed_unverified` never
  means the physical LED was observed dark. **Copy LED diagnostics** exports an allowlist of device
  build, renderer identity, transport, session/cleanup state, and revisions; it excludes notification
  data, packages, accounts, stable device identifiers, and logcat.
- ADB reset sends `SIGTERM` through the constrained helper-process pattern, treats an app-process with
  an unreadable/empty cmdline as unresolved, waits up to 6.5 seconds, and skips launch if any exact
  HiLight renderer survives. Root takeover additionally validates the exact PID, owner, and command
  line. A current helper's shutdown hook runs the bounded stop cleanup once before exit; the singleton
  lock and post-exit cleanup/replay fence keep takeover failed closed on a mismatched or unresponsive
  process.
- The black-only mitigation has not yet been physically validated on a Pixel 11 Pro that reproduces
  the latch. The maintainer's Pixel 11 Pro XL does not reproduce it and can establish only regression,
  renderer-identity, session-ownership, and call/Gemini hand-back behavior.
- Deep sleep suspends the CPU, so animations freeze at the last frame until the device wakes. Static
  colours are unaffected.
- Notification rules ignore ongoing notifications (media, progress) to avoid constant retriggering.
