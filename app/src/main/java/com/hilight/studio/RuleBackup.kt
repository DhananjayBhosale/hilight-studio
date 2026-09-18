package com.hilight.studio

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Portable rules only. Callers must warn that package names and matching text can be private. */
internal object RuleBackup {
    const val MAX_BYTES = 1_048_576
    const val MAX_RULES = 500
    private const val VERSION = 1

    data class MergeResult(val rules: List<AppRule>, val added: Int, val skipped: Int)

    fun encode(rules: List<AppRule>): String {
        val text = JSONObject().put("format", "hilight-rules").put("version", VERSION)
            .put("rules", JSONArray().also { array -> rules.forEach { array.put(it.toPrefsJson()) } })
            .toString(2)
        decode(text) // Export only files which this version can safely restore.
        return text
    }

    fun decode(text: String): List<AppRule> {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Rule backup is larger than 1 MB."
        }
        try {
            val reader = JSONTokener(text)
            val root = reader.nextValue() as? JSONObject ?: error("Expected an object")
            require(reader.nextClean() == '\u0000')
            require(root.get("format") == "hilight-rules")
            number(root, "version", VERSION.toDouble(), VERSION.toDouble())
            val array = root.getJSONArray("rules")
            require(array.length() <= MAX_RULES)
            val rules = (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                validateRule(value)
                AppRule.fromJson(value)
            }
            require(rules.map { it.id }.distinct().size == rules.size) { "Duplicate rule IDs" }
            return rules
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid or unsupported rule backup. No rules were changed.", failure)
        }
    }

    /** Atomic, non-destructive import. Different settings with the same identity need user resolution. */
    fun merge(existing: List<AppRule>, imported: List<AppRule>): MergeResult {
        require(imported.map { it.id }.distinct().size == imported.size) { "Duplicate imported rule IDs." }
        val byId = existing.associateBy { it.id }
        require(imported.none { byId[it.id]?.let { saved -> saved.copy(stableId = saved.id) != it.copy(stableId = it.id) } == true }) {
            "A saved rule has the same identity but different settings. No rules were changed."
        }
        val additions = imported.filter { it.id !in byId }
        require(existing.size + additions.size <= MAX_RULES) { "A maximum of 500 rules can be imported." }
        return MergeResult(existing + additions, additions.size, imported.size - additions.size)
    }

    private fun validateRule(o: JSONObject) {
        val pkg = string(o, "pkg", 255)
        require(pkg == AppRule.ANY_APP || isPackage(pkg))
        string(o, "label", 1024)
        string(o, "keyword", 4096)
        optionalString(o, "conversationKey", 4096)
        optionalString(o, "conversationName", 4096)
        optionalString(o, "stableId", 4608)?.let { require(it.isNotBlank()) }
        require(string(o, "trigger", 32) in Trigger.entries.map { it.name })
        pattern(o)
        booleans(o, "enabled", "randomColor", "onlyWhenScreenOff", "onlyWhenFaceDown",
            "includeGroups", "conversationIsGroup", "ignoreSilent", "repeatWhilePending")
        if (o.has("useAppColor")) booleans(o, "useAppColor")
        number(o, "color", 0.0, 4294967295.0)
        number(o, "durationMs", 250.0, Limits.RULE_MAX_MS.toDouble())
        number(o, "speedMs", 100.0, 10_000.0)
        number(o, "brightness", 0.0, 1.0, integer = false)
        number(o, "repeatIntervalMs", 5_000.0, 60_000.0)
        if (o.has("repeatPulseMs")) number(o, "repeatPulseMs", 1_000.0, 3_000.0)
        val excluded = o.getJSONArray("excludedPackages")
        require(excluded.length() <= MAX_RULES)
        repeat(excluded.length()) { i ->
            val pkgName = excluded.get(i)
            require(pkgName is String && pkgName.length <= 255 && isPackage(pkgName))
        }
        if (o.has("look")) validateLook(o.getJSONObject("look"))
    }

    private fun validateLook(o: JSONObject) {
        pattern(o)
        number(o, "color", 0.0, 4294967295.0)
        number(o, "secondColor", 0.0, 4294967295.0)
        val leds = o.getJSONArray("perLed")
        require(leds.length() == LED_COUNT)
        repeat(leds.length()) { index -> numeric(leds.get(index), 0.0, 4294967295.0, true) }
        number(o, "brightness", 0.0, 1.0, integer = false)
        number(o, "speedMs", 100.0, 10_000.0)
        number(o, "randomIntervalMs", 100.0, 10_000.0)
        number(o, "randomSaturation", 0.0, 1.0, integer = false)
        number(o, "rotateMs", 0.0, 10_000.0)
        booleans(o, "rainbowSpread", "randomPerLed", "randomSmooth")
    }

    private fun pattern(o: JSONObject) {
        require(string(o, "pattern", 32) in Pattern.entries.map { it.key })
    }

    private fun isPackage(value: String) = value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))

    private fun string(o: JSONObject, key: String, max: Int): String {
        val value = o.get(key)
        require(value is String && value.length <= max)
        return value
    }

    private fun optionalString(o: JSONObject, key: String, max: Int): String? =
        if (o.has(key)) string(o, key, max) else null

    private fun booleans(o: JSONObject, vararg keys: String) {
        keys.forEach { require(o.get(it) is Boolean) }
    }

    private fun number(o: JSONObject, key: String, min: Double, max: Double, integer: Boolean = true) =
        numeric(o.get(key), min, max, integer)

    private fun numeric(value: Any, min: Double, max: Double, integer: Boolean) {
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite() && number in min..max && (!integer || number % 1.0 == 0.0))
    }
}
