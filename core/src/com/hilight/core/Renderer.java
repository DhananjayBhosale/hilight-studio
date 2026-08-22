package com.hilight.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

/**
 * Turns a pattern config into one frame of LED colours.
 *
 * Config keys: "mode" (ambient) or "pattern" (alert), "color" or "colors", "brightness", "speedMs",
 * "spread", "rotateMs", the random-mode keys "randomIntervalMs" / "randomPerLed" /
 * "randomSmooth" / "randomSaturation", and the battery-gauge keys "level" / "byLevel".
 *
 * The app mirrors this maths in Kotlin for its on-screen preview; keep the two in step.
 */
public final class Renderer {

    /**
     * Dimmest the battery gauge draws a partly-filled LED. Straight proportion would make the first
     * few percent of an LED invisible, so the boundary LED reads as "just started" instead of off.
     */
    static final double PARTIAL_FLOOR = 0.1;

    /** Shortest hold at the level between two one-by-one counts of the battery gauge. */
    static final long STEP_HOLD_MIN_MS = 1200;

    /** How often, and how fast, the gauge's last lit LED blinks once the level is reached. */
    static final int TIP_BLINKS = 3;
    static final long TIP_BLINK_MS = 300;

    /**
     * Whether the tip LED is in an off beat of its blink: [sinceMs] into a blink run of [totalMs],
     * the on and off beats each last TIP_BLINK_MS, starting on, and it holds lit once the run is over.
     */
    static boolean tipBlinkOff(long sinceMs, long totalMs) {
        return sinceMs < totalMs && ((sinceMs / TIP_BLINK_MS) & 1) == 1;
    }

    private final Random rnd = new Random();

    // random-mode fade state
    private int[] randFrom, randTo;
    private long randStart, randDuration = 1500;

    /** Discards animation state so the next frame starts a pattern cleanly. */
    public void reset() {
        randFrom = null;
        randTo = null;
    }

    public int[] frame(JSONObject cfg, long t, int n) {
        int[] out = new int[n];
        if (cfg == null) return out;

        // ambient configs carry "mode", alerts carry "pattern" — accept either
        String mode = cfg.optString("mode", cfg.optString("pattern", "off"));
        double bright = clamp01(cfg.optDouble("brightness", 1.0));
        long speed = Math.max(60, cfg.optLong("speedMs", 2000));
        int[] palette = colors(cfg);

        switch (mode) {
            case "off":
                break;

            case "solid":
                for (int i = 0; i < n; i++) out[i] = palette[i % palette.length];
                break;

            case "gradient": {
                int a = palette[0];
                int b = palette.length > 1 ? palette[1] : a;
                for (int i = 0; i < n; i++) out[i] = mix(a, b, n == 1 ? 0 : (double) i / (n - 1));
                break;
            }

            case "breathe": {
                double phase = (t % speed) / (double) speed;
                double k = (1 - Math.cos(phase * 2 * Math.PI)) / 2;
                for (int i = 0; i < n; i++) out[i] = scale(palette[i % palette.length], 0.05 + 0.95 * k);
                break;
            }

            case "blink": {
                if ((t % speed) < speed / 2) {
                    for (int i = 0; i < n; i++) out[i] = palette[i % palette.length];
                }
                break;
            }

            case "pulse": {
                // sharp attack, exponential decay — reads well as a notification
                double phase = (t % speed) / (double) speed;
                double k = phase < 0.12 ? phase / 0.12 : Math.exp(-(phase - 0.12) * 5);
                for (int i = 0; i < n; i++) out[i] = scale(palette[i % palette.length], k);
                break;
            }

            case "chase": {
                int head = (int) ((t / Math.max(1, speed / n)) % n);
                for (int i = 0; i < n; i++) out[i] = i == head ? palette[0] : 0xFF000000;
                break;
            }

            case "comet": {
                double pos = (t % speed) / (double) speed * n;
                for (int i = 0; i < n; i++) {
                    double d = pos - i;
                    if (d < 0) d += n;
                    out[i] = scale(palette[i % palette.length], Math.max(0, 1 - d / 3.0));
                }
                break;
            }

            case "wave": {
                double phase = (t % speed) / (double) speed;
                for (int i = 0; i < n; i++) {
                    double k = (1 + Math.sin(2 * Math.PI * (phase + (double) i / n))) / 2;
                    out[i] = scale(palette[i % palette.length], 0.08 + 0.92 * k);
                }
                break;
            }

            case "rainbow": {
                double phase = (t % speed) / (double) speed;
                boolean spread = cfg.optBoolean("spread", true);
                for (int i = 0; i < n; i++) {
                    double h = (phase + (spread ? (double) i / n : 0)) * 360.0;
                    out[i] = hsv(h % 360, 1f, 1f);
                }
                break;
            }

            case "random": {
                long interval = Math.max(120, cfg.optLong("randomIntervalMs", 1500));
                boolean perLed = cfg.optBoolean("randomPerLed", true);
                boolean smooth = cfg.optBoolean("randomSmooth", true);
                long now = System.currentTimeMillis();
                if (randFrom == null || randFrom.length != n || now - randStart >= randDuration) {
                    randFrom = (randTo != null && randTo.length == n) ? randTo : randomColors(n, perLed, cfg);
                    randTo = randomColors(n, perLed, cfg);
                    randStart = now;
                    randDuration = interval;
                }
                double k = smooth ? clamp01((now - randStart) / (double) randDuration) : 0;
                for (int i = 0; i < n; i++) out[i] = mix(randFrom[i], randTo[i], k);
                break;
            }

            case "custom": {
                long rotateMs = cfg.optLong("rotateMs", 0);
                int shift = rotateMs > 50 ? (int) ((t / rotateMs) % n) : 0;
                for (int i = 0; i < n; i++) out[i] = palette[((i + shift) % n) % palette.length];
                break;
            }

            case "battery": {
                // A charge gauge. The LEDs fill from ordinal 0 upwards to "level" (0..1), each LED
                // standing for 1/n of the range, and the LED on the boundary is dimmed to its share.
                // With "byLevel" every lit LED takes the colour of the level itself, red at empty
                // through amber to green at full; otherwise the palette applies per LED, so a colour
                // scale can be painted by hand. "oddScale" dims every second LED (ordinals 1, 3, 5, 7)
                // by that factor: the eight LEDs sit behind one diffuser, and a solid run of lit ones
                // blurs into a single glow nobody can count, whereas alternating bright and dim reads
                // as separate dots while the run still reaches as far as the level does.
                //
                // "fill" is how the lit LEDs appear. "all": together, from the first frame, and still
                // from then on. "step": one after another, each easing in over speedMs, then a hold
                // at the level, a short dark gap, and the count starts again — so the LEDs can be
                // counted as they come on, for as long as the gauge is showing.
                //
                // "blinkTip" makes the last lit LED blink TIP_BLINKS times once the gauge has reached
                // its level — straight away for "all", after the count for "step" — and then hold,
                // so the eye is drawn to where the level actually is.
                double level = clamp01(cfg.optDouble("level", 0));
                boolean byLevel = cfg.optBoolean("byLevel", false);
                double oddScale = clamp01(cfg.optDouble("oddScale", 1.0));
                boolean blinkTip = cfg.optBoolean("blinkTip", false);
                long blinkMs = blinkTip ? 2L * TIP_BLINKS * TIP_BLINK_MS : 0;
                double target = level * n;
                int lit = (int) Math.ceil(target - 1e-9);
                double shown = target;
                boolean tipOff = false;
                if ("step".equals(cfg.optString("fill", "all"))) {
                    long count = lit * speed;
                    long hold = Math.max(STEP_HOLD_MIN_MS, 3 * speed) + blinkMs;
                    long cycle = count + hold + speed;
                    long phase = t % cycle;
                    if (phase < count) {
                        long idx = phase / speed;
                        shown = Math.min(target, idx + (phase % speed) / (double) speed);
                    } else if (phase >= count + hold) {
                        shown = 0;                      // the gap before the next count
                    } else {
                        tipOff = tipBlinkOff(phase - count, blinkMs);
                    }
                } else {
                    tipOff = tipBlinkOff(t, blinkMs);
                }
                int levelColor = hsv(level * 120.0, 1f, 1f);
                for (int i = 0; i < n; i++) {
                    double k = clamp01(shown - i);
                    if (k <= 0) continue;
                    if (tipOff && i == lit - 1) continue;
                    int c = byLevel ? levelColor : palette[i % palette.length];
                    double f = k >= 1 ? 1 : PARTIAL_FLOOR + (1 - PARTIAL_FLOOR) * k;
                    if ((i & 1) == 1) f *= oddScale;
                    out[i] = scale(c, f);
                }
                break;
            }

            default:
                for (int i = 0; i < n; i++) out[i] = palette[i % palette.length];
        }

        if (bright < 1.0) for (int i = 0; i < n; i++) out[i] = scale(out[i], bright);
        return out;
    }

    private int[] randomColors(int n, boolean perLed, JSONObject cfg) {
        float sat = (float) clamp01(cfg.optDouble("randomSaturation", 1.0));
        int[] c = new int[n];
        if (perLed) {
            for (int i = 0; i < n; i++) c[i] = hsv(rnd.nextInt(360), sat, 1f);
        } else {
            int one = hsv(rnd.nextInt(360), sat, 1f);
            for (int i = 0; i < n; i++) c[i] = one;
        }
        return c;
    }

    private static int[] colors(JSONObject cfg) {
        JSONArray a = cfg.optJSONArray("colors");
        if (a != null && a.length() > 0) {
            int[] c = new int[a.length()];
            for (int i = 0; i < a.length(); i++) c[i] = (int) (a.optLong(i, 0xFFFFFFFFL) | 0xFF000000L);
            return c;
        }
        return new int[]{(int) (cfg.optLong("color", 0xFFFFFFFFL) | 0xFF000000L)};
    }

    // ------------------------------------------------------------------------------ colour maths

    static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    static int scale(int color, double k) {
        k = clamp01(k);
        int r = (int) (((color >> 16) & 0xFF) * k);
        int g = (int) (((color >> 8) & 0xFF) * k);
        int b = (int) ((color & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int mix(int a, int b, double k) {
        k = clamp01(k);
        int r = (int) (((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = (int) (((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) ((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static int hsv(double h, float s, float v) {
        double c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        double r, g, b;
        switch ((int) (h / 60) % 6) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x;
        }
        return 0xFF000000
                | ((int) ((r + m) * 255) << 16)
                | ((int) ((g + m) * 255) << 8)
                | (int) ((b + m) * 255);
    }
}
