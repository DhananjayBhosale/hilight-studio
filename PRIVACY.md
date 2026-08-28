# Privacy policy

**HiLight Studio** (`com.hilight.studio`)

Last updated: 28 August 2026. Applies to version 1.0.6 (version code 7) and later until this
document is revised.

Publisher: `TODO — the name the app is published under, whether that is an individual or a company.`
Contact: `TODO — the address or channel privacy questions should be sent to. Do not leave this blank;
Google Play requires a working contact.`
Governing jurisdiction: `TODO — the country or region whose law applies, if the policy needs to
name one.`

## The short version

HiLight Studio drives the eight-LED array around the camera on a Pixel 11 Pro. To make a light mean
something, it has to know when something happened: a notification arrived, a chosen app came to the
front, the microphone went live. It reads those signals on the device, decides which colour to show,
and forgets them.

There is no account, no analytics, no crash reporting, no advertising library and no telemetry of any
kind. In the Play build there is no networking code at all, so there is nothing for the app to send
and nowhere for it to send it.

## What the app reads

| What | When | Why | Permission involved |
|---|---|---|---|
| Notifications from every app on the phone | Whenever notification access is granted and a notification is posted | To decide whether a rule should light the array, and which colour | `BIND_NOTIFICATION_LISTENER_SERVICE`, granted by you in Settings |
| Which app is in the foreground | Only while at least one "while this app is open" rule exists and the master switch is on | To hold that app's colour while it is on screen | `PACKAGE_USAGE_STATS` (Usage access), optional, granted by you in Settings |
| One contact's display name | Only at the moment you tap a contact in the system contact picker | To name a per-contact rule without you typing the name | None. See "Contacts" below |
| The list of apps that have a launcher icon | When you open the rule picker | To offer apps to write rules for | Declared `<intent>` visibility query, not a permission |
| Whether Android reports the microphone or camera as active, and which package is using it | Only while a microphone or camera rule is enabled | To light the array while recording is in progress | None held by the app; observed by the privileged renderer through AppOps |

Two things that are deliberately *not* on that list. HiLight never opens the microphone or the
camera, and never receives audio or video: the privacy activity rules see only Android's own
"in use" signal. And HiLight never asks for `READ_CONTACTS`.

### Notifications

Notification access is the one permission the app cannot work around, because Android offers no
other way to learn that a message arrived. When it is granted, every notification on the phone
passes through the app's listener. For each one the app extracts a small, fixed set of fields — the
posting package, the notification's stable per-chat id (`shortcutId`), the sender's name, the group
title, the notification title, and a few structural flags such as "this is a group summary" — and
uses them to find a matching rule.

### Contacts

Picking a contact by hand uses Android's own contact picker
(`ActivityResultContracts.PickContact`). The picker returns a URI that carries a one-shot read grant
for the single row you tapped, and the app reads one column from it: the display name. The app has
no `READ_CONTACTS` permission, does not declare one, and cannot read your address book. Choosing a
different contact means opening the picker again.

Most per-contact rules do not use the picker at all. The sender's name is already inside the
notification the listener receives, so the app can offer the chats it has seen and you pick from
that list.

## What is stored, and where

Everything is stored in the app's own private storage, in a single `SharedPreferences` file named
`hilight`, created with `MODE_PRIVATE`. No other app can read it. There is no database and no
cloud storage.

| Stored item | Contents |
|---|---|
| Remembered chats (`conversations`) | For each chat the listener has seen: the app's package name, the chat's display name as that app wrote it, the app's own per-chat id where it provides one, whether it is a group, and when it was last seen. Capped at 300 entries, oldest dropped first |
| Rules (`rules`) | The app package and label, the chat name and chat id for a per-contact rule, an optional keyword you typed, and the look: pattern, colours, duration, speed, brightness, and the screen-off and group switches |
| Privacy activity rules (`privacyRules`) | Microphone or camera, the chosen package or "any app", and the look |
| Last-matched times (`ruleLastMatch`) | When each rule last fired. A rule's identity string contains its chat id or chat name, so a chat name can appear here as well as in the rule itself |
| Presets and the always-on look (`presets`, `ambient`) | Colours, patterns and timings only |
| Settings | Transport choice, master switch, quiet hours, battery and Battery Saver thresholds, Do Not Disturb handling, ambient timeout, session priority, dynamic colour |

The app also writes two small JSON files into its own external files directory
(`Android/data/com.hilight.studio/files/hilight/`), which is how the UI talks to the privileged
renderer when the renderer was started over ADB or as root. Those files carry the master switch,
colours, timings, and — for a microphone or camera rule scoped to one app — that app's package
name. They contain no notification data, no chat names and no contact names.

## What is never stored

**The text of your messages.** The body of a notification is read into memory, used for one purpose
only — testing it against a keyword you typed into a rule, if you typed one — and then discarded
with the rest of that notification. It is never written to storage, never written to a log, and
never included in anything the notification inspector copies or shares.

Where that is enforced in the source, so it can be checked rather than believed:

- `Store.kt` — the inspector's list of recent notifications is a plain in-memory flow, capped at 30
  entries, with a comment stating why it must never be persisted. Nothing writes it to the
  preferences file.
- `InspectorScreen.kt` — the export function `peeksToJson` names each field it writes, and the
  message body is not among them; the file's header states the rule for anything added later.
- `Conversation.kt` — `MessageInfo.describe()`, the human-readable diagnostic, lists the ids and
  names only.
- `NotificationTrigger.kt` — the only reader of the message body is `matchesKeyword`. The log lines
  around it record the package, how the rule matched and which pattern fired, and deliberately
  record neither the message nor the sender.
- `res/values/strings_inspector.xml` — the same promise is written into the user-visible text and
  into the exported file, so a translation cannot quietly invite a message body in.

The inspector's list also disappears whenever the listener restarts, because it only exists to
answer "why did my rule not fire?" about a notification that has just arrived.

One honest caveat. The notification *title* is stored in nothing but is shown in the inspector and
included in its export, because for apps that never adopted `MessagingStyle` the title is the only
place the chat name appears. Most apps put a name there. An app that puts part of a message into
its title would have that part appear in an export you chose to share. If you are sending a
diagnostic to a stranger, read it first.

## What leaves the device

**In the Google Play build: nothing.** That build has no update-checking feature and does not
declare the `INTERNET` permission, so the app cannot open a network connection.

> Stated as an assumption so it can be checked: this section is written on the basis that the Play
> build is produced from the `play` product flavour, which sets `UPDATE_CHECK = false` and does not
> merge the `INTERNET` permission. If a Play build is ever produced from another flavour, this
> section is wrong and must be corrected before that build is published.

The builds published on GitHub and F-Droid have exactly one network path, and only you can trigger
it: tapping **Check for updates** under Setup fetches the public list of releases from
`api.github.com`. That request sends the app's version in the `User-Agent` header and nothing else —
no identifier, no device details, no rules, no notification data, no settings. The response is used
only to compare version numbers. There is no background check, and no check at startup.

GitHub receives that request and, like any web server, may log the connection, including your IP
address. That is GitHub's processing, under GitHub's privacy statement, not the app's.

Two things you can send yourself, deliberately and by hand:

- the notification inspector's **Copy** and **Send** actions, which put the diagnostic on the
  clipboard or into an app you choose;
- the **Copy** and **Send** actions for the ADB setup command, which contain no personal data at all.

Both open the standard Android share sheet, and whatever you choose there decides where the text
goes. Nothing is sent unless you tap one of them.

## Android's own backup

Backup is switched off. The app sets `android:allowBackup="false"`, so its settings file is excluded
from Google account backups and from device-to-device transfer. That closes the one route by which
locally stored names could otherwise have left the phone — Android's default is to include an app's
data, which would have carried remembered chat names and the names inside your rules with it.

The practical cost is that HiLight's settings do not follow you to a new phone. Given that the app is
tied to specific hardware and to a privileged renderer that has to be set up again anyway, that is a
trade worth making.

## Analytics, advertising and third parties

There are none. The app contains no analytics SDK, no crash reporter, no advertising library and no
attribution library. Nothing about how you use the app is measured or transmitted. No data is sold,
rented or shared with anyone, because none is collected.

The app can talk to Shizuku, if you have installed it, in order to start the privileged renderer.
That is a local binder call on your own device. Shizuku is separate software with its own terms; the
app sends it no personal data.

## Deleting your data

| To remove | Do this | What it removes |
|---|---|---|
| The list of remembered chats | Setup → **Forget remembered chats** | The whole `conversations` list, written immediately. Your existing rules keep working, because each rule holds its own copy of the name it matches |
| A chat name held inside a rule | Delete that rule on the Apps tab | The rule, and the chat name and chat id stored in it. The last-matched entry keyed to that rule goes with it |
| The inspector's recent notifications | Close the app, or wait for the listener to restart | The whole in-memory list. It is never on disk to begin with |
| Everything | Uninstall the app, or Settings → Apps → HiLight Studio → Storage → **Clear storage** | The preferences file and the renderer bridge files, in their entirety |

Revoking notification access or Usage access in Android's settings stops the app reading anything
new at once. It does not by itself delete what has already been remembered; use **Forget remembered
chats** for that.

## Children's privacy

`TODO — the owner must decide and state this. The app is not directed at children and has no
content aimed at them, but this section should name the age threshold that applies in the relevant
jurisdiction (for example 13 under COPPA, or 16 under some EU implementations of the GDPR) and state
plainly that the app does not knowingly collect data from children below it. Since the app collects
nothing from anyone, that statement is easy to make truthfully — but it should be the owner's
statement, with the right threshold in it, not a placeholder.`

## Your rights

Because nothing is collected or transmitted, there is no copy of your data anywhere for anyone to
disclose, correct, export or delete on request. Everything the app knows is on your own device, is
listed above, and is under your control through the table in "Deleting your data".

## Changes to this policy

Material changes will be recorded here with a new "last updated" date, and in `CHANGELOG.md` where
they follow a change in the app's behaviour. The history of this file is public in the project's Git
repository, so any revision can be compared with the one before it.
