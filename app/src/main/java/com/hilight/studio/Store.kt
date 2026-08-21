package com.hilight.studio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for the UI and the triggers, and the only thing that pushes to a [Backend].
 *
 * Output layering, highest priority first:
 *   1. a finite notification alert
 *   2. an infinite foreground-app override
 *   3. the ambient look
 * When a notification alert finishes, any foreground override is re-pushed so it is not lost.
 */
class Store private constructor(private val app: Context) {

    private val prefs = app.getSharedPreferences("hilight", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val adb = AdbBackend(app)
    val shizuku = ShizukuBackend(app)

    private val _transport = MutableStateFlow(
        runCatching { Transport.valueOf(prefs.getString("transport", null) ?: "AUTO") }
            .getOrDefault(Transport.AUTO)
    )
    val transport: StateFlow<Transport> = _transport.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamicColor", true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _ambientTimeoutMs =
        MutableStateFlow(prefs.getInt("ambientTimeoutMs", Limits.AMBIENT_DEFAULT_MS))
    val ambientTimeoutMs: StateFlow<Int> = _ambientTimeoutMs.asStateFlow()

    private val _quietEnabled = MutableStateFlow(prefs.getBoolean("quietEnabled", false))
    val quietEnabled: StateFlow<Boolean> = _quietEnabled.asStateFlow()

    /** minutes since midnight */
    private val _quietStart = MutableStateFlow(prefs.getInt("quietStart", 23 * 60))
    val quietStart: StateFlow<Int> = _quietStart.asStateFlow()

    private val _quietEnd = MutableStateFlow(prefs.getInt("quietEnd", 7 * 60))
    val quietEnd: StateFlow<Int> = _quietEnd.asStateFlow()

    /** Dim through quiet hours instead of going fully dark. */
    private val _quietDim = MutableStateFlow(prefs.getBoolean("quietDim", false))
    val quietDim: StateFlow<Boolean> = _quietDim.asStateFlow()

    private val _quietDimPct = MutableStateFlow(prefs.getInt("quietDimPct", 12))
    val quietDimPct: StateFlow<Int> = _quietDimPct.asStateFlow()

    private val _screenOffOnly = MutableStateFlow(prefs.getBoolean("screenOffOnly", false))
    val screenOffOnly: StateFlow<Boolean> = _screenOffOnly.asStateFlow()

    private val _batteryGuard = MutableStateFlow(prefs.getBoolean("batteryGuard", true))
    val batteryGuard: StateFlow<Boolean> = _batteryGuard.asStateFlow()

    private val _batteryMinPct =
        MutableStateFlow(prefs.getInt("batteryMinPct", Limits.BATTERY_DEFAULT_PCT))
    val batteryMinPct: StateFlow<Int> = _batteryMinPct.asStateFlow()

    /** Pause whenever Android's own Battery Saver is on, whatever the level. */
    private val _saverGuard = MutableStateFlow(prefs.getBoolean("saverGuard", true))
    val saverGuard: StateFlow<Boolean> = _saverGuard.asStateFlow()

    private val _respectDnd = MutableStateFlow(prefs.getBoolean("respectDnd", true))
    val respectDnd: StateFlow<Boolean> = _respectDnd.asStateFlow()

    private val _suppression = MutableStateFlow<Suppression?>(null)
    val suppression: StateFlow<Suppression?> = _suppression.asStateFlow()

    private val _priority = MutableStateFlow(prefs.getInt("priority", 0))
    val priority: StateFlow<Int> = _priority.asStateFlow()

    private val _ambient = MutableStateFlow(loadAmbient())
    val ambient: StateFlow<Ambient> = _ambient.asStateFlow()

    private val _presets = MutableStateFlow(loadPresets())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _rules = MutableStateFlow(loadRules())
    val rules: StateFlow<List<AppRule>> = _rules.asStateFlow()

    private val _status = MutableStateFlow(HelperStatus(alive = false))
    val status: StateFlow<HelperStatus> = _status.asStateFlow()

    /** Which transport is actually carrying state right now. */
    private val _activeTransport = MutableStateFlow(Transport.ADB)
    val activeTransport: StateFlow<Transport> = _activeTransport.asStateFlow()

    /** Package of the app whose FOREGROUND rule is currently held, if any. */
    private var foregroundOverride: Pair<String, JSONObject>? = null

    /**
     * The notification alert still meant to be on the array, if any.
     *
     * Held so that [pushCurrent] can put it back into every document it sends. Without it, any
     * unrelated push during an alert — a foreground rule matching, or the user moving a slider —
     * replaced the top layer with the one below and cut the alert short. Re-sending the same alert
     * id does not restart it: the renderer only resets its clock when the id changes.
     */
    private var activeAlert: JSONObject? = null
    private var alertExpiry: Runnable? = null

    init {
        Bridge.ensureFiles(app)
        _suppression.value = suppressionNow()
        // cheap clock/battery watch: quiet hours start and battery drops must take effect on their own
        main.post(object : Runnable {
            override fun run() {
                refreshSuppression()
                main.postDelayed(this, 30_000)
            }
        })
        // Screen and power state both change far too fast for the 30s tick to be the only watcher —
        // waiting half a minute to notice a charger makes the guards look like faults.
        app.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_SCREEN_OFF -> refreshSuppression(armOnRelease = true)
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            // The screen coming on, or the phone being unlocked, means the
                            // notification has been seen — a rule's colour has no one left to tell,
                            // so drop it now instead of burning the rest of its window.
                            cancelAlert()
                            refreshSuppression()
                        }
                        // plugging in, unplugging, or toggling Battery Saver: re-check, but a power
                        // event is not the user looking at the phone, so any alert keeps running
                        else -> refreshSuppression()
                    }
                }
            },
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
        )
        // Whenever Shizuku appears or disappears, re-push: a new user service starts stateless, and
        // after a loss the ADB helper needs to be told to take over.
        shizuku.onAvailabilityChanged = {
            main.post {
                pushCurrent(arm = false)
                HiLightTile.refresh(app)
            }
        }
    }

    // ------------------------------------------------------------------ transport selection

    private fun backend(): Backend = when (_transport.value) {
        Transport.SHIZUKU -> shizuku
        Transport.ADB -> adb
        Transport.AUTO ->
            if (shizuku.state.value == ShizukuBackend.State.CONNECTED) shizuku else adb
    }

    fun setTransport(t: Transport) {
        _transport.value = t
        prefs.edit().putString("transport", t.name).apply()
        if (t == Transport.SHIZUKU || t == Transport.AUTO) shizuku.refresh()
        pushCurrent()
    }

    // ------------------------------------------------------------------ mutations

    fun setEnabled(v: Boolean) {
        _enabled.value = v
        prefs.edit().putBoolean("enabled", v).apply()
        pushCurrent()
        HiLightTile.refresh(app)
    }

    fun setDynamicColor(v: Boolean) {
        _dynamicColor.value = v
        prefs.edit().putBoolean("dynamicColor", v).apply()
    }

    fun setAmbientTimeoutMs(v: Int) {
        _ambientTimeoutMs.value = v.coerceIn(5_000, Limits.AMBIENT_MAX_MS)
        prefs.edit().putInt("ambientTimeoutMs", _ambientTimeoutMs.value).apply()
        pushCurrent()
    }

    fun setQuietHours(enabled: Boolean, startMin: Int = _quietStart.value, endMin: Int = _quietEnd.value) {
        _quietEnabled.value = enabled
        _quietStart.value = startMin
        _quietEnd.value = endMin
        prefs.edit()
            .putBoolean("quietEnabled", enabled)
            .putInt("quietStart", startMin)
            .putInt("quietEnd", endMin)
            .apply()
        pushCurrent()
    }

    fun setQuietDim(dim: Boolean, pct: Int = _quietDimPct.value) {
        _quietDim.value = dim
        _quietDimPct.value = pct.coerceIn(2, 40)
        prefs.edit()
            .putBoolean("quietDim", dim)
            .putInt("quietDimPct", _quietDimPct.value)
            .apply()
        pushCurrent()
    }

    fun setScreenOffOnly(v: Boolean) {
        _screenOffOnly.value = v
        prefs.edit().putBoolean("screenOffOnly", v).apply()
        pushCurrent()
    }

    fun setBatteryGuard(enabled: Boolean, minPct: Int = _batteryMinPct.value) {
        _batteryGuard.value = enabled
        _batteryMinPct.value = minPct.coerceIn(Limits.BATTERY_MIN_PCT, Limits.BATTERY_MAX_PCT)
        prefs.edit()
            .putBoolean("batteryGuard", enabled)
            .putInt("batteryMinPct", _batteryMinPct.value)
            .apply()
        refreshSuppression()
        pushCurrent()
    }

    fun setSaverGuard(v: Boolean) {
        _saverGuard.value = v
        prefs.edit().putBoolean("saverGuard", v).apply()
        refreshSuppression()
        pushCurrent()
    }

    fun setRespectDnd(v: Boolean) {
        _respectDnd.value = v
        prefs.edit().putBoolean("respectDnd", v).apply()
    }

    fun setPriority(v: Int) {
        _priority.value = v
        prefs.edit().putInt("priority", v).apply()
        pushCurrent()
    }

    fun setAmbient(a: Ambient) {
        _ambient.value = a
        prefs.edit().putString("ambient", a.toPrefsJson().toString()).apply()
        pushCurrent()
    }

    fun upsertRule(rule: AppRule) {
        val current = _rules.value
        val existingIndex = current.indexOfFirst { it.id == rule.id }
        val withoutOtherForeground = if (rule.trigger == Trigger.FOREGROUND) {
            current.filter { it.id == rule.id || it.pkg != rule.pkg || it.trigger != Trigger.FOREGROUND }
        } else {
            current
        }
        _rules.value = if (existingIndex >= 0) {
            withoutOtherForeground.toMutableList().also {
                it[it.indexOfFirst { saved -> saved.id == rule.id }] = rule
            }
        } else {
            withoutOtherForeground + rule
        }
        saveRules()
        ForegroundWatcher.syncRunning(app, _rules.value, _enabled.value)
    }

    fun removeRule(rule: AppRule) {
        _rules.value = _rules.value.filterNot { it.id == rule.id }
        saveRules()
        ForegroundWatcher.syncRunning(app, _rules.value, _enabled.value)
    }

    fun savePreset(name: String) {
        val clean = name.trim().ifEmpty { "Preset ${_presets.value.size + 1}" }
        _presets.value = _presets.value.filterNot { it.name == clean } + Preset(clean, _ambient.value)
        persistPresets()
    }

    fun applyPreset(preset: Preset) = setAmbient(preset.ambient)

    fun deletePreset(preset: Preset) {
        _presets.value = _presets.value.filterNot { it.name == preset.name }
        persistPresets()
    }

    /** All presets as a JSON document, for sharing or backup. */
    fun exportPresets(): String = JSONObject().apply {
        put("v", 1)
        put("presets", JSONArray().also { a -> _presets.value.forEach { a.put(it.toJson()) } })
    }.toString(2)

    /** Merges presets from an exported document. Returns how many were added, or null if unreadable. */
    fun importPresets(raw: String): Int? = runCatching {
        val arr = JSONObject(raw).getJSONArray("presets")
        val incoming = (0 until arr.length()).mapNotNull {
            runCatching { Preset.fromJson(arr.getJSONObject(it)) }.getOrNull()
        }
        val byName = _presets.value.associateBy { it.name }.toMutableMap()
        incoming.forEach { byName[it.name] = it }
        _presets.value = byName.values.sortedBy { it.name.lowercase() }
        persistPresets()
        incoming.size
    }.getOrNull()

    private fun persistPresets() {
        val a = JSONArray()
        _presets.value.forEach { a.put(it.toJson()) }
        prefs.edit().putString("presets", a.toString()).apply()
    }

    private fun loadPresets(): List<Preset> =
        prefs.getString("presets", null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).mapNotNull {
                    runCatching { Preset.fromJson(a.getJSONObject(it)) }.getOrNull()
                }
            }.getOrNull()
        } ?: emptyList()

    /** A rule for this package if there is one, otherwise the catch-all. Foreground stays singular. */
    fun ruleFor(pkg: String, trigger: Trigger): AppRule? {
        val enabled = _rules.value.filter { it.enabled && it.trigger == trigger }
        return enabled.firstOrNull { it.pkg == pkg }
            ?: enabled.firstOrNull { it.isCatchAll }
    }

    fun notificationRuleFor(pkg: String, searchableText: String): AppRule? =
        RuleMatcher.notificationRuleFor(_rules.value, pkg, searchableText)

    // ------------------------------------------------------------------ light output

    /** The highest layer that should currently be showing: alert, else override, else ambient. */
    fun pushCurrent(arm: Boolean = true) =
        send(_enabled.value, activeAlert ?: foregroundOverride?.second, arm)

    /** Fires a one-shot alert, then falls back to the override/ambient layer. */
    fun fireAlert(rule: AppRule) {
        if (!_enabled.value) return
        // Quiet hours and the battery rules silence a flash; the global "only while the screen is
        // off" switch does not. That switch governs the always-on look, and letting it block here
        // killed every per-app colour for as long as the screen was on. Per-rule
        // AppRule.onlyWhenScreenOff is how a rule asks to flash only on a dark screen.
        if (guardState().alertSuppression() != null) return
        val color = if (rule.randomColor) randomColor() else rule.color
        holdAlert(
            alert = Bridge.alertJson(
                id = Bridge.nextAlertId(),
                pattern = rule.pattern,
                color = color,
                durationMs = rule.durationMs,
                speedMs = rule.speedMs,
                brightness = rule.brightness,
            ),
            durationMs = rule.durationMs,
            arm = false,               // a notification must not extend the ambient window
            preview = null,
        )
    }

    /**
     * Takes the transient top layer, for [durationMs], and schedules its release.
     *
     * A notification alert and a Test preview share this one slot because the renderer has one too;
     * whichever arrives last owns it. The renderer self-expires the alert, but the app tracks the
     * window as well so an unlock can cut it short and so [pushCurrent] can keep re-sending the layer
     * that should actually be on top.
     */
    private fun holdAlert(alert: JSONObject, durationMs: Int, arm: Boolean, preview: Ambient?) {
        activeAlert = alert
        alertIsPreview = preview != null
        _previewLook.value = preview
        // A preview is a deliberate "show me this now", so it lights the array even with the master
        // switch off, which is what the Test buttons have always done. Notification alerts get here
        // only once fireAlert has confirmed the switch is on.
        send(_enabled.value || preview != null, alert, arm)
        alertExpiry?.let { main.removeCallbacks(it) }
        val r = Runnable {
            alertExpiry = null
            releaseAlert()
        }
        alertExpiry = r
        main.postDelayed(r, durationMs.toLong() + 150)
    }

    /** Drops the top layer and re-pushes whatever sits underneath it. */
    private fun releaseAlert() {
        activeAlert = null
        alertIsPreview = false
        _previewLook.value = null
        pushCurrent(arm = false)       // handing the layer back must not extend the ambient window
    }

    /**
     * Drops a notification alert that is still running, restoring whatever sits underneath it.
     *
     * Called when the user turns the screen on or unlocks: the alert exists to be noticed, so once it
     * has been there is nothing to keep lit. No-op when no alert is in flight.
     */
    fun cancelAlert() {
        if (activeAlert == null) return
        alertExpiry?.let { main.removeCallbacks(it) }
        alertExpiry = null
        releaseAlert()
    }

    /** Holds a look for as long as [pkg] is in the foreground. */
    fun setForegroundOverride(pkg: String?, rule: AppRule?) {
        if (pkg == null || rule == null) {
            if (foregroundOverride == null) return
            foregroundOverride = null
            pushCurrent(arm = false)
            return
        }
        if (foregroundOverride?.first == pkg) return
        val color = if (rule.randomColor) randomColor() else rule.color
        foregroundOverride = pkg to Bridge.alertJson(
            id = Bridge.nextAlertId(),
            pattern = rule.pattern,
            color = color,
            durationMs = 0,                 // hold until cleared
            speedMs = rule.speedMs,
            brightness = rule.brightness,
        )
        pushCurrent(arm = false)       // opening an app must not extend the ambient window either
    }

    /** True while the top layer is a Test-button preview rather than a notification alert. */
    private var alertIsPreview = false

    /** The look currently being tested, so the hero can show it instead of the ambient look. */
    private val _previewLook = MutableStateFlow<Ambient?>(null)
    val previewLook: StateFlow<Ambient?> = _previewLook.asStateFlow()

    /** One-off preview used by the Test buttons. */
    fun preview(pattern: Pattern, color: Int, speedMs: Int, brightness: Float, durationMs: Int = 4000) {
        holdAlert(
            alert = Bridge.alertJson(
                Bridge.nextAlertId(), pattern, color, durationMs, speedMs, brightness,
            ),
            durationMs = durationMs,
            arm = true,                // the user asked for this one, so it may open a window
            preview = Ambient(
                pattern = pattern, color = color, speedMs = speedMs, brightness = brightness,
            ),
        )
    }

    /**
     * Kills a running preview. Called when the app leaves the foreground: a test the user started by
     * hand must never outlive the screen they started it from.
     *
     * Only a preview. A notification alert is exactly what should keep running once the app is out of
     * sight, so backgrounding must leave it alone.
     */
    fun stopPreview() {
        if (!alertIsPreview) return
        alertExpiry?.let { main.removeCallbacks(it) }
        alertExpiry = null
        // clears the test immediately, and does not hand ambient a fresh window on the way out
        releaseAlert()
    }

    /** Battery level from the sticky broadcast — no receiver to keep alive. */
    private fun batteryPct(): Int {
        val i = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 100
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return 100
        // on the charger there is no reason to hold the array back
        return if (charging) 100 else level * 100 / scale
    }

    private fun inQuietWindow(nowMin: Int): Boolean {
        val start = _quietStart.value
        val end = _quietEnd.value
        if (start == end) return false
        return if (start < end) nowMin in start until end
        else nowMin >= start || nowMin < end       // window crosses midnight
    }

    private fun nowMinutes(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private fun screenOn(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true

    /** Android's own Battery Saver, which the user turns on to make the battery last. */
    private fun powerSaveMode(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode ?: false

    /** Everything the guards need, sampled now. The rules themselves live in [GuardState]. */
    private fun guardState(): GuardState = GuardState(
        screenOffOnly = _screenOffOnly.value,
        screenOn = screenOn(),
        quietEnabled = _quietEnabled.value,
        quietDim = _quietDim.value,
        inQuietWindow = inQuietWindow(nowMinutes()),
        saverGuard = _saverGuard.value,
        powerSaveMode = powerSaveMode(),
        batteryGuard = _batteryGuard.value,
        batteryPct = batteryPct(),
        batteryMinPct = _batteryMinPct.value,
    )

    /** Why the array must stay dark right now, or null. */
    private fun suppressionNow(): Suppression? = guardState().suppression()

    /** Scale applied to every frame — below 1 only inside a dimmed quiet window. */
    private fun dimFactor(): Float =
        if (_quietEnabled.value && _quietDim.value && inQuietWindow(nowMinutes())) {
            _quietDimPct.value / 100f
        } else {
            1f
        }

    /** True while a dimmed quiet window is in effect, for the UI to explain itself. */
    fun inDimmedWindow(): Boolean = dimFactor() < 1f

    /**
     * Re-checks screen, clock and battery, and re-pushes if the answer changed.
     *
     * [armOnRelease] is for the screen going off: that is the moment the user wants the array back, so
     * it starts a fresh auto-off window. The duty-cycle guard still bounds the total.
     */
    fun refreshSuppression(armOnRelease: Boolean = false) {
        val was = _suppression.value
        val now = suppressionNow()
        if (was != now) {
            _suppression.value = now
            pushCurrent(arm = armOnRelease && now == null)
            HiLightTile.refresh(app)
        }
    }

    private fun send(enabled: Boolean, alert: JSONObject?, arm: Boolean = true) {
        // quiet hours and the battery guard override the master switch, and hand the array back to
        // the system rather than merely blanking it, so the system's own alerts still work
        val guards = guardState()
        val suppressed = guards.suppression()
        _suppression.value = suppressed          // the UI still explains the always-on look
        // A transient top layer — a notification flash or a Test preview — lights through the global
        // "only while the screen is off" switch, because that switch is about the always-on look.
        // Every other reason to stay dark still applies to it. A foreground "while this app is open"
        // override is not transient and gets no such exemption.
        val blocked = if (activeAlert != null) guards.alertSuppression() else suppressed
        val json = Bridge.stateJson(
            enabled && blocked == null,
            _priority.value, _ambient.value, alert, _ambientTimeoutMs.value, arm, dimFactor(),
        )
        val active = backend()
        active.push(json)
        standDown(active.transport)
        _activeTransport.value = active.transport
        refreshStatus()
    }

    /**
     * Tells whichever renderer is *not* driving to let the array go.
     *
     * Only one may hold a session. Both directions matter: an adb helper left over from an earlier
     * session has to release when Shizuku takes over, and a Shizuku user service — which runs as a
     * daemon and outlives the app — has to release when the user switches to the adb helper.
     * Standing only the helper down left both processes driving the same eight LEDs.
     *
     * Never armed: being told to stand down is not a user action, and an armed window left behind
     * here would still be open if this renderer later became the one driving.
     */
    private fun standDown(driving: Transport) {
        val idle = Bridge.stateJson(
            false, _priority.value, _ambient.value, null, _ambientTimeoutMs.value,
            arm = false, dim = dimFactor(),
        )
        if (driving == Transport.SHIZUKU) {
            Bridge.writeState(app, idle)
        } else if (shizuku.state.value == ShizukuBackend.State.CONNECTED) {
            shizuku.push(idle)
        }
    }

    fun refreshStatus() {
        if (_transport.value != Transport.ADB) shizuku.refresh()
        _status.value = backend().status()
        _activeTransport.value = backend().transport
    }

    private fun randomColor(): Int {
        val hsv = floatArrayOf((0..359).random().toFloat(), 1f, 1f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    // ------------------------------------------------------------------ persistence

    private fun loadAmbient(): Ambient =
        prefs.getString("ambient", null)?.let { runCatching { Ambient.fromJson(JSONObject(it)) }.getOrNull() }
            ?: Ambient()

    private fun loadRules(): List<AppRule> =
        prefs.getString("rules", null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).mapNotNull { i ->
                    runCatching { AppRule.fromJson(a.getJSONObject(i)) }.getOrNull()
                }
            }.getOrNull()
        } ?: emptyList()

    private fun saveRules() {
        val a = JSONArray()
        _rules.value.forEach { a.put(it.toPrefsJson()) }
        prefs.edit().putString("rules", a.toString()).apply()
    }

    companion object {
        @Volatile
        private var instance: Store? = null

        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }
    }
}
