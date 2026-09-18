# Architecture

Runtime flow:

1. `NotificationTrigger` extracts a privacy-bounded notification description.
2. `Store` matches a rule and emits one complete JSON state document.
3. `ShizukuBackend` sends JSON over binder; `AdbBackend` writes the same JSON to the bridge file.
4. `Engine` owns alert/ambient/privacy layering, timeouts, safety guards, and session lifecycle.
5. `Renderer` creates frames; `LightsBackend` owns the hidden-framework session and HAL-bound writes.

The app process and privileged renderer are separate runtime/failure boundaries. The renderer is shared by all transports under `core/src`, so transport-only diagnoses require evidence from the host lifecycle or state delivery rather than the frame code.
