package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public final class SequenceCompilerTest {

    @Test
    public void continuousModesUseNinePointLinearCanonicalPlans() throws Exception {
        String[] modes = {"breathe", "pulse", "wave", "rainbow", "comet"};
        for (String mode : modes) {
            SequenceCompiler.Plan plan = SequenceCompiler.compile(
                    config("mode", mode, "speedMs", 800, "color", 0xFFB040E0L),
                    7, 8, 40, 0.5);
            assertPlan(plan, 8, 40);
            assertEquals(SequenceCompiler.Interpolation.LINEAR, plan.interpolation);
            assertEquals(9, plan.tracks.get(0).points.size());
        }
    }

    @Test
    public void continuousSamplingUsesExternalScaleAndExactPeriod() throws Exception {
        JSONObject cfg = config("mode", "breathe", "speedMs", 628, "color", 0xFFFF0000L);
        SequenceCompiler.Plan plan = SequenceCompiler.compile(cfg, 0, 1, 33, 0.5);

        assertPlan(plan, 1, 33);
        assertEquals(628, plan.periodMs);
        assertEquals(Renderer.scale(new Renderer().frame(cfg, 0, 1)[0], 0.5),
                plan.tracks.get(0).colors.get(0).intValue());
        assertEquals(9, plan.tracks.get(0).points.size());
    }

    @Test
    public void blinkChaseAndRotatingCustomUseNoneAndRetainTiming() throws Exception {
        SequenceCompiler.Plan blink = SequenceCompiler.compile(
                config("mode", "blink", "speedMs", 601, "color", 0xFFFF0000L),
                0, 8, 100, 1.0);
        assertPlan(blink, 8, 100);
        assertEquals(SequenceCompiler.Interpolation.NONE, blink.interpolation);
        assertEquals(0, blink.tracks.get(0).delaysMs.get(0).longValue());
        assertEquals(300, blink.tracks.get(0).delaysMs.get(1).longValue());
        assertEquals(301, blink.tracks.get(0).delaysMs.get(2).longValue());
        assertTrue(blink.tracks.get(0).colors.contains(0xFFFF0000));
        assertTrue(blink.tracks.get(0).colors.contains(0xFF000000));

        SequenceCompiler.Plan chase = SequenceCompiler.compile(
                config("mode", "chase", "speedMs", 800, "color", 0xFF00FF00L),
                0, 8, 100, 1.0);
        assertPlan(chase, 8, 100);
        assertEquals(SequenceCompiler.Interpolation.NONE, chase.interpolation);
        assertEquals(9, chase.tracks.get(0).points.size());
        assertEquals(100, chase.tracks.get(0).delaysMs.get(1).longValue());
        assertTrue(chase.tracks.get(0).colors.contains(0xFF00FF00));
        assertTrue(chase.tracks.get(0).colors.contains(0xFF000000));

        SequenceCompiler.Plan custom = SequenceCompiler.compile(
                config("mode", "custom", "rotateMs", 100, "color", 0xFFFF0000L),
                0, 8, 25, 1.0);
        assertPlan(custom, 8, 25);
        assertEquals(SequenceCompiler.Interpolation.NONE, custom.interpolation);
        assertEquals(100, custom.tracks.get(0).delaysMs.get(1).longValue());
    }

    @Test
    public void seededRandomSupportsSmoothSteppedAndPerLedPlans() throws Exception {
        JSONObject smoothCfg = config("mode", "random", "randomIntervalMs", 200,
                "randomSmooth", true, "randomPerLed", false, "randomSaturation", 0.8);
        SequenceCompiler.Plan smooth = SequenceCompiler.compile(smoothCfg, 0, 8, 25, 1.0, 1234L);
        SequenceCompiler.Plan same = SequenceCompiler.compile(smoothCfg, 0, 8, 25, 1.0, 1234L);
        SequenceCompiler.Plan changed = SequenceCompiler.compile(smoothCfg, 0, 8, 25, 1.0, 1235L);
        assertPlan(smooth, 8, 25);
        assertEquals(SequenceCompiler.Interpolation.LINEAR, smooth.interpolation);
        assertPlansHaveSameColors(smooth, same);
        assertPlansHaveDifferentColors(smooth, changed);
        for (int stage = 0; stage < 8; stage++) {
            assertEquals(smooth.tracks.get(0).colors.get(stage),
                    smooth.tracks.get(1).colors.get(stage));
        }

        JSONObject steppedCfg = config("mode", "random", "randomIntervalMs", 200,
                "randomSmooth", false, "randomPerLed", true);
        SequenceCompiler.Plan stepped = SequenceCompiler.compile(steppedCfg, 0, 8, 25, 1.0, 99L);
        assertPlan(stepped, 8, 25);
        assertEquals(SequenceCompiler.Interpolation.NONE, stepped.interpolation);
        boolean perLedDiffers = false;
        for (int stage = 0; stage < 8; stage++) {
            perLedDiffers |= !stepped.tracks.get(0).colors.get(stage)
                    .equals(stepped.tracks.get(1).colors.get(stage));
        }
        assertTrue(perLedDiffers);
    }

    @Test
    public void randomPhaseContinuesSmoothAndSteppedCyclesWithinNinePoints() throws Exception {
        long interval = 200;
        long seed = 1234L;
        JSONObject smoothCfg = config("mode", "random", "randomIntervalMs", interval,
                "randomSmooth", true, "randomPerLed", false, "randomSaturation", 0.8);
        SequenceCompiler.Plan smoothAtStart = SequenceCompiler.compile(
                smoothCfg, 0, 8, 25, 1.0, seed);
        SequenceCompiler.Plan smoothAtBoundary = SequenceCompiler.compile(
                smoothCfg, interval, 8, 25, 1.0, seed);
        SequenceCompiler.Plan smoothAtHalf = SequenceCompiler.compile(
                smoothCfg, interval + interval / 2, 8, 25, 1.0, seed);
        assertPlan(smoothAtStart, 8, interval);
        assertPlan(smoothAtBoundary, 8, interval);
        assertPlan(smoothAtHalf, 8, interval);
        assertEquals(smoothAtStart.tracks.get(0).colors.get(1),
                smoothAtBoundary.tracks.get(0).colors.get(0));
        assertEquals(Renderer.mix(smoothAtBoundary.tracks.get(0).colors.get(0),
                        smoothAtBoundary.tracks.get(0).colors.get(1), 0.5),
                smoothAtHalf.tracks.get(0).colors.get(0).intValue());
        assertTrue(smoothAtHalf.tracks.get(0).colors.get(0)
                != smoothAtBoundary.tracks.get(0).colors.get(0));
        assertTrue(smoothAtHalf.tracks.get(0).colors.get(0)
                != smoothAtBoundary.tracks.get(0).colors.get(1));
        assertTrue(smoothAtBoundary.tracks.get(0).colors.get(0)
                != smoothAtStart.tracks.get(0).colors.get(0));

        JSONObject steppedCfg = config("mode", "random", "randomIntervalMs", interval,
                "randomSmooth", false, "randomPerLed", false);
        SequenceCompiler.Plan steppedAtStart = SequenceCompiler.compile(
                steppedCfg, 0, 8, 25, 1.0, seed);
        SequenceCompiler.Plan steppedAtBoundary = SequenceCompiler.compile(
                steppedCfg, interval, 8, 25, 1.0, seed);
        SequenceCompiler.Plan steppedAtHalf = SequenceCompiler.compile(
                steppedCfg, interval + interval / 2, 8, 25, 1.0, seed);
        assertPlan(steppedAtHalf, 8, interval);
        assertEquals(steppedAtStart.tracks.get(0).colors.get(1),
                steppedAtBoundary.tracks.get(0).colors.get(0));
        assertEquals(steppedAtBoundary.tracks.get(0).colors.get(0),
                steppedAtHalf.tracks.get(0).colors.get(0));
        assertEquals(steppedAtBoundary.tracks.get(0).colors.get(1),
                steppedAtHalf.tracks.get(0).colors.get(1));

        SequenceCompiler.Plan scaledBoundary = SequenceCompiler.compile(
                smoothCfg, interval, 8, 25, 0.5, seed);
        SequenceCompiler.Plan scaledNextBoundary = SequenceCompiler.compile(
                smoothCfg, interval * 2, 8, 25, 0.5, seed);
        SequenceCompiler.Plan scaledHalf = SequenceCompiler.compile(
                smoothCfg, interval + interval / 2, 8, 25, 0.5, seed);
        assertEquals(Renderer.mix(scaledBoundary.tracks.get(0).colors.get(0),
                        scaledNextBoundary.tracks.get(0).colors.get(0), 0.5),
                scaledHalf.tracks.get(0).colors.get(0).intValue());
        assertEquals(Renderer.scale(smoothAtBoundary.tracks.get(0).colors.get(0), 0.5),
                scaledBoundary.tracks.get(0).colors.get(0).intValue());
    }

    @Test
    public void blackTerminatorIsAnOpaqueClosedEightByTwoLinearPlan() {
        SequenceCompiler.Plan plan = SequenceCompiler.blackTerminator(8, 66);
        assertNotNull(plan);
        assertEquals(66, plan.periodMs);
        assertEquals(SequenceCompiler.Interpolation.LINEAR, plan.interpolation);
        assertFalse(plan.mayLight());
        assertEquals(8, plan.tracks.size());
        for (SequenceCompiler.Track track : plan.tracks) {
            assertEquals(2, track.points.size());
            assertEquals(0, track.delaysMs.get(0).longValue());
            assertEquals(66, track.delaysMs.get(1).longValue());
            assertEquals(0xFF000000, track.colors.get(0).intValue());
            assertEquals(0xFF000000, track.colors.get(1).intValue());
        }
        assertNull(SequenceCompiler.blackTerminator(0, 66));
        assertNull(SequenceCompiler.blackTerminator(8, 0));
        assertNull(SequenceCompiler.blackTerminator(37, 66));
        assertNull(SequenceCompiler.blackTerminator(8, -1));
    }

    @Test
    public void staticAndUnknownModesRejectForOrdinaryOutputHandling() throws Exception {
        assertNull(SequenceCompiler.compile(config("mode", "off"), 0, 8, 25, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "solid"), 0, 8, 25, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "gradient"), 0, 8, 25, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "custom", "rotateMs", 50),
                0, 8, 25, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "unknown"), 0, 8, 25, 1.0));
        assertNull(SequenceCompiler.compile(null, 0, 8, 25, 1.0));
    }

    @Test
    public void unsafeTimingAndTopologyRejectSafely() throws Exception {
        assertNull(SequenceCompiler.compile(config("mode", "wave", "speedMs", 60),
                0, 8, 61, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "blink", "speedMs", 601),
                0, 8, 301, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "chase", "speedMs", 600),
                0, 8, 76, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "custom", "rotateMs", 100),
                0, 8, 101, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "random", "randomIntervalMs", 200),
                0, 8, 201, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "wave", "speedMs", 800),
                0, 9, 40, 1.0));
        assertNull(SequenceCompiler.compile(config("mode", "random", "randomIntervalMs", 200),
                0, 9, 25, 1.0));
    }

    private static void assertPlan(SequenceCompiler.Plan plan, int ledCount, long quantum) {
        assertNotNull(plan);
        assertEquals(ledCount, plan.tracks.size());
        int total = 0;
        for (SequenceCompiler.Track track : plan.tracks) {
            assertTrue(track.points.size() <= 9);
            total += track.points.size();
            assertEquals(0, track.delaysMs.get(0).longValue());
            for (int i = 1; i < track.delaysMs.size(); i++) {
                assertTrue(track.delaysMs.get(i) > 0);
                assertTrue(track.delaysMs.get(i) >= quantum);
            }
            assertEquals(plan.periodMs, track.totalDurationMs());
            assertEquals(track.colors.get(0),
                    track.colors.get(track.colors.size() - 1));
        }
        assertTrue(total <= 72);
    }

    private static void assertPlansHaveSameColors(SequenceCompiler.Plan first,
                                                    SequenceCompiler.Plan second) {
        assertNotNull(second);
        assertEquals(first.tracks.size(), second.tracks.size());
        for (int i = 0; i < first.tracks.size(); i++) {
            assertEquals(first.tracks.get(i).colors, second.tracks.get(i).colors);
        }
    }

    private static void assertPlansHaveDifferentColors(SequenceCompiler.Plan first,
                                                       SequenceCompiler.Plan second) {
        assertNotNull(second);
        boolean different = false;
        for (int i = 0; i < first.tracks.size(); i++) {
            different |= !first.tracks.get(i).colors.equals(second.tracks.get(i).colors);
        }
        assertTrue(different);
    }

    /* The Android unit-test class path supplies the framework's stub JSONObject.
     * This tiny test double keeps these tests Android-free without changing the
     * production build just to add a JSON test dependency. */
    private static JSONObject config(Object... entries) throws Exception {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        TestConfig cfg = (TestConfig) unsafe.getClass()
                .getMethod("allocateInstance", Class.class).invoke(unsafe, TestConfig.class);
        cfg.values = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) cfg.values.put((String) entries[i], entries[i + 1]);
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
