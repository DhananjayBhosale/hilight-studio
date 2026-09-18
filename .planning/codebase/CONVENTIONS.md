# Conventions

- State crosses process boundaries as a complete versioned JSON document; do not add an independent mutable command channel without a lifecycle reason.
- Renderer safety and release behavior lives in `core`, not in Compose UI.
- Keep notification logs free of message text and contact names. Evidence: `NotificationTrigger.kt`, `InspectorScreen.kt`.
- Preserve both privileged hosts when changing core code; validate `scripts/build-helper.sh` as well as the Android module.
- Hidden API reflection is isolated in `LightsBackend`; keep pure decisions or test seams Android-stub-free for local JVM tests.
- A successful binder call proves framework acceptance, not physical LED state.
