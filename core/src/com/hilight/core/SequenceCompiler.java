package com.hilight.core;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compiles animated renderer patterns into bounded, deterministic hardware tracks. */
public final class SequenceCompiler {

    private static final int MAX_POINTS_PER_LIGHT = 9;
    private static final int MAX_TOTAL_POINTS = 72;
    private static final int MAX_SEGMENTS = MAX_POINTS_PER_LIGHT - 1;

    private SequenceCompiler() {
        // Utility class.
    }

    /** The interpolation a hardware consumer should use between track points. */
    public enum Interpolation {
        LINEAR,
        NONE
    }

    /** A color and the incremental delay at which it is emitted. */
    public static final class Point {
        public final long delayMs;
        public final int color;

        public Point(long delayMs, int color) {
            this.delayMs = delayMs;
            this.color = color;
        }

        public long getDelayMs() {
            return delayMs;
        }

        public int getColor() {
            return color;
        }
    }

    /** One light's ordered, incrementally timed points. */
    public static final class Track {
        public final List<Point> points;
        public final List<Long> delaysMs;
        public final List<Integer> colors;

        private Track(List<Point> points) {
            this.points = immutable(points);
            List<Long> delays = new ArrayList<>(points.size());
            List<Integer> values = new ArrayList<>(points.size());
            for (Point point : points) {
                delays.add(point.delayMs);
                values.add(point.color);
            }
            this.delaysMs = Collections.unmodifiableList(delays);
            this.colors = Collections.unmodifiableList(values);
        }

        public List<Point> getPoints() {
            return points;
        }

        public List<Long> getDelaysMs() {
            return delaysMs;
        }

        public List<Integer> getColors() {
            return colors;
        }

        public long totalDurationMs() {
            long total = 0;
            for (long delay : delaysMs) {
                if (delay > Long.MAX_VALUE - total) return Long.MAX_VALUE;
                total += delay;
            }
            return total;
        }
    }

    /** Immutable compiler result. */
    public static final class Plan {
        public final long periodMs;
        public final Interpolation interpolation;
        public final List<Track> tracks;
        public final boolean mayLight;

        private Plan(long periodMs, Interpolation interpolation, List<Track> tracks,
                     boolean mayLight) {
            this.periodMs = periodMs;
            this.interpolation = interpolation;
            this.tracks = immutable(tracks);
            this.mayLight = mayLight;
        }

        public long getPeriodMs() {
            return periodMs;
        }

        public Interpolation getInterpolation() {
            return interpolation;
        }

        public List<Track> getTracks() {
            return tracks;
        }

        public boolean mayLight() {
            return mayLight;
        }
    }

    /**
     * Builds the preemptive all-black effect used to terminate a colored hardware submission.
     *
     * This deliberately is not a renderer/schema plan: every light has the same opaque-black
     * two-point LINEAR track, and the plan is explicitly marked as unable to light the array.
     */
    static Plan blackTerminator(int ledCount, long durationMs) {
        if (ledCount <= 0 || ledCount > MAX_TOTAL_POINTS / 2 || durationMs <= 0) return null;

        List<Track> tracks = new ArrayList<>(ledCount);
        for (int light = 0; light < ledCount; light++) {
            List<Point> points = new ArrayList<>(2);
            points.add(new Point(0, 0xFF000000));
            points.add(new Point(durationMs, 0xFF000000));
            tracks.add(new Track(points));
        }
        Plan plan = new Plan(durationMs, Interpolation.LINEAR, tracks, false);
        // A one-millisecond quantum is the compiler's minimum invariant; the backend applies the
        // device's actual quantum (33 ms on the certified profile) before submission.
        return validate(plan, ledCount, 1L) ? plan : null;
    }

    /** Preserves the original caller API; the default random activation is deterministic. */
    public static Plan compile(JSONObject cfg, long phaseOriginMs, int ledCount,
                               long quantumMs, double externalScale) {
        return compile(cfg, phaseOriginMs, ledCount, quantumMs, externalScale, 0L);
    }

    /** Compiles with a caller-supplied activation seed for deterministic random patterns. */
    public static Plan compile(JSONObject cfg, long phaseOriginMs, int ledCount,
                               long quantumMs, double externalScale, long randomSeed) {
        if (cfg == null || ledCount <= 0 || ledCount > MAX_TOTAL_POINTS / 2
                || quantumMs <= 0 || !Double.isFinite(externalScale)
                || externalScale < 0.0 || externalScale > 1.0) {
            return null;
        }

        try {
            String mode = cfg.optString("mode", cfg.optString("pattern", "off"));
            if (isContinuous(mode)) {
                long period = sourcePeriod(cfg);
                if (period <= 0) return null;
                long available = period / quantumMs;
                int segments = (int) Math.min(MAX_SEGMENTS, available);
                if (segments < 1) return null;
                int[][] sampled = sampleRenderer(cfg, phaseOriginMs, period, segments,
                        externalScale, ledCount);
                return finish(period, Interpolation.LINEAR,
                        tracks(sampled, boundaries(period, segments)), ledCount, quantumMs);
            }

            if ("blink".equals(mode)) {
                long period = sourcePeriod(cfg);
                if (period <= 0) return null;
                long first = period / 2;
                long second = period - first;
                if (first < quantumMs || second < quantumMs) return null;
                int[][] sampled = sampleRenderer(cfg, phaseOriginMs, period, 2,
                        externalScale, ledCount);
                return finish(period, Interpolation.NONE,
                        tracks(sampled, new long[]{0, first, period}),
                        ledCount, quantumMs);
            }

            if ("chase".equals(mode)) {
                if (ledCount > MAX_SEGMENTS) return null;
                long speed = sourcePeriod(cfg);
                long interval = speed / ledCount;
                if (interval < 1) interval = 1;
                if (interval < quantumMs || interval > Long.MAX_VALUE / ledCount) return null;
                long period = interval * ledCount;
                int[][] sampled = sampleRenderer(cfg, phaseOriginMs, period, ledCount,
                        externalScale, ledCount);
                return finish(period, Interpolation.NONE,
                        tracks(sampled, intervalBoundaries(interval, ledCount)),
                        ledCount, quantumMs);
            }

            if ("custom".equals(mode)) {
                long rotateMs = cfg.optLong("rotateMs", 0);
                if (rotateMs <= 50 || ledCount > MAX_SEGMENTS
                        || rotateMs > Long.MAX_VALUE / ledCount
                        || rotateMs < quantumMs) return null;
                long period = rotateMs * ledCount;
                int[][] sampled = sampleRenderer(cfg, phaseOriginMs, period, ledCount,
                        externalScale, ledCount);
                return finish(period, Interpolation.NONE,
                        tracks(sampled, intervalBoundaries(rotateMs, ledCount)),
                        ledCount, quantumMs);
            }

            if ("random".equals(mode)) {
                long interval = cfg.optLong("randomIntervalMs", 1500);
                if (interval < 120) interval = 120;
                if (interval < quantumMs || interval > Long.MAX_VALUE / MAX_SEGMENTS) {
                    return null;
                }
                long period = interval * MAX_SEGMENTS;
                boolean smooth = cfg.optBoolean("randomSmooth", true);
                int[][] sampled = randomSamples(cfg, phaseOriginMs, interval, ledCount,
                        externalScale, randomSeed, smooth);
                Interpolation interpolation = smooth
                        ? Interpolation.LINEAR : Interpolation.NONE;
                return finish(period, interpolation,
                        tracks(sampled, intervalBoundaries(interval, MAX_SEGMENTS)),
                        ledCount, quantumMs);
            }

            // Static and unknown modes are handled by the ordinary output path.
            return null;
        } catch (RuntimeException malformedConfig) {
            // JSONObject values can be malformed at runtime; reject them safely.
            return null;
        }
    }

    private static boolean isContinuous(String mode) {
        return "breathe".equals(mode) || "pulse".equals(mode) || "wave".equals(mode)
                || "rainbow".equals(mode) || "comet".equals(mode);
    }

    private static long sourcePeriod(JSONObject cfg) {
        return Math.max(60, cfg.optLong("speedMs", 2000));
    }

    private static int[][] sampleRenderer(JSONObject cfg, long phaseOriginMs, long period,
                                          int segments, double externalScale, int ledCount) {
        long[] elapsed = boundaries(period, segments);
        long base = Math.floorMod(phaseOriginMs, period);
        int[][] sampled = new int[segments + 1][ledCount];
        Renderer renderer = new Renderer();
        for (int sample = 0; sample <= segments; sample++) {
            long time = addModulo(base, elapsed[sample], period);
            int[] frame = renderer.frame(cfg, time, ledCount);
            for (int light = 0; light < ledCount; light++) {
                sampled[sample][light] = Renderer.scale(frame[light], externalScale);
            }
        }
        return sampled;
    }

    /** Samples the seeded eight-stage cycle at the requested phase without expanding its shape. */
    private static int[][] randomSamples(JSONObject cfg, long phaseOriginMs, long interval,
                                         int ledCount, double externalScale, long seed,
                                         boolean smooth) {
        boolean perLed = cfg.optBoolean("randomPerLed", true);
        float saturation = (float) Renderer.clamp01(
                cfg.optDouble("randomSaturation", 1.0));
        double brightness = Renderer.clamp01(cfg.optDouble("brightness", 1.0));
        int[][] stages = new int[MAX_SEGMENTS][ledCount];
        for (int stage = 0; stage < MAX_SEGMENTS; stage++) {
            for (int light = 0; light < ledCount; light++) {
                long key = seed ^ ((long) stage * 0x9E3779B97F4A7C15L);
                if (perLed) key ^= (long) light * 0xBF58476D1CE4E5B9L;
                int hue = (int) Math.floorMod(mix64(key), 360);
                int color = Renderer.hsv(hue, saturation, 1f);
                // Scale each deterministic stage once, before any phase interpolation.
                stages[stage][light] = Renderer.scale(
                        Renderer.scale(color, brightness), externalScale);
            }
        }

        long period = interval * MAX_SEGMENTS;
        long phase = Math.floorMod(phaseOriginMs, period);
        int stage = (int) (phase / interval);
        long remainder = phase % interval;
        double fraction = remainder / (double) interval;
        int[][] sampled = new int[MAX_SEGMENTS + 1][ledCount];
        for (int sample = 0; sample < MAX_SEGMENTS; sample++) {
            int current = (stage + sample) % MAX_SEGMENTS;
            int next = (current + 1) % MAX_SEGMENTS;
            for (int light = 0; light < ledCount; light++) {
                sampled[sample][light] = smooth
                        ? Renderer.mix(stages[current][light], stages[next][light], fraction)
                        : stages[current][light];
            }
        }
        for (int light = 0; light < ledCount; light++) {
            sampled[MAX_SEGMENTS][light] = sampled[0][light];
        }
        return sampled;
    }

    private static long[] boundaries(long period, int segments) {
        long[] result = new long[segments + 1];
        for (int i = 1; i <= segments; i++) {
            result[i] = boundary(period, segments, i);
        }
        return result;
    }

    private static long[] intervalBoundaries(long interval, int stages) {
        long[] result = new long[stages + 1];
        for (int i = 1; i <= stages; i++) {
            result[i] = interval * i;
        }
        return result;
    }

    private static long boundary(long period, int segments, int index) {
        long quotient = period / segments;
        long remainder = period % segments;
        return quotient * index + (remainder * index) / segments;
    }

    private static long addModulo(long base, long offset, long period) {
        if (base <= Long.MAX_VALUE - offset) return base + offset;
        return base - (period - offset);
    }

    private static List<Track> tracks(int[][] sampled, long[] elapsed) {
        List<Track> result = new ArrayList<>(sampled[0].length);
        for (int light = 0; light < sampled[0].length; light++) {
            List<Point> points = new ArrayList<>(sampled.length);
            for (int sample = 0; sample < sampled.length; sample++) {
                long delay = sample == 0 ? 0 : elapsed[sample] - elapsed[sample - 1];
                points.add(new Point(delay, sampled[sample][light]));
            }
            result.add(new Track(points));
        }
        return result;
    }

    private static Plan finish(long period, Interpolation interpolation, List<Track> tracks,
                               int ledCount, long quantumMs) {
        boolean mayLight = false;
        for (Track track : tracks) {
            for (int color : track.colors) mayLight |= rgbSum(color) > 12;
        }
        Plan plan = new Plan(period, interpolation, tracks, mayLight);
        return validate(plan, ledCount, quantumMs) ? plan : null;
    }

    private static boolean validate(Plan plan, int ledCount, long quantumMs) {
        if (plan == null || plan.periodMs <= 0 || plan.interpolation == null
                || plan.tracks == null || plan.tracks.size() != ledCount) return false;
        int total = 0;
        for (Track track : plan.tracks) {
            if (track == null || track.points == null || track.points.isEmpty()
                    || track.points.size() > MAX_POINTS_PER_LIGHT) return false;
            if (total > MAX_TOTAL_POINTS - track.points.size()) return false;
            total += track.points.size();
            if (track.points.get(0).delayMs != 0
                    || track.totalDurationMs() != plan.periodMs) return false;
            for (int i = 1; i < track.points.size(); i++) {
                long delay = track.points.get(i).delayMs;
                if (delay <= 0 || delay < quantumMs) return false;
            }
            if (track.points.get(0).color
                    != track.points.get(track.points.size() - 1).color) return false;
        }
        return total <= MAX_TOTAL_POINTS;
    }

    private static int rgbSum(int color) {
        return ((color >> 16) & 0xFF) + ((color >> 8) & 0xFF) + (color & 0xFF);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
