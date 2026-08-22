<img src="docs/media/hilight-studio-logo.png" alt="HiLight Studio logo" width="112" align="right">

# HiLight Studio

Control the eight-LED HiLight array on Pixel 11 Pro devices.

[![Android checks](https://github.com/DhananjayBhosale/hilight-studio/actions/workflows/android.yml/badge.svg)](https://github.com/DhananjayBhosale/hilight-studio/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/DhananjayBhosale/hilight-studio?include_prereleases&label=release)](https://github.com/DhananjayBhosale/hilight-studio/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-2f81f7.svg)](LICENSE)

> [!IMPORTANT]
> HiLight Studio is experimental and supports only the Pixel 11 Pro, Pixel 11 Pro XL, and Pixel 11 Pro Fold on Android 17 (API 37). It is not affiliated with or endorsed by Google.

<p align="center">
  <img src="docs/media/screen-live.png" alt="Live tab controlling the HiLight array on a Pixel 11 Pro XL" width="420">
</p>

## Features

- Solid colours and animated patterns across all eight LEDs
- Per-app rules for foreground use and notifications
- Saved presets with import and export
- Wallpaper-derived colours and a Quick Settings tile
- Quiet hours, Do Not Disturb, Battery Saver, and low-battery controls
- Set up entirely on the phone: no computer, no companion app, no root

## Screenshots

<table>
<tr>
<td width="33%"><img src="docs/media/screen-style.png" alt="Style tab with presets, patterns, and colour controls"></td>
<td width="33%"><img src="docs/media/screen-apps.png" alt="Apps tab with per-app rules"></td>
<td width="33%"><img src="docs/media/screen-setup.png" alt="Setup tab with access and safety controls"></td>
</tr>
<tr>
<td align="center"><sub><b>Style</b></sub></td>
<td align="center"><sub><b>Apps</b></sub></td>
<td align="center"><sub><b>Setup</b></sub></td>
</tr>
</table>

## Install

1. Download the signed APK from the [latest GitHub prerelease](https://github.com/DhananjayBhosale/hilight-studio/releases).
2. Open it on the phone and install it. Play Protect may warn about a sideloaded app that uses
   notification access; choose to install anyway.

If you previously installed v1.0.3 or an older debug-signed build, uninstall it once first, because
the signing certificates are different. Installing over ADB also works and skips the Play Protect
prompt:

```bash
adb install -r HiLight-Studio-v1.1.0-experimental-signed.apk
```

## Set up

Driving the LEDs needs `android.permission.CONTROL_DEVICE_LIGHTS`, which Android declares
`signature|privileged`. No installed app can hold it, so the renderer has to run in a process owned
by the shell UID. HiLight Studio arranges that on the phone itself, with no computer, no companion
app, and no root.

Open the app, go to **Setup**, and follow the **Built-in access** card:

1. **Allow local network access** when the app asks on first launch. Android 17 gates the phone's
   own debug service behind it; nothing leaves the device.
2. **Turn on Developer options**: Settings, About phone, then tap **Build number** seven times.
3. **Turn on Wireless debugging**, and stay connected to Wi-Fi.
4. **Tap "Pair with this phone".** In Wireless debugging, choose **Pair device with pairing code**
   and leave that dialog open. A HiLight notification appears: pull down the shade over the dialog,
   type the six digits into it, and send.

That is the whole setup. The pairing is remembered, and HiLight Studio brings its own renderer back
after a reboot or an app update.

Then grant **Notification access** for notification rules and **Usage access** for foreground-app
rules. Turn on **Live**, then choose a look in **Style**. A new installation starts with its
always-on style set to **Off**.

### If it does not connect

- Wireless debugging must be on **and** the phone must be on a Wi-Fi network. The debug service is
  found over mDNS on that interface; with no network there is nothing to discover.
- The pairing dialog must still be open when you send the code, and it expires after about a
  minute. Closing it takes the pairing endpoint down with it; just reopen it and send again.
- If the Setup card says **needs local network**, grant that permission first. Without it the phone's
  debug service is invisible to the app.
- If the phone stops trusting the app's key, which happens after a factory reset or after clearing
  the ADB authorisations in Developer options, tap **Forget pairing** and pair again.

### If the app connects but the LEDs stay dark

Only one renderer can drive the array, and a leftover one keeps sending black while the app still
reports a connection. **Restart renderer** in the Setup tab clears any leftover session before
starting a fresh one. With a computer to hand, the session count should be exactly one:

```bash
adb shell dumpsys lights | grep -c "Session token="
```

### Other ways in

<details>
<summary>Shizuku</summary>

Install [Shizuku](https://shizuku.rikka.app/), start it using Wireless debugging, then open
HiLight Studio, go to **Setup**, tap **Request access**, and approve the request. Shizuku has to be
restarted after each reboot, and HiLight Studio reopened afterwards so Shizuku can hand it access.

</details>

<details>
<summary>A computer with ADB</summary>

Under **Built-in access**, tap **Use a computer instead** to copy the two commands. Run them with
the phone plugged in and USB debugging enabled, and re-run them after every reboot. The first
command stops any existing renderer, the second starts a fresh one out of the installed APK, so
there is nothing to push.

</details>

## Safety limits

The renderer enforces these limits even if app state is edited:

- Ambient effects stop after 30 seconds by default and can be raised to 5 minutes.
- Notification effects are limited to 1 minute.
- Sustained brightness tapers after 10 seconds of continuous light.
- The array can be active for at most half of any 10-minute window.
- Battery Saver, low-battery, quiet-hours, screen-state, and Do Not Disturb rules can pause output.

Long, continuous use of the HiLight LEDs has not been tested. If you build the project yourself, you can change the timing and safety values in your copy. Custom builds are your responsibility.

See [Technical details](docs/TECHNICAL.md) for the renderer architecture, hardware findings, device verification, and known limits.

## Privacy

HiLight Studio has no analytics, account system, or telemetry. App rules and presets stay on the device. Notification and usage access are optional and are used locally for the rules you enable.

The app declares `android.permission.INTERNET` and `android.permission.ACCESS_LOCAL_NETWORK` because Android gates sockets and local service discovery behind them, including the connection HiLight Studio makes to the phone's own debug daemon during setup. Nothing in the project contacts a remote host.

## Build from source

Requirements:

- JDK 21
- Android SDK platform 37.0
- Android Studio or a command-line Android SDK installation

```bash
git clone https://github.com/DhananjayBhosale/hilight-studio.git
cd hilight-studio
./gradlew :app:testDebugUnitTest :app:build :app:lint
```

Build an installable developer APK with:

```bash
./gradlew :app:assembleDebug
```

The APK is written under `app/build/outputs/apk/debug/`. You may fork the repository, change the source, and build your own version under the terms of the MIT License.

## Contributing

Issues and pull requests are welcome. Hardware reports should include the Pixel model, Android build, renderer transport, and exact steps to reproduce. Do not include notification contents or other personal data.

Read [Contributing](CONTRIBUTING.md) before opening a pull request. Security issues must follow the private process in [Security policy](SECURITY.md).

## Project documents

- [Changelog](CHANGELOG.md)
- [Technical details](docs/TECHNICAL.md)
- [Release process](docs/RELEASING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)

## License

[MIT](LICENSE). You may use, modify, redistribute, and sell the project. Redistributed copies must retain the license notice.
