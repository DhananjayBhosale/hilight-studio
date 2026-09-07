# Highlight Studio privacy policy

Effective 3 September 2026  
Package: `com.highlight.studio`
Publisher: Dhananjay Bhosale  
Privacy contact: [Highlight Studio issue tracker](https://github.com/DhananjayBhosale/hilight-studio/issues)

## Summary

Highlight Studio controls the eight-LED HiLight array on supported Pixel 11 Pro devices. The Google
Play build has no advertising, analytics, account system, telemetry, crash reporting, or internet
permission. It does not collect or share user data with the publisher or another organization.

The app processes the information described below only on the user's device to provide features the
user explicitly enables.

## Information processed on the device

- **Notifications:** If the user grants Android notification access, the app reads the posting app,
  title, sender or chat name, stable chat ID, notification structure, and message text. These fields
  are used to match user-created LED rules. Message text is used only for optional keyword matching
  and is never stored or logged.
- **Foreground app:** If the user grants Usage access and creates a "while open" rule, the app checks
  which app is in the foreground so it can apply that rule.
- **Microphone and camera activity:** When the user enables a privacy-activity rule, the privileged
  renderer checks Android's active AppOps signal. The app does not open the microphone or camera and
  does not receive audio, video, or their contents.
- **One selected contact:** The Android contact picker may grant one-time access to the single contact
  selected by the user. The app reads that contact's display name only. It does not request contacts
  permission or read the address book.
- **Installed apps:** The Play build lists apps with launcher activities and apps learned from local
  notifications so the user can select a rule target. It does not request broad package visibility.

## Information stored on the device

The app stores settings, presets, LED rules, selected app package names, remembered chat names and
stable chat IDs, and recent rule-match times in private app storage. A capped diagnostic list of
recent notification metadata exists only in memory and disappears when the listener process stops.
Message bodies are not persisted.

Android cloud backup and device-to-device transfer are disabled for the app. Users can delete
remembered chats from Setup, delete individual rules in the app, or delete everything by clearing app
storage or uninstalling the app.

## Information leaving the device

The Google Play build has no internet permission and sends no app data to the publisher. The app can
open Android's share sheet only after the user taps a Copy or Send action. The user chooses the
destination and can review the diagnostic or setup text before sending it.

Shizuku, a root manager, and the Android Debug Bridge are optional user-controlled ways to provide
the privileged access required by the LED renderer. Highlight Studio does not install them, root the
device, collect their data, or grant itself access without the user's explicit action.

## Children

Highlight Studio is a technical hardware utility intended for adults. It is not directed to children,
does not contain child-focused content, and does not knowingly collect personal information from
children or anyone else.

## Changes

Material changes to this policy will be published at this location with a new effective date before
the corresponding app release.
