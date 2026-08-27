package com.hilight.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.Random;

import org.json.JSONObject;
import org.junit.Test;

public final class RendererTest {

    @Test
    public void randomFadeAdvancesOnlyFromTheSuppliedMonotonicTime() throws Exception {
        JSONObject config = new JSONObject()
                .put("mode", "random")
                .put("randomIntervalMs", 1_000)
                .put("randomSmooth", true)
                .put("randomPerLed", true);

        Renderer renderer = new Renderer(new Random(7));
        int[] start = renderer.frame(config, 10_000, 8);
        int[] halfway = renderer.frame(config, 10_500, 8);

        assertFalse(Arrays.equals(start, halfway));

        // A second renderer with the same seed and elapsed times produces the same frames. Epoch
        // wall time between these calls is intentionally irrelevant.
        Renderer replay = new Renderer(new Random(7));
        assertArrayEquals(start, replay.frame(config, 10_000, 8));
        assertArrayEquals(halfway, replay.frame(config, 10_500, 8));
    }
}
