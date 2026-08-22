package com.hilight.core;

import org.json.JSONObject;

public final class Engine {
    public static final long FRAME_MS = SafetyGuard.FRAME_MS;

    public static final long ALERT_MAX_MS = 60_000;
    public static final long DEFAULT_AMBIENT_TIMEOUT_MS = 30_000;

    public static final long DUTY_WINDOW_MS = SafetyGuard.DUTY_WINDOW_MS;
    public static final double MAX_DUTY = SafetyGuard.MAX_DUTY;

    public static final long TAPER_AFTER_MS = SafetyGuard.TAPER_AFTER_MS;
    public static final long TAPER_RAMP_MS = SafetyGuard.TAPER_RAMP_MS;
    public static final double TAPER_FLOOR = SafetyGuard.TAPER_FLOOR;

    private final LightsBackend lights = new LightsBackend();
    private final Renderer renderer = new Renderer();
    private final SafetyGuard safety = new SafetyGuard();
    private final OutputGate gate = new OutputGate();
    private final Object lock = new Object();

    private Thread thread;
    private volatile boolean running;

    private JSONObject state = new JSONObject();
    private JSONObject alert;
    private long alertId = -1;
    private boolean lastFrameWasAlert;

    private double dim = 1.0;
    private long ambientTimeoutMs = DEFAULT_AMBIENT_TIMEOUT_MS;

    public void start() throws Exception {
        lights.connect();
        Log.i("connected: " + lights.ledCount() + " HiLight LEDs");
        running = true;
        thread = new Thread(this::loop, "hilight-render");
        thread.setDaemon(false);
        thread.start();
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            lights.push(new int[]{0});
            lights.closeSession();
        }
    }

    public int ledCount() { return lights.ledCount(); }

    public void setState(String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            Log.w("bad state json: " + e);
            return;
        }
        synchronized (lock) {
            state = o;
            ambientTimeoutMs = Math.max(1_000, o.optLong("ambientTimeoutMs", DEFAULT_AMBIENT_TIMEOUT_MS));
            dim = Math.max(0.02, Math.min(1.0, o.optDouble("dim", 1.0)));

            if (o.optBoolean("arm", false)) gate.armAmbient(System.currentTimeMillis(), ambientTimeoutMs);
            JSONObject a = o.optJSONObject("alert");
            if (a == null) {
                if (alert != null) Log.i("alert cleared");
                alert = null;
                alertId = -1;

                gate.clearAlert();
                renderer.reset();
            } else {
                long id = a.optLong("id", -1);
                if (id != alertId) {
                    alertId = id;
                    alert = a;
                    long asked = a.optLong("durationMs", 4000);

                    long dur = asked <= 0 ? ambientTimeoutMs : Math.min(asked, ALERT_MAX_MS);
                    gate.startAlert(System.currentTimeMillis(), dur);
                    renderer.reset();
                    Log.i("alert " + id + " " + a.optString("pattern", "pulse") + " for " + dur + "ms"
                            + (dur != asked ? " (asked " + asked + ", capped)" : ""));
                }
            }
        }
    }

    public String status() {
        JSONObject o = new JSONObject();
        try {
            synchronized (lock) {
                o.put("pid", android.os.Process.myPid());
                o.put("uid", android.os.Process.myUid());
                o.put("ts", System.currentTimeMillis());
                o.put("ledCount", lights.ledCount());
                o.put("session", lights.isSessionOpen());
                o.put("priority", lights.sessionPriority());
                JSONObject amb = state.optJSONObject("ambient");
                o.put("mode", amb == null ? "off" : amb.optString("mode", "off"));
                o.put("alertId", alertId);
                o.put("timeoutMs", ambientTimeoutMs);
                o.put("dim", dim);
                o.put("ambientRemainingMs", gate.ambientRemainingMs(System.currentTimeMillis()));
                o.put("ambientHeld", gate.isAmbientHeld());
                o.put("resting", safety.isResting());
                o.put("dutyPct", safety.dutyPercent());
                o.put("version", 1);
            }
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private void loop() {
        while (running) {
            try {
                tick();
                Thread.sleep(FRAME_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.w("frame failed: " + t);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void tick() {
        synchronized (lock) {
            if (!running) return;
            boolean enabled = state.optBoolean("enabled", false);
            int priority = state.optInt("priority", 0);

            if (!enabled) {
                release("released HiLight to the system");
                noteDark(now());
                return;
            }

            long now = System.currentTimeMillis();
            OutputGate.Layer layer = gate.next(now);

            if (lastFrameWasAlert && layer != OutputGate.Layer.ALERT) {
                alert = null;
                renderer.reset();

            }
            lastFrameWasAlert = layer == OutputGate.Layer.ALERT;

            JSONObject cfg;
            long t;
            switch (layer) {
                case ALERT:
                    cfg = alert;
                    t = gate.alertElapsed(now);
                    break;
                case AMBIENT:
                    cfg = state.optJSONObject("ambient");
                    t = now;
                    break;
                case BLANK:

                    if (lights.isSessionOpen()) lights.push(protect(new int[]{0}, now));
                    release("nothing left to show — released HiLight to the system");
                    return;
                default:
                    noteDark(now);
                    return;
            }

            if (!lights.isSessionOpen() || priority != lights.sessionPriority()) {
                if (lights.isSessionOpen()) lights.closeSession();
                lights.openSession(priority);
            }
            int[] frame = renderer.frame(cfg, t, Math.max(1, lights.ledCount()));
            lights.push(protect(frame, now));
        }
    }

    private int[] protect(int[] frame, long now) {
        return safety.apply(frame, now, dim);
    }

    private void noteDark(long now) {
        safety.apply(BLANK, now, dim);
    }

    private void release(String why) {
        if (!lights.isSessionOpen()) return;
        lights.closeSession();
        Log.i(why);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static final int[] BLANK = {0};
}
