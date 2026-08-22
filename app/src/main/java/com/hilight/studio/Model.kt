package com.hilight.studio

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject

/**
 * Patterns the renderer understands.
 *
 * [cycleMeaningRes] spells out what one "cycle" is for each pattern, because it means something
 * different every time. [usesSpeed] is false for the patterns whose maths ignore speedMs entirely —
 * those must not show a cycle slider that does nothing.
 *
 * The labels are resource ids rather than strings because this enum is read from the renderer's state
 * layer and from a Quick Settings tile as well as from Compose, and none of those has a Context to
 * resolve a string with at the point the enum is declared.
 */
enum class Pattern(
    val key: String,
    @StringRes val labelRes: Int,
    val usesSpeed: Boolean = true,
    @StringRes val cycleMeaningRes: Int? = null,
    /**
     * Set only where the full name does not fit a narrow control.
     *
     * The Live tab's effect tiles and the per-LED fill buttons give a pattern a third of a row, which
     * "Rainbow" survives and レインボー does not — it wraps and then clips. Read through
     * [shortLabelRes], which falls back to the full name.
     */
    @StringRes private val narrowLabelRes: Int? = null,
    /**
     * True for a pattern the renderer understands but no picker should offer, because it needs an
     * input only one trigger can supply. Read through [selectable].
     */
    val internal: Boolean = false,
) {
    OFF("off", R.string.pattern_off, usesSpeed = false),
    SOLID("solid", R.string.pattern_solid, usesSpeed = false),
    GRADIENT("gradient", R.string.pattern_gradient, usesSpeed = false),
    BREATHE("breathe", R.string.pattern_breathe, cycleMeaningRes = R.string.cycle_breathe),
    BLINK("blink", R.string.pattern_blink, cycleMeaningRes = R.string.cycle_blink),
    PULSE("pulse", R.string.pattern_pulse, cycleMeaningRes = R.string.cycle_pulse),
    CHASE("chase", R.string.pattern_chase, cycleMeaningRes = R.string.cycle_chase),
    COMET("comet", R.string.pattern_comet, cycleMeaningRes = R.string.cycle_comet),
    WAVE("wave", R.string.pattern_wave, cycleMeaningRes = R.string.cycle_wave),
    RAINBOW(
        "rainbow", R.string.pattern_rainbow, cycleMeaningRes = R.string.cycle_rainbow,
        narrowLabelRes = R.string.pattern_rainbow_short,
    ),
    RANDOM("random", R.string.pattern_random, usesSpeed = false),
    CUSTOM("custom", R.string.pattern_custom, usesSpeed = false),

    /**
     * The charging gauge. It draws a battery level the renderer is handed with the alert, so it is
     * meaningless as an always-on look or a per-app effect and stays out of those pickers.
     */
    BATTERY("battery", R.string.pattern_battery, internal = true);

    /** The name to show where a third of a row is all there is. */
    @get:StringRes
    val shortLabelRes: Int get() = narrowLabelRes ?: labelRes

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: SOLID

        /** Every pattern a user may pick as a look or a rule effect. */
        val selectable: List<Pattern> get() = entries.filter { !it.internal }
    }
}

enum class Trigger { NOTIFICATION, FOREGROUND }

enum class AlertSource(val key: String) {
    NOTIFICATION("notification"), PREVIEW("preview"), FOREGROUND("foreground"),
    /** the charging gauge; survives the screen coming on, unlike a notification flash */
    CHARGING("charging"),
}

/** A continuous Android privacy operation observed by the privileged renderer. */
enum class PrivacyActivity(val key: String, val appOp: String) {
    MICROPHONE("microphone", "android:record_audio"),
    CAMERA("camera", "android:camera");

    companion object {
        fun of(key: String): PrivacyActivity? = entries.firstOrNull { it.key == key }
    }
}

/** The always-on look: what HiLight shows when nothing else is happening. */
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
    /**
     * Charging-gauge inputs, only read for [Pattern.BATTERY] previews. Neither is persisted: the
     * always-on look never uses the gauge, and a real alert carries the level of the moment it fires.
     */
    val level: Float = 1f,
    val colorByLevel: Boolean = false,
    /** gauge only: brightness factor for every second LED, 1 for none; see ChargingRule.alternate */
    val oddLedScale: Float = 1f,
    /** gauge only: light the LEDs one after another instead of together; see ChargingFill */
    val fillStepwise: Boolean = false,
    /** gauge only: blink the last lit LED a few times once the level is reached */
    val blinkTip: Boolean = false,
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
            Pattern.BATTERY -> {
                put("colors", JSONArray().also { a -> perLed.forEach { a.put(it.toUInt().toLong()) } })
                put("level", level.toDouble())
                put("byLevel", colorByLevel)
                put("oddScale", oddLedScale.toDouble())
                put("fill", if (fillStepwise) ChargingFill.STEP.key else ChargingFill.ALL.key)
                put("blinkTip", blinkTip)
            }
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

    /** Local persistence form (keeps UI-only fields the helper does not need). */
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

/** One "show X for app Y" rule. */
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
    /** only fire when the title or text contains this, case-insensitive; empty means anything */
    val keyword: String = "",
    /**
     * Per-conversation rules — "green when Sujay messages on WhatsApp".
     *
     * [conversationKey] is the notification's `shortcutId`, the stable per-chat id. It is filled in
     * the first time a matching notification is seen, even for a rule created from the contact
     * picker, after which renaming the contact can no longer break the rule. [conversationName] is
     * the fallback for apps that set no shortcutId, and what the card shows.
     */
    val conversationKey: String? = null,
    val conversationName: String? = null,
    /** also fire when this person speaks inside a group, not only in their own chat */
    val includeGroups: Boolean = false,
    /**
     * Whether the chat this rule was made from is itself a group.
     *
     * Carried on the rule rather than looked up, because the learned-chat list is capped and a rule
     * made from the contact picker was never in it at all — so the card had no way to tell a group
     * from a person, and the editor could not explain why the "also in groups" switch is irrelevant
     * for a rule that already names a group.
     */
    val conversationIsGroup: Boolean = false,
) {
    /** The catch-all rule, which matches any app without one of its own. */
    val isCatchAll: Boolean get() = pkg == ANY_APP

    /** True for a rule scoped to one chat rather than to a whole app. */
    val isConversationRule: Boolean
        get() = !conversationKey.isNullOrBlank() || !conversationName.isNullOrBlank()

    /**
     * Identity for storage.
     *
     * Package plus trigger used to be enough, but an app can now hold several rules — one per
     * conversation, plus a plain one for everything else — so the conversation has to be part of it.
     */
    val id: String get() = "$pkg|${trigger.name}|${conversationKey ?: conversationName ?: ""}"

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
        conversationKey?.let { put("conversationKey", it) }
        conversationName?.let { put("conversationName", it) }
        put("includeGroups", includeGroups)
        put("conversationIsGroup", conversationIsGroup)
    }

    companion object {
        /** Package sentinel for the catch-all rule. */
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
            conversationKey = o.optString("conversationKey", "").takeIf { it.isNotEmpty() },
            conversationName = o.optString("conversationName", "").takeIf { it.isNotEmpty() },
            includeGroups = o.optBoolean("includeGroups", false),
            conversationIsGroup = o.optBoolean("conversationIsGroup", false),
        )
    }
}

/** One microphone/camera activity signal, deliberately separate from notification/app-open rules. */
data class PrivacyRule(
    val activity: PrivacyActivity,
    val pkg: String = AppRule.ANY_APP,
    val appLabel: String = "",
    val enabled: Boolean = true,
    val pattern: Pattern = Pattern.BLINK,
    val color: Int,
    val secondColor: Int = 0xFF00E5FF.toInt(),
    val lightMs: Int = DEFAULT_LIGHT_MS,
    val cooldownMs: Int = DEFAULT_COOLDOWN_MS,
    val speedMs: Int = 800,
    val brightness: Float = 1f,
) {
    val id: String get() = "${activity.key}|$pkg"
    val isCatchAll: Boolean get() = pkg == AppRule.ANY_APP

    fun toPrefsJson(): JSONObject = JSONObject().apply {
        put("activity", activity.key)
        put("pkg", pkg)
        put("appLabel", appLabel)
        put("enabled", enabled)
        put("pattern", pattern.key)
        put("color", color.toUInt().toLong())
        put("secondColor", secondColor.toUInt().toLong())
        put("lightMs", lightMs)
        put("cooldownMs", cooldownMs)
        put("speedMs", speedMs)
        put("brightness", brightness.toDouble())
    }

    /** The renderer does not need the translated app label. */
    fun toRendererJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("activity", activity.key)
        put("pkg", pkg)
        put("pattern", pattern.key)
        if (pattern == Pattern.GRADIENT) {
            put(
                "colors",
                JSONArray()
                    .put(color.toUInt().toLong())
                    .put(secondColor.toUInt().toLong()),
            )
        } else {
            put("color", color.toUInt().toLong())
        }
        put("lightMs", lightMs.coerceIn(MIN_PHASE_MS, MAX_PHASE_MS))
        put("cooldownMs", cooldownMs.coerceIn(MIN_PHASE_MS, MAX_PHASE_MS))
        put("speedMs", speedMs.coerceIn(100, 10_000))
        put("brightness", brightness.coerceIn(0.05f, 1f).toDouble())
    }

    companion object {
        const val DEFAULT_LIGHT_MS = 10_000
        const val DEFAULT_COOLDOWN_MS = 10_000
        const val MIN_PHASE_MS = 1_000
        const val MAX_PHASE_MS = 60_000

        /** Every built-in one-rule look; Off and per-LED Custom are not trigger effects. */
        val selectablePatterns: List<Pattern>
            get() = Pattern.selectable.filter { it != Pattern.OFF && it != Pattern.CUSTOM }

        fun default(
            activity: PrivacyActivity,
            pkg: String = AppRule.ANY_APP,
            appLabel: String = "",
        ): PrivacyRule = PrivacyRule(
            activity = activity,
            pkg = pkg,
            appLabel = appLabel,
            color = when (activity) {
                PrivacyActivity.MICROPHONE -> 0xFFFF1744.toInt()
                PrivacyActivity.CAMERA -> 0xFF00E676.toInt()
            },
        )

        /** Unknown future activities are ignored so an older app can still load the remaining list. */
        fun fromJson(o: JSONObject): PrivacyRule? {
            val activity = PrivacyActivity.of(o.optString("activity")) ?: return null
            val defaults = default(activity)
            return PrivacyRule(
                activity = activity,
                pkg = o.optString("pkg", AppRule.ANY_APP),
                appLabel = o.optString("appLabel", ""),
                enabled = o.optBoolean("enabled", true),
                pattern = Pattern.of(o.optString("pattern", defaults.pattern.key)),
                color = o.optLong("color", defaults.color.toUInt().toLong()).toInt(),
                secondColor = o.optLong(
                    "secondColor",
                    defaults.secondColor.toUInt().toLong(),
                ).toInt(),
                lightMs = o.optInt("lightMs", DEFAULT_LIGHT_MS)
                    .coerceIn(MIN_PHASE_MS, MAX_PHASE_MS),
                cooldownMs = o.optInt("cooldownMs", DEFAULT_COOLDOWN_MS)
                    .coerceIn(MIN_PHASE_MS, MAX_PHASE_MS),
                speedMs = o.optInt("speedMs", defaults.speedMs).coerceIn(100, 10_000),
                brightness = o.optDouble("brightness", defaults.brightness.toDouble()).toFloat()
                    .coerceIn(0.05f, 1f),
            )
        }
    }
}

/** How the charging gauge colours the LEDs it lights. */
enum class ChargingColorMode(val key: String, @StringRes val labelRes: Int) {
    /** Every lit LED takes the colour of the level itself: red at empty, amber halfway, green at full. */
    LEVEL("level", R.string.charging_colour_by_level),
    SINGLE("single", R.string.charging_colour_single),
    PER_LED("perLed", R.string.charging_colour_per_led);

    companion object {
        fun of(key: String): ChargingColorMode = entries.firstOrNull { it.key == key } ?: LEVEL
    }
}

/** How the charging gauge's lit LEDs appear. */
enum class ChargingFill(val key: String, @StringRes val labelRes: Int) {
    /** Every lit LED comes on together, and the gauge then holds still. */
    ALL("all", R.string.charging_fill_all),
    /** LEDs 1 to 8 come on in turn up to the level, hold, then count again — so they can be counted. */
    STEP("step", R.string.charging_fill_step);

    companion object {
        fun of(key: String): ChargingFill = entries.firstOrNull { it.key == key } ?: STEP
    }
}

/**
 * Ready-made looks for the charging gauge.
 *
 * A preset is applied by copying its colours into the rule, so the rule stores colours rather than a
 * preset name: a preset the user has tweaked by hand is simply a custom look, and [matching] finds the
 * chip to highlight by comparing colours, not by remembering what was tapped.
 */
enum class ChargingPreset(
    @StringRes val labelRes: Int,
    val colorMode: ChargingColorMode,
    val color: Int = 0xFF00E676.toInt(),
    private val colors: (() -> List<Int>)? = null,
) {
    /** The default: red at empty, amber halfway, green at full. */
    TRAFFIC_LIGHT(R.string.charging_preset_traffic_light, ChargingColorMode.LEVEL),
    RED_TO_GREEN(R.string.charging_preset_red_green, ChargingColorMode.PER_LED, colors = { ChargingRule.redToGreen() }),
    FIRE(R.string.charging_preset_fire, ChargingColorMode.PER_LED, colors = { sweep(0f, 50f) }),
    SUNSET(R.string.charging_preset_sunset, ChargingColorMode.PER_LED, colors = { sweep(300f, 400f) }),
    OCEAN(R.string.charging_preset_ocean, ChargingColorMode.PER_LED, colors = { sweep(230f, 170f) }),
    AURORA(R.string.charging_preset_aurora, ChargingColorMode.PER_LED, colors = { sweep(120f, 240f) }),
    RAINBOW(
        R.string.pattern_rainbow_short, ChargingColorMode.PER_LED,
        colors = { List(LED_COUNT) { i -> Renderer.hsv(i * 360f / LED_COUNT) } },
    ),
    FOUR_COLOURS(
        R.string.charging_preset_four_colours, ChargingColorMode.PER_LED,
        colors = { List(LED_COUNT) { i -> QUARTET[i % QUARTET.size] } },
    ),
    ICE(R.string.charging_preset_ice, ChargingColorMode.SINGLE, color = 0xFF00E5FF.toInt()),
    MINT(R.string.charging_preset_mint, ChargingColorMode.SINGLE, color = 0xFF00E676.toInt()),
    WHITE(R.string.charging_preset_white, ChargingColorMode.SINGLE, color = 0xFFFFFFFF.toInt());

    /** The eight colours this preset paints, one per LED. */
    val perLed: List<Int> get() = colors?.invoke() ?: List(LED_COUNT) { color }

    /** [rule] with this preset's colours; fields the preset does not speak for are kept. */
    fun applyTo(rule: ChargingRule): ChargingRule = rule.copy(
        colorMode = colorMode,
        color = if (colorMode == ChargingColorMode.SINGLE) color else rule.color,
        perLed = if (colorMode == ChargingColorMode.PER_LED) perLed else rule.perLed,
    )

    /** True while [rule] still shows exactly this preset. */
    fun matches(rule: ChargingRule): Boolean = when (colorMode) {
        ChargingColorMode.LEVEL -> rule.colorMode == ChargingColorMode.LEVEL
        ChargingColorMode.SINGLE -> rule.colorMode == ChargingColorMode.SINGLE && rule.color == color
        ChargingColorMode.PER_LED -> rule.colorMode == ChargingColorMode.PER_LED && rule.perLed == perLed
    }

    /**
     * A miniature of the preset. Per-LED and single-colour presets show the full ring so every colour
     * is visible; the by-level preset is shown part-way up, because all-green would hide what it does.
     */
    fun previewLook(): Ambient =
        applyTo(ChargingRule(fill = ChargingFill.ALL, blinkTip = false))   // still: chips do not animate
            .previewLook(if (colorMode == ChargingColorMode.LEVEL) 0.6f else 1f)

    companion object {
        /** Blue, red, yellow, green: four colours that read as distinct at a glance. */
        private val QUARTET = listOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt())

        /** Hues from [fromHue] on the first LED to [toHue] on the last; degrees, may run past 360. */
        private fun sweep(fromHue: Float, toHue: Float): List<Int> =
            List(LED_COUNT) { i -> Renderer.hsv((fromHue + (toHue - fromHue) * i / (LED_COUNT - 1)) % 360f) }

        /** The preset [rule] currently shows, or null once it has been tweaked into a custom look. */
        fun matching(rule: ChargingRule): ChargingPreset? = entries.firstOrNull { it.matches(rule) }
    }
}

/**
 * The charging gauge: the battery level drawn across the eight LEDs when the charger goes in, and
 * again when the battery is full.
 *
 * One rule rather than a list, because there is one battery. It fires as an ordinary finite alert,
 * so the renderer's one-minute cap, the duty-cycle guard and the quiet-hours and Battery Saver rules
 * all apply to it unchanged — nothing here adds a new way to keep the array lit.
 */
data class ChargingRule(
    val enabled: Boolean = false,
    val onPlugIn: Boolean = true,
    val onFull: Boolean = true,
    val colorMode: ChargingColorMode = ChargingColorMode.LEVEL,
    val color: Int = 0xFF00E676.toInt(),
    val perLed: List<Int> = redToGreen(),
    val durationMs: Int = DEFAULT_DURATION_MS,
    /** whether the lit LEDs come on together or one after another */
    val fill: ChargingFill = ChargingFill.STEP,
    /** in one-by-one mode, how long each LED takes to come on */
    val stepMs: Int = DEFAULT_STEP_MS,
    /** blink the last lit LED a few times once the level is reached, so the eye finds it */
    val blinkTip: Boolean = true,
    val brightness: Float = 1f,
    val onlyWhenScreenOff: Boolean = false,
    /** show the gauge again this often while the charger stays connected; 0 is off */
    val repeatEveryMin: Int = 0,
    /**
     * Dim every second LED so the lit ones can be counted. The eight LEDs share one diffuser, and a
     * solid run of them blurs into a single glow; alternating bright and dim reads as separate dots.
     */
    val alternate: Boolean = true,
    /** brightness of the dimmed LEDs while [alternate] is on; 0 turns them off outright */
    val alternateLevel: Float = DEFAULT_ALTERNATE_LEVEL,
) {
    /** The factor the renderer applies to every second LED. */
    val oddLedScale: Float get() = if (alternate) alternateLevel.coerceIn(0f, MAX_ALTERNATE_LEVEL) else 1f

    /** The on-screen form of this rule at [level] (0..1), for the card, the editor and the hero. */
    fun previewLook(level: Float): Ambient = Ambient(
        pattern = Pattern.BATTERY,
        color = color,
        perLed = if (colorMode == ChargingColorMode.PER_LED) perLed else List(LED_COUNT) { color },
        brightness = brightness,
        speedMs = stepMs,
        level = level.coerceIn(0f, 1f),
        colorByLevel = colorMode == ChargingColorMode.LEVEL,
        oddLedScale = oddLedScale,
        fillStepwise = fill == ChargingFill.STEP,
        blinkTip = blinkTip,
    )

    fun toPrefsJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("onPlugIn", onPlugIn)
        put("onFull", onFull)
        put("colorMode", colorMode.key)
        put("color", color.toUInt().toLong())
        put("perLed", JSONArray().also { a -> perLed.forEach { a.put(it.toUInt().toLong()) } })
        put("durationMs", durationMs)
        put("fill", fill.key)
        put("stepMs", stepMs)
        put("blinkTip", blinkTip)
        put("brightness", brightness.toDouble())
        put("onlyWhenScreenOff", onlyWhenScreenOff)
        put("repeatEveryMin", repeatEveryMin)
        put("alternate", alternate)
        put("alternateLevel", alternateLevel.toDouble())
    }

    companion object {
        const val DEFAULT_DURATION_MS = 8_000
        const val MIN_DURATION_MS = 2_000
        const val DEFAULT_STEP_MS = 400
        const val MIN_STEP_MS = 150
        const val MAX_STEP_MS = 1_000
        const val MAX_REPEAT_MIN = 30
        const val DEFAULT_ALTERNATE_LEVEL = 0.25f
        /** Above this the dimmed LEDs stop reading as dimmed, so the slider ends here. */
        const val MAX_ALTERNATE_LEVEL = 0.6f

        /** Red on the first LED through amber to green on the last: the ring as a scale. */
        fun redToGreen(): List<Int> = List(LED_COUNT) { i -> Renderer.hsv(i * 120f / (LED_COUNT - 1)) }

        /** Clamps as it loads, so an edited preferences file cannot smuggle in a longer showing. */
        fun fromJson(o: JSONObject): ChargingRule {
            val defaults = ChargingRule()
            return ChargingRule(
                enabled = o.optBoolean("enabled", defaults.enabled),
                onPlugIn = o.optBoolean("onPlugIn", defaults.onPlugIn),
                onFull = o.optBoolean("onFull", defaults.onFull),
                colorMode = ChargingColorMode.of(o.optString("colorMode", defaults.colorMode.key)),
                color = o.optLong("color", defaults.color.toUInt().toLong()).toInt(),
                perLed = o.optJSONArray("perLed")?.let { a ->
                    (0 until a.length()).map { a.optLong(it).toInt() }
                }?.takeIf { it.size == LED_COUNT } ?: defaults.perLed,
                durationMs = o.optInt("durationMs", DEFAULT_DURATION_MS)
                    .coerceIn(MIN_DURATION_MS, Limits.RULE_MAX_MS),
                fill = ChargingFill.of(o.optString("fill", defaults.fill.key)),
                stepMs = o.optInt("stepMs", DEFAULT_STEP_MS).coerceIn(MIN_STEP_MS, MAX_STEP_MS),
                blinkTip = o.optBoolean("blinkTip", defaults.blinkTip),
                brightness = o.optDouble("brightness", 1.0).toFloat().coerceIn(0.05f, 1f),
                onlyWhenScreenOff = o.optBoolean("onlyWhenScreenOff", false),
                repeatEveryMin = o.optInt("repeatEveryMin", 0).coerceIn(0, MAX_REPEAT_MIN),
                alternate = o.optBoolean("alternate", defaults.alternate),
                alternateLevel = o.optDouble("alternateLevel", DEFAULT_ALTERNATE_LEVEL.toDouble())
                    .toFloat().coerceIn(0f, MAX_ALTERNATE_LEVEL),
            )
        }
    }
}

/** A saved look. Only the ambient config is stored; rules are separate. */
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

/** Why the array is being held dark despite the master switch being on. */
enum class Suppression(@StringRes val shortRes: Int) {
    QUIET_HOURS(R.string.suppression_quiet_hours),
    LOW_BATTERY(R.string.suppression_low_battery),
    POWER_SAVER(R.string.suppression_power_saver),
    SCREEN_ON(R.string.suppression_screen_on),
}

/** Nothing may run indefinitely: these are the ceilings the UI enforces. */
object Limits {
    /** Battery level at or below which the array pauses, unless the user moves it. */
    const val BATTERY_DEFAULT_PCT = 10
    const val BATTERY_MIN_PCT = 5
    const val BATTERY_MAX_PCT = 50
    const val AMBIENT_DEFAULT_MS = 30_000
    const val AMBIENT_MAX_MS = 300_000          // 5 minutes, behind two warnings
    const val RULE_DEFAULT_MS = 10_000
    const val RULE_MAX_MS = 60_000              // 1 minute, behind two warnings
    const val WARN_ABOVE_MS = 30_000            // anything longer than this warns twice
}

/** What the helper is reporting back. */
data class HelperStatus(
    val alive: Boolean,
    val ageMs: Long = -1,
    val pid: Int = -1,
    val uid: Int = -1,
    val owner: String = "",
    val ledCount: Int = 0,
    val sessionOpen: Boolean = false,
    val mode: String = "-",
    /** ms left on the ambient auto-off window at the moment this status was read */
    val ambientRemainingMs: Long = 0,
    /** the auto-off window has expired and the array is dark until the user acts */
    val ambientHeld: Boolean = false,
    /** the duty-cycle guard is resting the array */
    val resting: Boolean = false,
    /** how much of the duty allowance is used, 0-100 */
    val dutyPct: Int = 0,
    val appliedStateRevision: Long = 0,
    val privacyObserverEnabled: Boolean = false,
    val privacyObserverState: String = "stopped",
    val privacyPhase: String = "inactive",
)

const val LED_COUNT = 8
