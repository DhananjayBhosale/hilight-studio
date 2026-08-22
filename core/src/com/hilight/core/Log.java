package com.hilight.core;

public final class Log {
    public static final String TAG = "HiLightCore";

    private Log() { }

    public static void i(String msg) {
        System.out.println("[hilight] " + msg);
        System.out.flush();
        android.util.Log.i(TAG, msg);
    }

    public static void w(String msg) {
        System.out.println("[hilight] WARN " + msg);
        System.out.flush();
        android.util.Log.w(TAG, msg);
    }
}
