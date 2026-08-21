package com.hilight.core;

import android.hardware.lights.ColorSequence;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.MultiLightEffect;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Thin wrapper over the hidden ILightsManager binder interface.
 *
 * Reflection rather than the public android.hardware.lights.LightsManager because that needs a
 * Context, and both callers here (the adb-launched helper and the Shizuku user service) are plain
 * processes. The binder route also exposes openSession(token, priority), which the public API does
 * not.
 *
 * Requires android.permission.CONTROL_DEVICE_LIGHTS, which is signature|privileged — this class only
 * works in a process running as the shell UID (2000) or root.
 */
public final class LightsBackend {

    static final String CERTIFIED_FINGERPRINT =
            "google/kodiak/kodiak:17/CD1A.260714.001.A9/15938155:user/release-keys";
    private static final int CERTIFIED_LIGHT_COUNT = 8;
    private static final int MAX_DESCRIBED_APPLICATION_LIGHTS = 32;

    private final IBinder token = new Binder();
    private Object service;
    private Method mGetLights, mOpenSession, mCloseSession, mSetLightStates, mSetLightEffect;
    private int[] ids = new int[0];
    private final List<Light> applicationLights = new ArrayList<>();
    private boolean sessionOpen;
    private int sessionPriority = Integer.MIN_VALUE;
    private boolean effectActive;
    private boolean effectsDisabled;
    private boolean effectsCertified;
    private long certifiedQuantumMs;

    /** Internal adapter used to read capabilities without coupling the safe reader to Light. */
    interface CapabilitySource {
        int id();
        boolean hasRgbControl();
        boolean hasAnimationControl();
        long getMinUpdatePeriodMillis();
    }

    /** Package-private model keeps topology even when an individual capability is unavailable. */
    static final class CapabilityRead {
        final int[] ids;
        final boolean[] rgbControl;
        final boolean[] animationControl;
        final long[] minUpdatePeriods;
        final boolean[] rgbKnown;
        final boolean[] animationKnown;
        final boolean[] minUpdatePeriodKnown;
        final boolean capabilitiesKnown;
        final long quantumMs;

        CapabilityRead(int[] ids, boolean[] rgbControl, boolean[] animationControl,
                       long[] minUpdatePeriods, boolean[] rgbKnown,
                       boolean[] animationKnown, boolean[] minUpdatePeriodKnown,
                       boolean capabilitiesKnown, long quantumMs) {
            this.ids = ids;
            this.rgbControl = rgbControl;
            this.animationControl = animationControl;
            this.minUpdatePeriods = minUpdatePeriods;
            this.rgbKnown = rgbKnown;
            this.animationKnown = animationKnown;
            this.minUpdatePeriodKnown = minUpdatePeriodKnown;
            this.capabilitiesKnown = capabilitiesKnown;
            this.quantumMs = quantumMs;
        }
    }

    /** Reads each required getter independently; a failure is conservative but non-fatal. */
    static CapabilityRead readCapabilities(List<? extends CapabilitySource> sources) {
        int count = sources == null ? 0 : sources.size();
        int[] ids = new int[count];
        boolean[] rgb = new boolean[count];
        boolean[] animation = new boolean[count];
        long[] periods = new long[count];
        boolean[] rgbKnown = new boolean[count];
        boolean[] animationKnown = new boolean[count];
        boolean[] periodKnown = new boolean[count];
        boolean known = true;
        for (int i = 0; i < count; i++) {
            CapabilitySource source = sources.get(i);
            ids[i] = source.id();
            try {
                rgb[i] = source.hasRgbControl();
                rgbKnown[i] = true;
            } catch (Throwable ignored) {
                known = false;
            }
            try {
                animation[i] = source.hasAnimationControl();
                animationKnown[i] = true;
            } catch (Throwable ignored) {
                known = false;
            }
            try {
                periods[i] = source.getMinUpdatePeriodMillis();
                periodKnown[i] = true;
            } catch (Throwable ignored) {
                known = false;
            }
        }
        return new CapabilityRead(ids, rgb, animation, periods, rgbKnown,
                animationKnown, periodKnown, known,
                known ? slowestPositivePeriod(periods) : 0);
    }

    private static CapabilitySource capabilitySource(final Light light) {
        return new CapabilitySource() {
            @Override public int id() { return light.getId(); }
            @Override public boolean hasRgbControl() { return light.hasRgbControl(); }
            @Override public boolean hasAnimationControl() { return light.hasAnimationControl(); }
            @Override public long getMinUpdatePeriodMillis() {
                return light.getMinUpdatePeriodMillis();
            }
        };
    }

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
        mSetLightEffect = null;
        try {
            mSetLightEffect = iface.getMethod("setLightEffect", IBinder.class,
                    MultiLightEffect.class);
        } catch (NoSuchMethodException missingEffectMethod) {
            // The final certification log below reports the single policy reason.
        }

        @SuppressWarnings("unchecked")
        List<Light> all = (List<Light>) mGetLights.invoke(service);
        applicationLights.clear();
        for (Light l : all) {
            if (l.getType() == Light.LIGHT_TYPE_APPLICATION) applicationLights.add(l);
        }
        Collections.sort(applicationLights, Comparator.comparingInt(Light::getId));
        List<CapabilitySource> capabilitySources = new ArrayList<>(applicationLights.size());
        for (Light light : applicationLights) capabilitySources.add(capabilitySource(light));
        CapabilityRead capabilities = readCapabilities(capabilitySources);
        ids = capabilities.ids;
        effectActive = false;
        effectsDisabled = false;
        certifiedQuantumMs = capabilities.quantumMs;
        effectsCertified = mSetLightEffect != null && capabilities.capabilitiesKnown
                && isCertifiedProfile(Build.FINGERPRINT, capabilities.ids,
                capabilities.rgbControl, capabilities.animationControl,
                capabilities.minUpdatePeriods);
        describe(all);
        if (mSetLightEffect == null) {
            Log.i("hardware effects disabled: setLightEffect unavailable");
        } else if (!CERTIFIED_FINGERPRINT.equals(Build.FINGERPRINT)) {
            Log.i("hardware effects disabled: uncertified fingerprint");
        } else if (!effectsCertified) {
            Log.i("hardware effects disabled: uncertified application-light topology/capability");
        } else {
            Log.i("hardware effects certified: Pixel 8-light profile, quantum="
                    + certifiedQuantumMs + "ms");
        }
    }

    /**
     * Logs what the framework reports about every light it hands us.
     *
     * Worth the few lines: it is the first thing needed to tell "this device has no addressable
     * array" apart from "the renderer never got hold of it", which is otherwise guesswork from a bug
     * report, and it is the only place these capabilities are observable — `dumpsys lights` shows
     * ids and colours but nothing about control.
     */
    private void describe(List<Light> all) {
        int described = 0;
        int omitted = 0;
        for (Light l : all) {
            if (l.getType() != Light.LIGHT_TYPE_APPLICATION) continue;
            if (described >= MAX_DESCRIBED_APPLICATION_LIGHTS) {
                omitted++;
                continue;
            }
            CapabilityRead capabilities = readCapabilities(
                    Collections.singletonList(capabilitySource(l)));
            String rgb = capabilities.rgbKnown[0]
                    ? Boolean.toString(capabilities.rgbControl[0]) : "unknown";
            String animation = capabilities.animationKnown[0]
                    ? Boolean.toString(capabilities.animationControl[0]) : "unknown";
            String period = capabilities.minUpdatePeriodKnown[0]
                    ? capabilities.minUpdatePeriods[0] + "ms" : "unknown";
            String brightness;
            try {
                brightness = Boolean.toString(l.hasBrightnessControl());
            } catch (Throwable ignored) {
                brightness = "unknown";
            }
            Log.i("light id=" + l.getId()
                    + " ordinal=" + l.getOrdinal()
                    + " type=" + l.getType()
                    + " rgb=" + rgb
                    + " brightness=" + brightness
                    + " animation=" + animation
                    + " minUpdatePeriod=" + period);
            described++;
        }
        if (omitted > 0) Log.i("light capability description truncated: omitted=" + omitted);
    }

    public int ledCount() { return ids.length; }

    public boolean supportsEffects() { return effectQuantumMs() > 0; }

    /** Returns the certified profile's slowest minimum update period, or zero when unsupported. */
    public long effectQuantumMs() {
        if (effectsDisabled || !effectsCertified) return 0;
        return certifiedQuantumMs;
    }

    /** Pure profile policy; capability arrays may arrive in any order and are matched by id. */
    static boolean isCertifiedProfile(String fingerprint, int[] lightIds,
                                      boolean[] rgbControl, boolean[] animationControl,
                                      long[] minUpdatePeriods) {
        if (!CERTIFIED_FINGERPRINT.equals(fingerprint)
                || lightIds == null || rgbControl == null || animationControl == null
                || minUpdatePeriods == null
                || lightIds.length != CERTIFIED_LIGHT_COUNT
                || rgbControl.length != CERTIFIED_LIGHT_COUNT
                || animationControl.length != CERTIFIED_LIGHT_COUNT
                || minUpdatePeriods.length != CERTIFIED_LIGHT_COUNT) return false;
        for (int expectedId = 1; expectedId <= CERTIFIED_LIGHT_COUNT; expectedId++) {
            int found = -1;
            for (int i = 0; i < lightIds.length; i++) {
                if (lightIds[i] == expectedId) {
                    if (found != -1) return false;
                    found = i;
                }
            }
            if (found == -1 || !rgbControl[found] || !animationControl[found]
                    || minUpdatePeriods[found] <= 0) return false;
        }
        return true;
    }

    private static long slowestPositivePeriod(long[] periods) {
        long slowest = 0;
        for (long period : periods) slowest = Math.max(slowest, period);
        return slowest;
    }

    public boolean isEffectActive() { return effectActive; }

    public boolean isSessionOpen() { return sessionOpen; }

    public int sessionPriority() { return sessionPriority; }

    public void openSession(int priority) {
        effectActive = false;
        try {
            mOpenSession.invoke(service, token, priority);
            sessionOpen = true;
            sessionPriority = priority;
        } catch (Exception e) {
            Log.w("openSession failed: " + e);
        }
    }

    public void closeSession() {
        effectActive = false;
        if (!sessionOpen) return;
        try {
            mCloseSession.invoke(service, token);
        } catch (Exception e) {
            Log.w("closeSession failed: " + e);
        }
        sessionOpen = false;
    }

    /** Submits one finite, preemptive hardware effect. */
    public boolean startEffect(SequenceCompiler.Plan plan, int iterations) {
        if (!sessionOpen || iterations <= 0 || plan == null || !supportsEffects()
                || applicationLights.size() != CERTIFIED_LIGHT_COUNT
                || !isEffectPlanSafe(plan, certifiedQuantumMs, iterations)) {
            return false;
        }
        try {
            MultiLightEffect.Builder effectBuilder = new MultiLightEffect.Builder();
            int interpolation = plan.interpolation == SequenceCompiler.Interpolation.LINEAR
                    ? ColorSequence.INTERPOLATION_MODE_LINEAR
                    : ColorSequence.INTERPOLATION_MODE_NONE;
            for (int i = 0; i < applicationLights.size(); i++) {
                SequenceCompiler.Track track = plan.tracks.get(i);
                ColorSequence.Builder sequenceBuilder = new ColorSequence.Builder();
                for (SequenceCompiler.Point point : track.points) {
                    sequenceBuilder.addControlPoint(point.delayMs, point.color);
                }
                ColorSequence sequence = sequenceBuilder
                        .setInterpolationMode(interpolation)
                        .build();
                effectBuilder.addLightSequence(applicationLights.get(i), sequence);
            }
            MultiLightEffect effect = effectBuilder.setIterations(iterations)
                    .setPreemptive(true)
                    .build();
            mSetLightEffect.invoke(service, token, effect);
            effectActive = true;
            Log.i("hardware effect started: lights=" + applicationLights.size()
                    + " iterations=" + iterations + " period=" + plan.periodMs + "ms"
                    + " interpolation=" + plan.interpolation);
            return true;
        } catch (Throwable failure) {
            effectActive = false;
            effectsDisabled = true;
            Log.w("hardware effects disabled after failure: " + rootCause(failure));
            return false;
        }
    }

    /** Pure defensive check immediately before any MultiLightEffect builder is touched. */
    static boolean isEffectPlanSafe(SequenceCompiler.Plan plan, long quantumMs, int iterations) {
        if (plan == null || plan.tracks == null
                || plan.tracks.size() != CERTIFIED_LIGHT_COUNT) return false;
        try {
            long[][] delays = new long[CERTIFIED_LIGHT_COUNT][];
            int[][] colors = new int[CERTIFIED_LIGHT_COUNT][];
            for (int i = 0; i < CERTIFIED_LIGHT_COUNT; i++) {
                SequenceCompiler.Track track = plan.tracks.get(i);
                if (track == null || track.points == null
                        || track.points.size() < 1 || track.points.size() > 9) return false;
                delays[i] = new long[track.points.size()];
                colors[i] = new int[track.points.size()];
                for (int j = 0; j < track.points.size(); j++) {
                    SequenceCompiler.Point point = track.points.get(j);
                    if (point == null) return false;
                    delays[i][j] = point.delayMs;
                    colors[i][j] = point.color;
                }
            }
            return isEffectPlanSafe(plan.periodMs, plan.interpolation, delays, colors,
                    quantumMs, iterations);
        } catch (RuntimeException malformedPlan) {
            return false;
        }
    }

    /** Pure array policy used by JVM tests without constructing framework Light/Binder objects. */
    static boolean isEffectPlanSafe(long periodMs, SequenceCompiler.Interpolation interpolation,
                                    long[][] delays, int[][] colors, long quantumMs,
                                    int iterations) {
        if (periodMs <= 0 || quantumMs <= 0 || iterations <= 0
                || interpolation == null
                || (interpolation != SequenceCompiler.Interpolation.LINEAR
                && interpolation != SequenceCompiler.Interpolation.NONE)
                || delays == null || colors == null
                || delays.length != CERTIFIED_LIGHT_COUNT
                || colors.length != CERTIFIED_LIGHT_COUNT) return false;
        int total = 0;
        for (int light = 0; light < delays.length; light++) {
            long[] trackDelays = delays[light];
            int[] trackColors = colors[light];
            if (trackDelays == null || trackColors == null
                    || trackDelays.length != trackColors.length
                    || trackDelays.length < 1 || trackDelays.length > 9) return false;
            if (total > 72 - trackDelays.length) return false;
            total += trackDelays.length;
            if (trackDelays[0] != 0
                    || trackColors[0] != trackColors[trackColors.length - 1]) return false;
            long duration = 0;
            for (int point = 0; point < trackDelays.length; point++) {
                long delay = trackDelays[point];
                if (point > 0 && (delay <= 0 || delay < quantumMs)) return false;
                if (delay > Long.MAX_VALUE - duration) return false;
                duration += delay;
            }
            if (duration != periodMs) return false;
        }
        return total <= 72;
    }

    private static String rootCause(Throwable failure) {
        Throwable root = failure;
        while (root instanceof InvocationTargetException && root.getCause() != null) {
            root = root.getCause();
        }
        return root.toString();
    }

    /** Pushes one frame. [colors] is indexed per LED; a shorter array is repeated. */
    public void push(int[] colors) {
        if (!sessionOpen || ids.length == 0) return;
        LightState[] st = new LightState[ids.length];
        for (int i = 0; i < ids.length; i++) {
            st[i] = new LightState.Builder().setColor(colors[i % colors.length]).build();
        }
        try {
            mSetLightStates.invoke(service, token, ids, st);
            effectActive = false;
        } catch (Exception e) {
            Log.w("setLightStates failed: " + e);
            // Tell the service the session is over before dropping the local flag. A transient
            // binder failure does not mean the far side closed anything, and simply forgetting the
            // session here left the caller reopening a token the service still held open.
            closeSession();
        }
    }
}
