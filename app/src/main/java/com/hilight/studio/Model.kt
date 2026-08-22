package com.hilight.studio

import org.json.JSONArray
import org.json.JSONObject

enum class Pattern(
    val key: String,
    val label: String,
    val usesSpeed: Boolean = true,
    val cycleMeaning: String? = null,
) {
    OFF("off", "Off", usesSpeed = false),
    SOLID("solid", "Solid", usesSpeed = false),
    GRADIENT("gradient", "Gradient", usesSpeed = false),
    BREATHE("breathe", "Breathe", cycleMeaning = "One full breath: dim up to full, back down."),
    BLINK("blink", "Blink", cycleMeaning = "One on-off pair — lit for the first half."),
    PULSE("pulse", "Pulse", cycleMeaning = "One flash: snap to full, then fade away."),
    CHASE("chase", "Chase", cycleMeaning = "One lap of a single lit LED around all eight."),
    COMET("comet", "Comet", cycleMeaning = "One lap of the comet head, its tail trailing 3 LEDs."),
    WAVE("wave", "Wave", cycleMeaning = "One wave travelling once across the array."),
    RAINBOW("rainbow", "Rainbow", cycleMeaning = "One trip through every hue, back to the start."),
    RANDOM("random", "Random colours", usesSpeed = false),
    CUSTOM("custom", "Per-LED custom", usesSpeed = false);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: SOLID
    }
}

enum class Trigger { NOTIFICATION, FOREGROUND }

data class Ambient(
    val pattern: Pattern = Pattern.OFF,
    val color: Int = 0xFF7C4DFF.toInt(),
    val secondColor: Int = 0xFF00E5FF.toInt(),
    val perLed: List<Int> = List(LED_COUNT) { 0xFF7C4DFF.toInt() },
    val brightness: Float = 0.7f,
    val speedMs: Int = 2500,
    val rainbowSpread: Boolean = true,
    val randomIntervalMs: Int = 1500,
    val randomPerLed: Boolean = true,
    val randomSmooth: Boolean = true,
    val randomSaturation: Float = 1f,
    val rotateMs: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", pattern.key)
        put("brightness", brightness.toDouble())
        put("speedMs", speedMs)
        put("spread", rainbowSpread)
        put("randomIntervalMs", randomIntervalMs)
        put("randomPerLed", randomPerLed)
        put("randomSmooth", randomSmooth)
        put("randomSaturation", randomSaturation.toDouble())
        put("rotateMs", rotateMs)
        when (pattern) {
            Pattern.CUSTOM -> put("colors", JSONArray().also { a -> perLed.forEach { a.put(it.toUInt().toLong()) } })
            Pattern.GRADIENT -> put(
                "colors",
                JSONArray().put(color.toUInt().toLong()).put(secondColor.toUInt().toLong())
            )
            else -> put("color", color.toUInt().toLong())
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = Ambient(
            pattern = Pattern.of(o.optString("pattern", "off")),
            color = o.optLong("color", 0xFF7C4DFFL).toInt(),
            secondColor = o.optLong("secondColor", 0xFF00E5FFL).toInt(),
            perLed = o.optJSONArray("perLed")?.let { a ->
                (0 until a.length()).map { a.optLong(it).toInt() }
            }?.takeIf { it.size == LED_COUNT } ?: List(LED_COUNT) { 0xFF7C4DFF.toInt() },
            brightness = o.optDouble("brightness", 0.7).toFloat(),
            speedMs = o.optInt("speedMs", 2500),
            rainbowSpread = o.optBoolean("rainbowSpread", true),
            randomIntervalMs = o.optInt("randomIntervalMs", 1500),
            randomPerLed = o.optBoolean("randomPerLed", true),
            randomSmooth = o.optBoolean("randomSmooth", true),
            randomSaturation = o.optDouble("randomSaturation", 1.0).toFloat(),
            rotateMs = o.optInt("rotateMs", 0),
        )
    }

    fun toPrefsJson(): JSONObject = JSONObject().apply {
        put("pattern", pattern.key)
        put("color", color.toUInt().toLong())
        put("secondColor", secondColor.toUInt().toLong())
        put("perLed", JSONArray().also { a -> perLed.forEach { a.put(it.toUInt().toLong()) } })
        put("brightness", brightness.toDouble())
        put("speedMs", speedMs)
        put("rainbowSpread", rainbowSpread)
        put("randomIntervalMs", randomIntervalMs)
        put("randomPerLed", randomPerLed)
        put("randomSmooth", randomSmooth)
        put("randomSaturation", randomSaturation.toDouble())
        put("rotateMs", rotateMs)
    }
}

data class AppRule(
    val pkg: String,
    val label: String,
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger.NOTIFICATION,
    val pattern: Pattern = Pattern.PULSE,
    val randomColor: Boolean = false,
    val color: Int = 0xFF00E676.toInt(),
    val durationMs: Int = 10_000,
    val speedMs: Int = 800,
    val brightness: Float = 1f,
    val onlyWhenScreenOff: Boolean = false,

    val keyword: String = "",
) {
    val isCatchAll: Boolean get() = pkg == ANY_APP

    fun toPrefsJson(): JSONObject = JSONObject().apply {
        put("pkg", pkg)
        put("label", label)
        put("enabled", enabled)
        put("trigger", trigger.name)
        put("pattern", pattern.key)
        put("randomColor", randomColor)
        put("color", color.toUInt().toLong())
        put("durationMs", durationMs)
        put("speedMs", speedMs)
        put("brightness", brightness.toDouble())
        put("onlyWhenScreenOff", onlyWhenScreenOff)
        put("keyword", keyword)
    }

    companion object {
        const val ANY_APP = "*"

        fun fromJson(o: JSONObject) = AppRule(
            pkg = o.getString("pkg"),
            label = o.optString("label", o.getString("pkg")),
            enabled = o.optBoolean("enabled", true),
            trigger = runCatching { Trigger.valueOf(o.optString("trigger", "NOTIFICATION")) }
                .getOrDefault(Trigger.NOTIFICATION),
            pattern = Pattern.of(o.optString("pattern", "pulse")),
            randomColor = o.optBoolean("randomColor", false),
            color = o.optLong("color", 0xFF00E676L).toInt(),
            durationMs = o.optInt("durationMs", 10_000),
            speedMs = o.optInt("speedMs", 800),
            brightness = o.optDouble("brightness", 1.0).toFloat(),
            onlyWhenScreenOff = o.optBoolean("onlyWhenScreenOff", false),
            keyword = o.optString("keyword", ""),
        )
    }
}

data class Preset(val name: String, val ambient: Ambient) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("ambient", ambient.toPrefsJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            name = o.optString("name", "Preset"),
            ambient = Ambient.fromJson(o.getJSONObject("ambient")),
        )
    }
}

enum class Suppression(val short: String) {
    QUIET_HOURS("Quiet hours"),
    LOW_BATTERY("Low battery"),
    POWER_SAVER("Battery Saver"),
    SCREEN_ON("Screen-off only"),
}

object Limits {
    const val BATTERY_DEFAULT_PCT = 10
    const val BATTERY_MIN_PCT = 5
    const val BATTERY_MAX_PCT = 50
    const val AMBIENT_DEFAULT_MS = 30_000
    const val AMBIENT_MAX_MS = 300_000
    const val RULE_DEFAULT_MS = 10_000
    const val RULE_MAX_MS = 60_000
    const val WARN_ABOVE_MS = 30_000
}

data class HelperStatus(
    val alive: Boolean,
    val ageMs: Long = -1,
    val pid: Int = -1,
    val ledCount: Int = 0,
    val sessionOpen: Boolean = false,
    val mode: String = "-",

    val ambientRemainingMs: Long = 0,

    val ambientHeld: Boolean = false,

    val resting: Boolean = false,

    val dutyPct: Int = 0,
)

const val LED_COUNT = 8
