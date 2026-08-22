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

The renderer core (`core/src`) is shared, and there are two ways to get it into a shell-UID process.

```
HiLight Studio (normal app)                    privileged renderer (uid 2000 = shell)
┌─────────────────────────────────┐            ┌────────────────────────────────────┐
│ Compose UI: Live/Ambient/Apps   │  binder    │ Shizuku: HiLightUserService        │
│ NotificationTrigger (listener)  │ ─────────► │   com.hilight.studio:hilight       │
│ ForegroundWatcher (UsageStats)  │            ├────────────────────────────────────┤
│ Store: layering + rules         │  2 JSON    │ ADB: com.hilight.core.AdbHelper    │
│ Transport: Auto/Built-in/Shizuku│ ◄────────► │   run from the installed APK       │
│ AdbAccess: own ADB client       │  files     │   started by the app, or by adb    │
└─────────────────────────────────┘            └────────────────────────────────────┘
                                                shared core: Engine + Renderer + LightsBackend
```

**Built-in access (default).** The app is its own ADB client. It discovers the phone's debug daemon
over mDNS, pairs once with the six-digit code from Wireless debugging, opens a TLS shell session to
`127.0.0.1`, and runs the same two commands the manual flow used to ask for. From there it is the
ADB transport: the helper polls `state.json` and writes `helper_status.json`. Discovery, pairing,
and reconnection live in `AdbAccess`, `AdbPairingService`, and `AdbReconnect`.

The pairing service (`_adb-tls-pairing._tcp`) is advertised only while the Settings pairing dialog
is open, and the code is shown in that same dialog, so the code is collected from a notification
with a `RemoteInput` reply action rather than from a field inside the app. The RSA key and its
self-signed certificate are generated once and kept in app-private storage, so the daemon keeps
trusting the app across reboots and updates.

**Shizuku transport (preferred, no computer).** Shizuku launches `HiLightUserService` into a shell-UID
process (`daemon(true)`, so it outlives the UI) and the app holds a real binder to it. State is
pushed straight in, no polling. Verified running as `shell` uid 2000.

**ADB transport (fallback).** `AdbHelper` ships inside the APK, so the start command launches it with
no file to push. Cross-UID binder is not usable there: a shell-UID process that touches a
`ContentProvider` is killed by ActivityManager (verified), which rules out both a provider bridge and
`ContentObserver` push. So that transport exchanges two small JSON files instead.

**File ownership rule that matters** for the ADB transport: on external storage a file keeps the UID
of whoever created it. A file created by the shell is unreadable by the app, but the shell *can* write
into a file the app owns. So the app creates the directory and both files, and the helper only ever
overwrites in place.

Only one renderer may drive the array at a time. When Shizuku is active the app writes
`enabled:false` to the ADB state file so any leftover helper releases the session, and if Shizuku goes
away the app re-pushes so the ADB helper takes over.

Output layering, highest first:

1. a finite notification alert
2. an infinite "while this app is open" override
3. the always-on ambient look

Turning control off blanks the LEDs and closes the session, handing HiLight back to Android.

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
opt out of them:

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

## Known limits

- Privileged access has to be re-established after every reboot. Built-in access does this itself
  from a `BOOT_COMPLETED` receiver, retrying over a three-minute window because Wi-Fi and the debug
  daemon are usually not up yet at boot; the Shizuku and manual ADB routes still need the user.
  Wireless debugging must stay enabled either way. Removing that requirement entirely would need
  root or an unlocked bootloader (app in `/system/priv-app`).
- Built-in access needs the phone to be on a Wi-Fi network: the debug daemon advertises itself over
  mDNS on that interface, and there is nothing to discover without it.
- If Shizuku is (re)started while HiLight Studio is already running, reopen the app so Shizuku can hand
  it access. Shizuku's own "Authorized applications" count also resets when its server restarts, so it
  may ask for approval again.
- While our session is open the system's own HiLight effects (calls, Gemini) are suppressed, so the
  session is held only while there is actually something to show. The moment the array goes dark,
  whether from the auto-off deadline passing, an alert ending, or the master switch going off, it is handed straight
  back, and a rule firing reclaims it before the first frame. An all-black session left open beats
  the system's own effects, and because the Shizuku renderer is a daemon that outlives the app, that
  used to leave calls and Gemini dark until the phone was rebooted. The Setup tab still exposes the
  session **priority** for the overlap while a look is genuinely running; the exact arbitration rule
  in `LightsService` was not reverse-engineered.
- Deep sleep suspends the CPU, so animations freeze at the last frame until the device wakes. Static
  colours are unaffected.
- Notification rules ignore ongoing notifications (media, progress) to avoid constant retriggering.
