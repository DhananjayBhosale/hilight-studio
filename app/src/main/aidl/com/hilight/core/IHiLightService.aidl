package com.hilight.core;

/** Binder API of the Shizuku-hosted user service. */
interface IHiLightService {
    /** Shizuku calls this well-known transaction when it tears the service down. */
    void destroy() = 16777114;

    /** Replaces the full state document (same JSON the adb host reads from state.json). */
    void setState(String json) = 1;

    /** Status document: pid, uid, ledCount, session, priority, mode. */
    String status() = 2;

    /** Number of addressable HiLight LEDs the service found. */
    int ledCount() = 3;

    /**
     * Stops only the release-acknowledged adb helper whose PID and renderer instance both match.
     * An empty instance is the legacy exact-PID/cmdline upgrade path. No other helper is signalled;
     * if one remains, takeover fails closed instead of trusting or terminating the other identity.
     */
    boolean stopAdbRenderers(int expectedPid, String expectedRendererInstanceId) = 4;
}
