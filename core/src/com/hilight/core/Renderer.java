package com.hilight.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

/**
 * Turns a pattern config into one frame of LED colours.
 *
 * Config keys: "mode" (ambient) or "pattern" (alert), "color" or "colors", "brightness", "speedMs",
 * "spread", "rotateMs", and the random-mode keys "randomIntervalMs" / "randomPerLed" /
 * "randomSmooth" / "randomSaturation".
 *
 * The app mirrors this maths in Kotlin for its on-screen preview; keep the two in step.
 */
public final class Renderer {

    private final Random rnd;

    // random-mode fade state
    private int[] randFrom, randTo;
    private long randStart, randDuration = 1500;

    public Renderer() {
        this(new Random());
    }

    /** Deterministic host-test constructor. */
    Renderer(Random rnd) {
        this.rnd = rnd;
    }

    /** Discards animation state so the next frame starts a pattern cleanly. */
    public void reset() {
        randFrom = null;
        randTo = null;
    }

    /** Renders a frame at a caller-supplied monotonic animation time. */
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
                if (randFrom == null || randFrom.length != n || t - randStart >= randDuration) {
                    randFrom = (randTo != null && randTo.length == n) ? randTo : randomColors(n, perLed, cfg);
                    randTo = randomColors(n, perLed, cfg);
                    randStart = t;
                    randDuration = interval;
                }
                double k = smooth ? clamp01((t - randStart) / (double) randDuration) : 0;
                for (int i = 0; i < n; i++) out[i] = mix(randFrom[i], randTo[i], k);
                break;
            }

            case "custom": {
                long rotateMs = cfg.optLong("rotateMs", 0);
                int shift = rotateMs > 50 ? (int) ((t / rotateMs) % n) : 0;
                for (int i = 0; i < n; i++) out[i] = palette[((i + shift) % n) % palette.length];
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
