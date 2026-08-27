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
     * Lists the app's conversation shortcuts the shell can see.
     *
     * Used to populate the chat picker without waiting for a notification to arrive. Returns a JSON
     * array of ConversationRef objects.
     */
    String getConversations(String pkg) = 4;
}
