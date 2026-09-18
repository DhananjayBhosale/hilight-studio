# HiLight Studio 1.0.14 release

Version code: 15. GitHub package: com.hilight.studio. Play package remains com.highlight.studio in the dedicated Play branch.

Changes: root command timeout handling, configurable reminders, rule backup/import, optional icon colors, grouped independent rules, finite battery gauge. No new permissions added by this feedback patch.

Independent review: Codex and Claude Code claude-sonnet-5 at xhigh agreed the changes can be described as implemented locally, with limitations. 367 unit tests and debug build/release lint passed before the already-merged maintenance dependency integration. Final integrated signed-build checks are recorded with the release artifacts.

Known limits: KernelSU and stuck-LED reports still require affected-device confirmation; recording-only detection unresolved; Android deep sleep may delay reminders. Import uses a bounded synchronous preference commit; existing group visual order may change to match stored priority. Neither is represented as fixed in issue replies. No hardware attached for this release.

Release plan: publish signed GitHub APK and checksum, then the separately verified Play AAB to the existing track. Play must retain its application ID, official in-app updates, narrow package visibility and notification-access disclosure. Google review/availability is separate from upload success.

Regression response: stop further rollout if data loss, startup failures or persistent LEDs are reported. Do not downgrade versionCode or advise clearing data; issue a higher-code correction preserving preferences. Keep the preceding release available.
