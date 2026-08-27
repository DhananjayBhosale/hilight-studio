package com.hilight.core;

/**
 * Logging that works in both hosts: the adb helper writes to stdout (its log file), and the Shizuku
 * user service has no stdout worth reading, so everything also goes to logcat under one tag.
 */
public final class Log {

    public static final String TAG = "HiLightCore";

    private Log() { }

    public static void i(String msg) {
        System.out.println("[hilight] " + msg);
        System.out.flush();
        try {
            android.util.Log.i(TAG, msg);
        } catch (RuntimeException ignored) {
            // The local JVM test stub intentionally throws; stdout remains the helper log there.
        }
    }

    public static void w(String msg) {
        System.out.println("[hilight] WARN " + msg);
        System.out.flush();
        try {
            android.util.Log.w(TAG, msg);
        } catch (RuntimeException ignored) {
            // See i(String): real Android provides this API, host tests do not.
        }
    }
}
