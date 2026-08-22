package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LightsBackendEffectSafetyTest {

    @Test
    public void certifiedProfileRequiresExactFingerprintTopologyAndCapabilities() {
        int[] ids = {8, 1, 7, 2, 6, 3, 5, 4};
        boolean[] rgb = allTrue();
        boolean[] animation = allTrue();
        long[] periods = allPeriods(66);

        assertTrue(LightsBackend.isCertifiedProfile(
                LightsBackend.CERTIFIED_FINGERPRINT, ids, rgb, animation, periods));
        assertFalse(LightsBackend.isCertifiedProfile(
                "google/kodiak/kodiak:17/other:user/release-keys",
                ids, rgb, animation, periods));

        int[] wrongId = ids.clone();
        wrongId[0] = 9;
        assertFalse(LightsBackend.isCertifiedProfile(
                LightsBackend.CERTIFIED_FINGERPRINT, wrongId, rgb, animation, periods));
        boolean[] noRgb = rgb.clone();
        noRgb[3] = false;
        assertFalse(LightsBackend.isCertifiedProfile(
                LightsBackend.CERTIFIED_FINGERPRINT, ids, noRgb, animation, periods));
        boolean[] noAnimation = animation.clone();
        noAnimation[4] = false;
        assertFalse(LightsBackend.isCertifiedProfile(
                LightsBackend.CERTIFIED_FINGERPRINT, ids, rgb, noAnimation, periods));
        long[] noPeriod = periods.clone();
        noPeriod[5] = 0;
        assertFalse(LightsBackend.isCertifiedProfile(
                LightsBackend.CERTIFIED_FINGERPRINT, ids, rgb, animation, noPeriod));
    }

    @Test
    public void capabilityGetterFailuresKeepTopologyButDisableEffects() {
        for (int failure = 0; failure < 3; failure++) {
            List<LightsBackend.CapabilitySource> sources = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                sources.add(source(8 - i, failure == 0 && i == 2,
                        failure == 1 && i == 2, failure == 2 && i == 2));
            }
            LightsBackend.CapabilityRead read = LightsBackend.readCapabilities(sources);
            assertEquals(Arrays.toString(new int[]{8, 7, 6, 5, 4, 3, 2, 1}),
                    Arrays.toString(read.ids));
            assertEquals(8, read.ids.length);
            assertFalse(read.capabilitiesKnown);
            assertEquals(0, read.quantumMs);
            assertFalse(LightsBackend.isCertifiedProfile(
                    LightsBackend.CERTIFIED_FINGERPRINT, read.ids, read.rgbControl,
                    read.animationControl, read.minUpdatePeriods));
        }
    }

    @Test
    public void validatorAcceptsThePreemptiveBlackTerminatorAtCertifiedQuantum() {
        SequenceCompiler.Plan terminator = SequenceCompiler.blackTerminator(8, 66);
        assertTrue(LightsBackend.isEffectPlanSafe(terminator, 33, 1));
        assertTrue(LightsBackend.isEffectPlanSafe(66,
                SequenceCompiler.Interpolation.LINEAR, delays(8, 66, 2),
                blackColors(8, 2), 33, 1));

        long[][] badTiming = delays(8, 66, 2);
        badTiming[0][1] = 32;
        assertFalse(LightsBackend.isEffectPlanSafe(66,
                SequenceCompiler.Interpolation.LINEAR, badTiming,
                blackColors(8, 2), 33, 1));
    }

    @Test
    public void validatorAcceptsBoundedEightByNinePlans() throws Exception {
        SequenceCompiler.Plan compilerPlan = SequenceCompiler.compile(
                config("mode", "wave", "speedMs", 320), 0, 8, 40, 1.0, 123L);
        assertTrue(LightsBackend.isEffectPlanSafe(compilerPlan, 40, 1));

        long[][] delays = delays(8, 40);
        int[][] colors = colors(8, 9);

        assertTrue(LightsBackend.isEffectPlanSafe(320,
                SequenceCompiler.Interpolation.LINEAR, delays, colors, 40, 1));
        assertTrue(LightsBackend.isEffectPlanSafe(320,
                SequenceCompiler.Interpolation.NONE, delays, colors, 40, Integer.MAX_VALUE));
    }

    @Test
    public void validatorRejectsTrackCountAndEveryMalformedTrackShape() {
        long[][] delays = delays(8, 40);
        int[][] colors = colors(8, 9);

        assertFalse(LightsBackend.isEffectPlanSafe(320,
                SequenceCompiler.Interpolation.LINEAR, trim(delays, 7), trim(colors, 7), 40, 1));

        long[][] badFirstDelay = copy(delays);
        badFirstDelay[0][0] = 1;
        assertFalse(LightsBackend.isEffectPlanSafe(320,
                SequenceCompiler.Interpolation.LINEAR, badFirstDelay, colors, 40, 1));

        long[][] badLaterDelay = copy(delays);
        badLaterDelay[0][1] = 39;
        assertFalse(LightsBackend.isEffectPlanSafe(320,
                SequenceCompiler.Interpolation.LINEAR, badLaterDelay, colors, 40, 1));

        long[][] badDuration = copy(delays);
        badDuration[0][8] = 41;
        assertFalse(LightsBackend.isEffectPlanSafe(320,
                SequenceCompiler.Interpolation.LINEAR, badDuration, colors, 40, 1));

        int[][] badClosure = copy(colors);
        badClosure[0][8] = 0xFF000000;
        assertFalse(LightsBackend.isEffectPlanSafe(320,
                SequenceCompiler.Interpolation.LINEAR, delays, badClosure, 40, 1));

        long[][] tenPoints = new long[8][];
        int[][] tenColors = new int[8][];
        for (int i = 0; i < 8; i++) {
            tenPoints[i] = new long[10];
            tenColors[i] = new int[10];
            for (int j = 1; j < 10; j++) tenPoints[i][j] = 40;
            tenColors[i][0] = tenColors[i][9] = 0xFFFF0000;
        }
        assertFalse(LightsBackend.isEffectPlanSafe(360,
                SequenceCompiler.Interpolation.LINEAR, tenPoints, tenColors, 40, 1));

        assertFalse(LightsBackend.isEffectPlanSafe(320,
                null, delays, colors, 40, 1));
        assertFalse(LightsBackend.isEffectPlanSafe(320,
                SequenceCompiler.Interpolation.LINEAR, delays, colors, 40, 0));
    }

    private static LightsBackend.CapabilitySource source(final int id,
                                                            final boolean throwRgb,
                                                            final boolean throwAnimation,
                                                            final boolean throwPeriod) {
        return new LightsBackend.CapabilitySource() {
            @Override public int id() { return id; }

            @Override public boolean hasRgbControl() {
                if (throwRgb) throw new AssertionError("rgb");
                return true;
            }

            @Override public boolean hasAnimationControl() {
                if (throwAnimation) throw new AssertionError("animation");
                return true;
            }

            @Override public long getMinUpdatePeriodMillis() {
                if (throwPeriod) throw new AssertionError("period");
                return 66;
            }
        };
    }

    private static boolean[] allTrue() {
        boolean[] values = new boolean[8];
        for (int i = 0; i < values.length; i++) values[i] = true;
        return values;
    }

    private static long[] allPeriods(long period) {
        long[] values = new long[8];
        for (int i = 0; i < values.length; i++) values[i] = period;
        return values;
    }

    private static long[][] delays(int lights, long quantum) {
        return delays(lights, quantum, 9);
    }

    private static long[][] delays(int lights, long quantum, int points) {
        long[][] values = new long[lights][points];
        for (int light = 0; light < lights; light++) {
            for (int point = 1; point < points; point++) values[light][point] = quantum;
        }
        return values;
    }

    private static int[][] colors(int lights, int points) {
        int[][] values = new int[lights][points];
        for (int light = 0; light < lights; light++) {
            values[light][0] = 0xFFFF0000;
            values[light][points - 1] = 0xFFFF0000;
        }
        return values;
    }

    private static long[][] copy(long[][] source) {
        long[][] result = new long[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    private static int[][] blackColors(int lights, int points) {
        int[][] values = new int[lights][points];
        for (int light = 0; light < lights; light++) {
            for (int point = 0; point < points; point++) values[light][point] = 0xFF000000;
        }
        return values;
    }

    private static int[][] copy(int[][] source) {
        int[][] result = new int[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    private static long[][] trim(long[][] source, int length) {
        long[][] result = new long[length][];
        System.arraycopy(source, 0, result, 0, length);
        return result;
    }

    private static int[][] trim(int[][] source, int length) {
        int[][] result = new int[length][];
        System.arraycopy(source, 0, result, 0, length);
        return result;
    }

    private static JSONObject config(Object... entries) throws Exception {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        TestConfig cfg = (TestConfig) unsafe.getClass()
                .getMethod("allocateInstance", Class.class).invoke(unsafe, TestConfig.class);
        cfg.values = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            cfg.values.put((String) entries[i], entries[i + 1]);
        }
        return cfg;
    }

    private static final class TestConfig extends JSONObject {
        private Map<String, Object> values;

        @Override
        public String optString(String name, String fallback) {
            Object value = values.get(name);
            return value == null ? fallback : String.valueOf(value);
        }

        @Override
        public long optLong(String name, long fallback) {
            Object value = values.get(name);
            return value instanceof Number ? ((Number) value).longValue() : fallback;
        }

        @Override
        public double optDouble(String name, double fallback) {
            Object value = values.get(name);
            return value instanceof Number ? ((Number) value).doubleValue() : fallback;
        }

        @Override
        public boolean optBoolean(String name, boolean fallback) {
            Object value = values.get(name);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override
        public org.json.JSONArray optJSONArray(String name) {
            return null;
        }
    }
}

