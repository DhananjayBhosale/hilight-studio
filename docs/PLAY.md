# Google Play submission playbook

An internal checklist for publishing HiLight Studio (`com.hilight.studio`) on Google Play. It exists
because most of the work is not in the build: it is in declaring three sensitive permissions
honestly, answering the Data Safety form correctly, and being straight with a reviewer about an app
that does nothing at all without a Pixel 11 Pro and a privileged renderer.

Nothing here is legal advice, and nothing here can promise an outcome. Play review is a human
process applied to written policy, and this app sits in several places that policy does not describe
well. Where that is true, this document says so rather than guessing.

**Standing assumption for the whole document.** The Play build is the `play` product flavour, which
sets `UPDATE_CHECK = false` and does not declare `INTERNET`. The GitHub and F-Droid builds are the
`github` flavour, which does both. Every "no network" answer below depends on that split holding.
Verify it in the built artifact, not in the source, before each submission — step 3 of the
pre-launch checklist does this.

## 1. Sensitive permission declarations

Three declarations matter. All three are for permissions the user grants outside the normal runtime
prompt, and all three are ones Play asks about because they are commonly abused.

### `BIND_NOTIFICATION_LISTENER_SERVICE`

**What Play asks.** Play's notification-listener declaration is the strictest of the three. The
console asks you to confirm that the notification listener is used for a core feature, to describe
that feature, to state which data the listener reads and what happens to it, and usually to supply a
video showing the feature in use. An app whose notification access is convenience rather than core
function is refused. Notification content is also treated as personal and sensitive user data, so
the answer has to line up exactly with the Data Safety form and with the hosted privacy policy.

**Draft justification.**

> HiLight Studio's core function is to turn a notification into a colour on the eight-LED HiLight
> array around the camera of a Pixel 11 Pro. The user creates a rule — for example, green when
> WhatsApp notifies, or violet when one particular contact messages — and the app lights the array
> in that colour when a matching notification arrives. Notification access is the only way Android
> allows an app to learn that a notification was posted, so without it every notification rule in
> the app is dead and roughly half the app's purpose disappears.
>
> From each notification I read only the fields a rule can match on: the posting package name, the
> notification's `shortcutId` (the app's own stable per-chat identifier), the sender's name, the
> group title, the notification title, and structural flags such as whether the notification is a
> group summary or an ongoing one. I use them to select a rule, then discard them.
>
> The body of the message is never stored, never written to a log and never exported. It is read
> into memory for one purpose only — comparing it against an optional keyword the user typed into
> their own rule — and is then discarded with the rest of the notification. The app's diagnostic
> screen, which exists so a user can see why a rule did not fire, deliberately omits the message
> body from both its display and its export.
>
> Nothing derived from a notification leaves the device. The Play build of this app declares no
> `INTERNET` permission and contains no networking code, so it is not capable of transmitting
> anything. There is no account, no analytics, no crash reporting and no advertising library. Chat
> display names are kept in the app's own private storage so that creating a per-contact rule does
> not require the user to type a name; that list is capped, and the Setup screen has a "Forget
> remembered chats" action that clears it.
>
> The app is open source under the MIT licence, so every claim above can be checked against the
> source.

### `PACKAGE_USAGE_STATS` (Usage access)

**What Play asks.** Less certain than the other two, and worth saying so: usage access is a special
access the user grants in Settings rather than a permission Play grants, and I cannot confirm that
the console has a dedicated declaration form for it in the way it does for notification listeners.
What is reliably true is that pre-launch report and review flag it, that the App content
questionnaire and Data Safety form must account for reading which apps are in use, and that Play's
prominent-disclosure requirement applies: the user must be told what it is for before being asked
for it. Treat the text below as the answer to give wherever it is asked — a console form, a review
reply, or the store listing — and check the console for a form rather than assuming there is none.

**Draft justification.**

> Usage access is optional and powers one feature: a "while this app is open" rule, which holds a
> chosen colour on the LED array for as long as a chosen app is in the foreground. Android exposes
> no other API to a non-privileged app that reports which app is currently on screen, so the feature
> cannot be built without it.
>
> The app queries `UsageStatsManager.queryEvents` for the last ten seconds and reads a single field
> from it: the package name of the most recent activity that came to the foreground. It does that
> only while at least one "while this app is open" rule exists and the master switch is on; the
> service that polls is started and stopped to match the rule set, so with no such rule the app
> never queries usage stats at all. The package name is compared against the user's own rules in
> memory. No usage history is stored, no history is aggregated, nothing is written to a log, and
> nothing is transmitted — the Play build has no network access.
>
> The app never asks for this access on its own. The Setup screen shows it as optional, states what
> it is for, and opens Android's own Usage access settings only when the user taps the button.

### `FOREGROUND_SERVICE_SPECIAL_USE`

**What Play asks.** Since the Android 14 foreground-service rules took effect, the console has a
declaration section for foreground service types, and `specialUse` is the type you must argue for:
Play wants you to explain why none of the defined types fits. The manifest must also carry the
`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property on the service, which it does — the current
value is "Drives the HiLight LEDs from the foreground app". Console review of `specialUse` can and
does come back asking you to use a defined type instead, so expect a round trip.

**Draft justification.**

> The service is the foreground-app watcher behind the app's "while this app is open" rules. While
> such a rule exists, it polls `UsageStatsManager` once a second for the package that is currently
> in the foreground and, when that package matches a rule, holds that rule's colour on the phone's
> HiLight LED array until the user leaves the app. The work has to continue while HiLight Studio
> itself is in the background, because the entire point is to react to a *different* app being on
> screen.
>
> I use `specialUse` because none of the defined foreground service types describes this work.
> It is not location, media playback, media projection, a camera or microphone capture, a phone
> call, data sync, short service, remote messaging, health, connected device or system-exempt work.
> The nearest candidate, `connectedDevice`, would be a misdescription: the LEDs are part of the
> phone itself rather than an external device, and no companion-device pairing is involved. Rather
> than declare a type that does not match, I declared `specialUse` and named the work in the
> service's subtype property.
>
> The service runs only while a "while this app is open" rule is enabled and the master switch is
> on, and stops as soon as neither is true. It posts a minimum-importance notification of its own so
> the user can see it is running, and it does nothing but read the foreground package name and set
> LED colours.

### `QUERY_ALL_PACKAGES` — check before you submit

`QUERY_ALL_PACKAGES` is a restricted permission that Play grants only to a short list of app
categories, none of which this app belongs to; declaring it invites either a declaration form or a
rejection. As of the current working tree it has been removed from the manifest and replaced with an
`<intent>` visibility query for launcher activities, which is enough for the rule picker to list apps
the user could write a rule for. Confirm it is absent from the built Play artifact (checklist step 3)
and, if it ever returns, treat it as a fourth declaration rather than an implementation detail.

## 2. Data Safety form

Play's Data Safety form defines **collection** as data transmitted off the device, and **sharing**
as data transferred to a third party. On that definition the app collects nothing and shares
nothing, even though it reads several sensitive things — because everything it reads is processed on
the device and never sent anywhere. The table below is the set of answers to give, with the reason
for each, so that a future reviewer of this file can tell a correct "no" from a lazy one.

| Data type (Play's category) | Does the app access it? | Collected | Shared | Why the answer is "no" |
|---|---|---|---|---|
| Personal info → Name | Yes: chat and contact display names | No | No | Stored only in the app's private `SharedPreferences`; never transmitted. The Play build cannot transmit |
| Messages → Other in-app messages | Yes: notification title and body are read in memory | No | No | The body is used only for an optional user-typed keyword test and then discarded. Never persisted, logged or exported |
| Contacts | Yes: one display name, when the user picks a contact | No | No | One-shot URI grant from the system picker; no `READ_CONTACTS`; stored locally as a name only |
| App activity → Installed apps / app interactions | Yes: the foreground package, and the list of launcher apps | No | No | Read in memory to match rules; no usage history is retained or transmitted |
| Photos, video, audio | No | No | No | The app never opens the camera or microphone. Microphone and camera rules read Android's "in use" signal only, never any content |
| Location, financial info, health, files, calendar, contacts sync | No | No | No | Not accessed |
| Device or other IDs | No | No | No | No identifier is generated, read or transmitted |
| App info and performance → Crash logs, diagnostics | No | No | No | No crash reporter, no analytics SDK |

Supporting answers:

| Form question | Answer | Note |
|---|---|---|
| Is all of the user data collected by your app encrypted in transit? | Not applicable | The question should not appear, since nothing is collected. If the form insists on an answer, the truthful basis is that the Play build declares no `INTERNET` permission and makes no network requests |
| Do you provide a way for users to request that their data be deleted? | Yes — on-device | Setup → **Forget remembered chats** clears the remembered chat list immediately; deleting a rule removes the name held in it; uninstalling or clearing storage removes everything. There is no server-side copy to delete, so no data-deletion URL applies |
| Is your data collection independently validated? | No | Do not claim a security review that has not happened |
| Privacy policy URL | Required | Host `PRIVACY.md` at a stable URL and fill in its three `TODO` fields first. Play requires a reachable policy for any app with notification access |

**What changes if the update checker ever ships in a Play build.** The Data Safety answers above
barely move: the request sends only a `User-Agent` containing the app's version, so no user data is
collected even then. Three things do change, and they are the reason not to do it:

1. The `INTERNET` permission reappears, and with it the "encrypted in transit" question becomes a
   real one (the request is HTTPS to `api.github.com`).
2. GitHub sees the requesting IP address, which makes a third party's log part of the story. The
   privacy policy has to say so.
3. Most importantly, it is a policy problem in its own right — see the next section.

If the flavour split is ever undone, this section and the privacy policy both become inaccurate at
the same moment. Treat them as a pair.

## 3. Minimum functionality, and a reviewer with the wrong phone

The app needs three things at once: a Pixel 11 Pro, Pixel 11 Pro XL or Pixel 11 Pro Fold; Android 17
(API 37); and a privileged renderer reached through Shizuku, an ADB-started helper, or root. Without
all three the LEDs cannot be driven at all. Play's Minimum Functionality policy is aimed at apps
that are broken or empty, and an app that opens to a screen explaining that it cannot run is exactly
what that policy is written about — even when the explanation is honest. Three mitigations, in order
of importance:

**Restrict the device catalogue.** In the console, under the release's device targeting, exclude
everything except the three supported models. `minSdk = 37` already keeps the app off older
Android versions, and the store entry will then read as unavailable for a device that cannot run it
rather than as an app that installs and does nothing. This is the single most useful step: it turns
"broken for most users" into "not offered to most users".

**Say the requirement in the first line.** The short description has 80 characters and is the only
text many people read. Lead with the hardware, not the feature. The current F-Droid short
description already does this and can be reused nearly as it stands:

> Control the HiLight LED array on supported Pixel 11 Pro devices

The full description should state, in its first paragraph, all three requirements including that
privileged access must be re-established after every reboot. Understating this produces one-star
reviews from people whose phone was never capable of running it, and those reviews are harder to
undo than a cautious description is to write.

**Give the reviewer something to look at.** A reviewer will almost certainly not have a Pixel 11
Pro, and cannot be sent one. There is no way to make the app work for them, so the goal is to make
the app *legible* to them:

- A screen recording of a real device, showing the whole path: granting notification access, creating
  a rule, a notification arriving, the array lighting in the rule's colour, and the array going dark
  again. Put the link in the notification-listener declaration, which asks for exactly this, and
  repeat it in the review notes.
- Text in the console's **App access** section — the field intended for login instructions — stating
  plainly that no login exists, that the LED hardware exists only on the three listed models, and
  that on any other device the app opens to a Setup screen which says so. A reviewer who reads this
  before opening the app will not file it as broken.
- The unsupported-device path itself is part of the argument: the app already detects the model and
  explains the situation rather than failing silently. Make sure that screen is honest and legible,
  because it is the screen the reviewer will see.
- Screenshots that show the app doing something, taken on real hardware. The Live tab's device
  illustration animates from the same frame data as the LEDs, which makes the effect visible in a
  still image.

**One further practical obstacle, flagged rather than solved.** If the publishing account is a
personal developer account created recently enough to fall under Play's closed-testing requirement,
production access needs a closed test with a minimum number of testers over a minimum period. For an
app that only functions on three phone models, finding that many qualifying testers may be the
hardest part of this entire document. Check which requirement the account is actually subject to in
the console before planning a timeline; the answer depends on the account and cannot be read off the
code.

## 4. Policy risks worth naming plainly

| Risk | What kind of problem | Mitigation |
|---|---|---|
| Self-update / pointing at another distribution channel | **Policy problem.** The clearest one in this list | Ship the `play` flavour, which has neither the feature nor `INTERNET` |
| Instructing users to run `adb shell` commands | **Review friction**, not a policy breach as far as the written policy goes | Honest framing, and the fact that nothing is bypassed without the user's own approval |
| Root support | **Review friction**, and an unpredictable one | Root is used only where the user's root manager has already approved it; the app never attempts to obtain root |
| Reflection on the hidden `ILightsManager` interface | **Technical durability risk**, and a possible review question | The reflection runs in a privileged process, not in the app; the whole approach is documented |
| The name "HiLight" | **Trademark question**, outside code entirely | See section 5 |

**Self-update and alternative distribution.** Play's Device and Network Abuse policy prohibits an
app distributed through Play from updating itself by any method other than Play's own update
mechanism, and Play's anti-competition rules restrict directing users to another source for the same
app. The GitHub build's **Check for updates** action does the second of those: it compares versions
and, when a newer release exists, opens the release page where an APK can be downloaded. That is
correct behaviour for a GitHub or F-Droid build and unacceptable in a Play build. The `play` flavour
removes the feature and the `INTERNET` permission rather than hiding the feature behind a flag,
which is the right choice for two reasons: a reviewer reading the manifest can see the absence, and
a flag can be flipped by accident in a way a missing permission cannot.

**ADB shell instructions.** The app tells the user to enable developer options and run two
`adb shell` commands that start the renderer as the shell user. This is unusual, and it will draw
attention. It is worth being precise about what it is and is not. It is not a bypass of the Android
security model: `CONTROL_DEVICE_LIGHTS` is `signature|privileged`, the shell user already holds it,
and the user is deliberately lending their own shell access to a process they started. Nothing is
gained without the user's active participation at a computer, and nothing persists across a reboot.
No written Play policy that I can point to forbids an app from documenting an ADB workflow. But
"draws attention" is the honest summary: an app that ships a helper it expects to be launched via
`app_process` is not what reviewers see every day, and whether that ends in questions, a rejection,
or nothing at all depends on the reviewer. That cannot be predicted from here.

**Root support.** The app checks for `su` without elevating, and only asks the root manager for
approval when the user turns HiLight on. Using root that the user has already installed and approved
is not prohibited, and plenty of apps on Play do it. The risk is guilt by association rather than
policy: root plus shell commands plus a reflective binder call is a combination that reads as
privilege escalation at a glance, even though every step of it requires the user's own consent. The
mitigations are documentation and legibility, and both already exist in `docs/TECHNICAL.md`. Again,
the outcome depends on review.

**Reflection on a hidden interface.** `LightsBackend` reaches the lights service by reflection:
`ServiceManager.getService("lights")`, then `ILightsManager$Stub.asInterface`, then `getLights`,
`openSession`, `setLightStates`. Two separate things follow from that, and they should not be
confused.

The first is durability, not policy. `ILightsManager` is not public API, so a future Android release
can change or remove it and the renderer will stop working. That is a maintenance fact and already
documented as such.

The second is review perception. The renderer core ships inside the APK — it has to, so the ADB
command can launch it straight out of the installed app — which means a static scan of the artifact
finds `Class.forName("android.hardware.lights.ILightsManager$Stub")` in an ordinary app's dex, even
though nothing in the app's own process ever calls it. If that prompts a question, the answer is the
architecture: the app is a normal, unprivileged app, and the reflective calls only ever execute in
the separately launched privileged process. Whether it prompts a question at all is unknowable in
advance.

## 5. The name

"HiLight" is Google's own name for the LED array on the Pixel 11 Pro. The app is called HiLight
Studio, its package is `com.hilight.studio`, and its store listing will necessarily use the word
repeatedly because it is the name of the hardware being controlled.

This is a trademark and impersonation question, and it needs a qualified human — an intellectual
property lawyer, and ideally one who has dealt with Play's impersonation enforcement. It is not a
question that can be resolved by changing code, and it is not one this document should attempt to
answer. Two observations, offered as the state of the facts rather than as reassurance:

- The README's disclaimer ("not affiliated with or endorsed by Google") is worth keeping and should
  be repeated in the store listing. It reduces the chance of a user being confused about who
  publishes the app. It is not a defence against a trademark claim, and it does not stop Play's
  impersonation policy applying to an app title that a user could read as Google's own.
- There is a real distinction between using a name to identify the hardware a product works with,
  and using it as the product's own name. The store listing needs the first. The app title uses the
  second. Whether that distinction protects this specific title in the relevant jurisdictions is
  precisely the question for the lawyer, and the answer may be that the app should keep a title of
  its own while describing itself as a controller for the HiLight array.

Resolve this before submission, not after. A title change after publishing is far more disruptive
than one before, and an enforcement action against the listing can take the whole app down.

## 6. Pre-launch checklist

Work through in order. Every item is either verifiable or a decision with a name attached.

1. **Decide the app title.** Section 5. Get a qualified opinion, and record what was decided and by
   whom, so that a later question does not restart the discussion from nothing.
2. **Fill in `PRIVACY.md`'s `TODO` fields** — publisher, contact, jurisdiction, children's privacy.
   The Android backup question is already settled: the manifest sets `android:allowBackup="false"`,
   so remembered chat names and the names inside rules are excluded from Google account backups and
   from device-to-device transfer, and the policy states that as fact. If backup is ever re-enabled,
   that section has to change with it.
3. **Build the Play artifact and inspect it, not the source.** Confirm the merged manifest of the
   `playRelease` variant declares no `INTERNET` and no `QUERY_ALL_PACKAGES`, and that
   `BuildConfig.UPDATE_CHECK` is false. Confirm `GitHubUpdateChecker` was shrunk out, or at minimum
   that nothing reachable calls it. CI asserts the two permissions on every run, so a regression
   fails the build rather than reaching review.
4. **Host the privacy policy** at a stable URL and check it loads for a signed-out visitor.
5. **Set the device catalogue** to the three supported models and confirm the store entry shows the
   app as unavailable on a test account whose device is not one of them.
6. **Write the listing.** Short description leading with the hardware requirement; full description
   stating all three requirements and the reboot caveat in its first paragraph; the not-affiliated
   disclaimer present. Reuse the F-Droid text under `fastlane/metadata/` where it already says the
   right thing, but check every claim in it against the Play build — the existing full description
   mentions the manual update check, which the Play build does not have.
7. **Record the demonstration video** on real hardware, covering the whole notification path, and
   host it somewhere durable.
8. **Complete the Data Safety form** exactly as in section 2, and re-read it against the hosted
   privacy policy line by line. A mismatch between the two is a common and avoidable rejection.
9. **Submit the notification-listener declaration** using the drafted text, with the video link.
10. **Submit the foreground-service-type declaration** for `specialUse`, using the drafted text, and
    confirm the manifest subtype property is still present in the merged manifest.
11. **Answer the usage-access question** wherever the console raises it, using the drafted text.
12. **Fill in App access** with the no-login note and the hardware explanation from section 3.
13. **Upload screenshots** taken on real hardware, including at least one showing the array lit.
14. **Run the internal testing track** on a real supported device, installed from Play rather than
    sideloaded, and verify the whole path end to end: notification access, one notification rule, one
    per-contact rule, one privacy activity rule, the Quick Settings tile, and the renderer restarting
    after a reboot.
15. **Check the pre-launch report** for what it says about the sensitive permissions, and expect it
    to be of limited use given that its test devices cannot run the feature.

### Do not submit until

- [ ] The app title question has an answer from a qualified person, recorded in writing.
- [ ] The privacy policy is hosted, reachable, and has no `TODO` left in it.
- [ ] The backup behaviour has been decided and the policy says what the manifest does.
- [ ] The built `playRelease` manifest has been read with your own eyes and contains neither
      `INTERNET` nor `QUERY_ALL_PACKAGES`.
- [ ] The Data Safety answers and the hosted privacy policy have been compared line by line and
      agree.
- [ ] The device catalogue is restricted to the three supported models.
- [ ] A demonstration video exists and its link is in the notification-listener declaration.
- [ ] The listing's first line states the hardware requirement.
- [ ] The app has been installed from a Play track onto a real supported device and seen to work.
