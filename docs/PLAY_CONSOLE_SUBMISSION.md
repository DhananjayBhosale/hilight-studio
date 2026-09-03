# Google Play Console submission sheet

Use these answers for the Play build only. Recheck them whenever permissions, SDKs, storage, or
network behavior changes.

## Store setup

- App name: **OctaGlow**
- Package: `com.hilight.studio`
- Category: **Tools**
- App or game: **App**
- Free or paid: **Free**
- Ads: **No**
- Target audience recommendation: **18 and over**. This is a technical privileged-access utility,
  not a child-directed product.
- Privacy policy URL: publish the repository's `PRIVACY.md` at a stable public HTTPS URL and use that
  URL in Policy and programs > App content > Privacy policy.

## App access and reviewer instructions

There is no login, subscription, region lock, or account. Full function requires:

1. A Pixel 11 Pro, Pixel 11 Pro XL, or Pixel 11 Pro Fold running Android 17.
2. One user-controlled privileged transport: Shizuku permission, an ADB-started helper, or existing
   root access approved through the user's root manager.
3. Optional Android Notification access for notification and per-chat rules.
4. Optional Usage access for "while open" rules.

Reviewer note:

> OctaGlow controls the supported phone's eight-LED HiLight array. It does not root the device
> or bypass a consent screen. The reviewer explicitly supplies access through Shizuku, ADB, or an
> existing root manager. Without a supported device, the UI, presets, safety controls, disclosures,
> and rule configuration remain reviewable, but physical LED output cannot be demonstrated.

## Data safety

- Does the app collect or share any required Play data type? **No.** Information is processed only on
  the device and is not transmitted to the developer or another organization.
- Is all user data encrypted in transit? **Not applicable; the Play build makes no network requests.**
- Can users request deletion? **No account or remote data exists.** Local data can be deleted in-app,
  through Android Clear storage, or by uninstalling.

The app still needs a complete privacy policy because it accesses personal and sensitive information
locally through notification and usage access.

## Foreground service declaration

Declared type: `specialUse`

Function description:

> When the user enables a "while this app is open" rule or a face-down condition, OctaGlow runs
> a visible foreground service that watches the selected foreground-app state or phone orientation.
> It updates only user-created local LED rules. The persistent Android notification identifies the
> active feature and lets the user return to the app and stop it.

Impact if deferred or interrupted:

> The selected LED rule would react late or stop while the target app is open or the phone is face
> down. The task must run continuously for the user-requested rule to behave as configured. The user
> can disable the rule or turn HiLight off at any time, which stops the service.

Declaration video checklist:

1. Open the app and show the relevant rule disabled.
2. Enable a "while open" rule or face-down condition.
3. Show the foreground-service notification.
4. Demonstrate the rule reacting on supported hardware.
5. Disable the rule or turn HiLight off and show that the service notification stops.

Host the short unlisted video on YouTube or a reviewer-accessible Google Drive URL and paste it into
the foreground-service declaration.

## Sensitive access disclosure

The app displays its own notification-access disclosure before opening Android settings. The video or
review notes should show both **Continue to Android settings** and **Not now**, followed by a working
notification rule after consent.

## Device availability

In Play Console's Device catalog, restrict availability to the supported Pixel 11 Pro, Pixel 11 Pro
XL, and Pixel 11 Pro Fold models. The API 37 minimum removes older Android versions but does not by
itself exclude unsupported API 37 phones.

## Release sequence

1. Enroll in Play App Signing. To preserve updates for users of existing signed builds, supply the
   existing app-signing key rather than accepting an unrelated Google-generated app key.
2. Keep the private key and passwords outside the repository. A separate upload key can be registered
   after enrollment.
3. Upload the signed `.aab` from the release evidence directory.
4. Resolve every Play Console warning and complete App content declarations.
5. Publish to Internal testing first and install the Play-generated build on supported hardware.
6. Verify Shizuku, ADB, root where available, one notification rule, one foreground rule, LED cleanup,
   and upgrade behavior from the last permanently signed build.
7. Promote the exact tested bundle to production only after the physical checks pass.
