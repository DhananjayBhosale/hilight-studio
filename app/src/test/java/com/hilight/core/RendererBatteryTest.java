package com.hilight.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class RendererBatteryTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int LEDS = 8;
    /** Long after any fill sweep has finished. */
    private static final long SETTLED = 100_000;

    private static JSONObject gauge(double level, boolean byLevel) throws Exception {
        JSONObject o = new JSONObject();
        o.put("pattern", "battery");
        o.put("level", level);
        o.put("byLevel", byLevel);
        o.put("speedMs", 1000);
        o.put("color", 0xFFFFFFFFL);
        return o;
    }

    private static int[] frame(JSONObject cfg, long t) {
        return new Renderer().frame(cfg, t, LEDS);
    }

    @Test
    public void aFullBatteryLightsEveryLed() throws Exception {
        int[] all = new int[LEDS];
        java.util.Arrays.fill(all, WHITE);
        assertArrayEquals(all, frame(gauge(1.0, false), SETTLED));
    }

    @Test
    public void anEmptyBatteryStaysDark() throws Exception {
        assertArrayEquals(new int[LEDS], frame(gauge(0.0, false), SETTLED));
    }

    @Test
    public void theBoundaryLedIsDimmedToItsShare() throws Exception {
        // 73% of eight LEDs is 5.84: five full, the sixth at 84% of its share, two dark
        int[] out = frame(gauge(0.73, false), SETTLED);
        for (int i = 0; i < 5; i++) assertEquals("led " + i, WHITE, out[i]);
        double share = 0.73 * LEDS - 5;
        assertEquals(Renderer.scale(WHITE, Renderer.PARTIAL_FLOOR + (1 - Renderer.PARTIAL_FLOOR) * share), out[5]);
        assertEquals(0, out[6]);
        assertEquals(0, out[7]);
    }

    @Test
    public void allAtOnceIsCompleteFromTheFirstFrame() throws Exception {
        assertArrayEquals(frame(gauge(1.0, false), SETTLED), frame(gauge(1.0, false), 0));
        assertArrayEquals(frame(gauge(0.5, false), SETTLED), frame(gauge(0.5, false), 37));
    }

    private static JSONObject stepGauge(double level) throws Exception {
        JSONObject o = gauge(level, false);
        o.put("fill", "step");
        return o;
    }

    private static int partial(double share) {
        return Renderer.scale(WHITE, Renderer.PARTIAL_FLOOR + (1 - Renderer.PARTIAL_FLOOR) * share);
    }

    @Test
    public void oneByOneLightsEachLedInTurn() throws Exception {
        // speedMs is 1000: LED 0 eases in during the first second, LED 3 during the fourth
        int[] early = frame(stepGauge(1.0), 500);
        assertEquals(partial(0.5), early[0]);
        for (int i = 1; i < LEDS; i++) assertEquals("led " + i, 0, early[i]);

        int[] later = frame(stepGauge(1.0), 3500);
        for (int i = 0; i < 3; i++) assertEquals("led " + i, WHITE, later[i]);
        assertEquals(partial(0.5), later[3]);
        for (int i = 4; i < LEDS; i++) assertEquals("led " + i, 0, later[i]);
    }

    @Test
    public void oneByOneHoldsAtTheLevelGoesDarkAndCountsAgain() throws Exception {
        // 62% is 4.96 LEDs: five steps of 1 s, a 3 s hold, a 1 s gap, then the count restarts
        JSONObject cfg = stepGauge(0.62);

        int[] held = frame(cfg, 5_500);
        for (int i = 0; i < 4; i++) assertEquals("led " + i, WHITE, held[i]);
        assertEquals(partial(0.62 * LEDS - 4), held[4]);
        for (int i = 5; i < LEDS; i++) assertEquals("led " + i, 0, held[i]);

        assertArrayEquals(new int[LEDS], frame(cfg, 8_500));

        int[] again = frame(cfg, 9_500);
        assertEquals(partial(0.5), again[0]);
        for (int i = 1; i < LEDS; i++) assertEquals("led " + i, 0, again[i]);
    }

    @Test
    public void oneByOneNeverOvershootsTheBoundaryLed() throws Exception {
        // while the fifth LED is easing in it is capped at its 0.96 share, not its step progress
        int[] out = frame(stepGauge(0.62), 4_990);
        assertEquals(partial(0.62 * LEDS - 4), out[4]);
    }

    @Test
    public void theTipBlinksThreeTimesThenHoldsWhenShownAllAtOnce() throws Exception {
        // 50% is exactly four LEDs, so the tip is LED 3; beats of 300 ms, on first
        JSONObject cfg = gauge(0.5, false);
        cfg.put("blinkTip", true);
        long[] on = {150, 750, 1350, 1800, 5000};
        long[] off = {450, 1050, 1650};
        for (long t : on) {
            int[] out = frame(cfg, t);
            assertEquals("t=" + t, WHITE, out[3]);
            assertEquals("t=" + t, WHITE, out[2]);
        }
        for (long t : off) {
            int[] out = frame(cfg, t);
            assertEquals("t=" + t, 0, out[3]);
            // only the tip blinks; the rest of the run stays lit
            assertEquals("t=" + t, WHITE, out[2]);
            assertEquals("t=" + t, 0, out[4]);
        }
    }

    @Test
    public void theTipBlinksAfterTheCountAndTheHoldGrowsToFit() throws Exception {
        // 62%: five steps of 1 s, then 1.8 s of blinking, then the 3 s hold, then the 1 s gap
        JSONObject cfg = stepGauge(0.62);
        cfg.put("blinkTip", true);
        int tip = partial(0.62 * LEDS - 4);

        assertEquals(tip, frame(cfg, 5_150)[4]);         // first on beat
        assertEquals(0, frame(cfg, 5_450)[4]);           // first off beat
        assertEquals(WHITE, frame(cfg, 5_450)[3]);       // the LED before it is untouched
        assertEquals(tip, frame(cfg, 5_750)[4]);
        assertEquals(0, frame(cfg, 6_650)[4]);           // third off beat
        assertEquals(tip, frame(cfg, 7_000)[4]);         // holding
        assertEquals(tip, frame(cfg, 9_700)[4]);         // still holding, hold ends at 9 800
        assertArrayEquals(new int[LEDS], frame(cfg, 10_000));   // gap
        assertEquals(partial(0.5), frame(cfg, 11_300)[0]);       // counting again
    }

    @Test
    public void withoutBlinkingTheTimelineIsUnchanged() throws Exception {
        JSONObject cfg = stepGauge(0.62);
        cfg.put("blinkTip", false);
        assertArrayEquals(frame(stepGauge(0.62), 5_450), frame(cfg, 5_450));
        assertArrayEquals(new int[LEDS], frame(cfg, 8_500));
    }

    @Test
    public void anEmptyBatteryCountsNothing() throws Exception {
        for (long t : new long[]{0, 700, 3_000, 12_345}) {
            assertArrayEquals("t=" + t, new int[LEDS], frame(stepGauge(0.0), t));
        }
    }

    @Test
    public void colourByLevelRunsRedToGreen() throws Exception {
        // one LED exactly, at the hue an eighth of the way from red to green
        assertEquals(Renderer.hsv(15, 1f, 1f), frame(gauge(0.125, true), SETTLED)[0]);
        // halfway is amber, and every lit LED shares it
        int[] half = frame(gauge(0.5, true), SETTLED);
        for (int i = 0; i < 4; i++) assertEquals("led " + i, 0xFFFFFF00, half[i]);
        // full is green on all eight
        int[] full = frame(gauge(1.0, true), SETTLED);
        for (int i = 0; i < LEDS; i++) assertEquals("led " + i, 0xFF00FF00, full[i]);
    }

    @Test
    public void levelsOutsideTheRangeAreClamped() throws Exception {
        assertArrayEquals(frame(gauge(1.0, false), SETTLED), frame(gauge(7.0, false), SETTLED));
        assertArrayEquals(new int[LEDS], frame(gauge(-1.0, false), SETTLED));
    }

    @Test
    public void perLedColoursFollowThePalette() throws Exception {
        JSONObject cfg = gauge(1.0, false);
        cfg.remove("color");
        JSONArray colors = new JSONArray();
        for (int i = 0; i < LEDS; i++) colors.put(0xFF000000L | (i + 1));
        cfg.put("colors", colors);

        int[] out = frame(cfg, SETTLED);
        for (int i = 0; i < LEDS; i++) assertEquals("led " + i, 0xFF000000 | (i + 1), out[i]);
    }

    @Test
    public void oddScaleDimsEverySecondLitLed() throws Exception {
        JSONObject cfg = gauge(0.73, false);
        cfg.put("oddScale", 0.25);
        int[] out = frame(cfg, SETTLED);
        int dim = Renderer.scale(WHITE, 0.25);
        assertEquals(WHITE, out[0]);
        assertEquals(dim, out[1]);
        assertEquals(WHITE, out[2]);
        assertEquals(dim, out[3]);
        assertEquals(WHITE, out[4]);
        // the boundary LED is dimmed to its share and then halved again for being second
        double share = 0.73 * LEDS - 5;
        assertEquals(
                Renderer.scale(WHITE, (Renderer.PARTIAL_FLOOR + (1 - Renderer.PARTIAL_FLOOR) * share) * 0.25),
                out[5]);
        assertEquals(0, out[6]);

        // zero switches the second LEDs off outright; unlit ones stay unlit either way
        cfg.put("oddScale", 0.0);
        int[] off = frame(cfg, SETTLED);
        assertEquals(WHITE, off[0]);
        assertEquals(0xFF000000, off[1]);
        assertEquals(0, off[7]);
    }

    @Test
    public void brightnessScalesTheWholeGauge() throws Exception {
        JSONObject cfg = gauge(1.0, false);
        cfg.put("brightness", 0.5);
        assertEquals(Renderer.scale(WHITE, 0.5), frame(cfg, SETTLED)[0]);
    }
}
