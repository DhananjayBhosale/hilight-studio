package com.hilight.core;

import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.os.Binder;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.List;

public final class LightsBackend {
    private final IBinder token = new Binder();
    private Object service;
    private Method mGetLights, mOpenSession, mCloseSession, mSetLightStates;
    private int[] ids = new int[0];
    private boolean sessionOpen;
    private int sessionPriority = Integer.MIN_VALUE;

    public void connect() throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "lights");
        if (b == null) throw new IllegalStateException("lights service missing");
        Class<?> iface = Class.forName("android.hardware.lights.ILightsManager");
        service = Class.forName("android.hardware.lights.ILightsManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, b);
        mGetLights = iface.getMethod("getLights");
        mOpenSession = iface.getMethod("openSession", IBinder.class, int.class);
        mCloseSession = iface.getMethod("closeSession", IBinder.class);
        mSetLightStates = iface.getMethod("setLightStates", IBinder.class, int[].class, LightState[].class);

        @SuppressWarnings("unchecked")
        List<Light> all = (List<Light>) mGetLights.invoke(service);
        int n = 0;
        for (Light l : all) if (l.getType() == Light.LIGHT_TYPE_APPLICATION) n++;
        ids = new int[n];
        int k = 0;
        for (Light l : all) if (l.getType() == Light.LIGHT_TYPE_APPLICATION) ids[k++] = l.getId();
        describe(all);
    }

    private void describe(List<Light> all) {
        for (Light l : all) {
            if (l.getType() != Light.LIGHT_TYPE_APPLICATION) continue;
            String period;
            try {
                period = l.getMinUpdatePeriodMillis() + "ms";
            } catch (Throwable t) {
                period = "unknown";
            }
            Log.i("light id=" + l.getId()
                    + " ordinal=" + l.getOrdinal()
                    + " type=" + l.getType()
                    + " rgb=" + l.hasRgbControl()
                    + " brightness=" + l.hasBrightnessControl()
                    + " animation=" + l.hasAnimationControl()
                    + " minUpdatePeriod=" + period);
        }
    }

    public int ledCount() { return ids.length; }

    public boolean isSessionOpen() { return sessionOpen; }

    public int sessionPriority() { return sessionPriority; }

    public void openSession(int priority) {
        try {
            mOpenSession.invoke(service, token, priority);
            sessionOpen = true;
            sessionPriority = priority;
        } catch (Exception e) {
            Log.w("openSession failed: " + e);
        }
    }

    public void closeSession() {
        if (!sessionOpen) return;
        try {
            mCloseSession.invoke(service, token);
        } catch (Exception e) {
            Log.w("closeSession failed: " + e);
        }
        sessionOpen = false;
    }

    public void push(int[] colors) {
        if (!sessionOpen || ids.length == 0) return;
        LightState[] st = new LightState[ids.length];
        for (int i = 0; i < ids.length; i++) {
            st[i] = new LightState.Builder().setColor(colors[i % colors.length]).build();
        }
        try {
            mSetLightStates.invoke(service, token, ids, st);
        } catch (Exception e) {
            Log.w("setLightStates failed: " + e);

            closeSession();
        }
    }
}
