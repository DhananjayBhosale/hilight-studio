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

    private val _quietStart = MutableStateFlow(prefs.getInt("quietStart", 23 * 60))
    val quietStart: StateFlow<Int> = _quietStart.asStateFlow()

    private val _quietEnd = MutableStateFlow(prefs.getInt("quietEnd", 7 * 60))
    val quietEnd: StateFlow<Int> = _quietEnd.asStateFlow()

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

    private val _activeTransport = MutableStateFlow(Transport.ADB)
    val activeTransport: StateFlow<Transport> = _activeTransport.asStateFlow()

    private var foregroundOverride: Pair<String, JSONObject>? = null

    private var activeAlert: JSONObject? = null
    private var alertExpiry: Runnable? = null

    init {
        Bridge.ensureFiles(app)
        AdbAccess.ensure(app)
        _suppression.value = suppressionNow()

        main.post(object : Runnable {
            override fun run() {
                refreshSuppression()
                main.postDelayed(this, 30_000)
            }
        })

        app.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_SCREEN_OFF -> refreshSuppression(armOnRelease = true)
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            cancelAlert()
                            refreshSuppression()
                        }

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

        shizuku.onAvailabilityChanged = {
            main.post {
                pushCurrent(arm = false)
                HiLightTile.refresh(app)
            }
        }
    }

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
        _rules.value = _rules.value.filterNot { it.pkg == rule.pkg && it.trigger == rule.trigger } + rule
        saveRules()
        ForegroundWatcher.syncRunning(app, _rules.value, _enabled.value)
    }

    fun removeRule(rule: AppRule) {
        _rules.value = _rules.value.filterNot { it.pkg == rule.pkg && it.trigger == rule.trigger }
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

    fun exportPresets(): String = JSONObject().apply {
        put("v", 1)
        put("presets", JSONArray().also { a -> _presets.value.forEach { a.put(it.toJson()) } })
    }.toString(2)

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

    fun ruleFor(pkg: String, trigger: Trigger): AppRule? {
        val enabled = _rules.value.filter { it.enabled && it.trigger == trigger }
        return enabled.firstOrNull { it.pkg == pkg }
            ?: enabled.firstOrNull { it.isCatchAll }
    }

    fun pushCurrent(arm: Boolean = true) =
        send(_enabled.value, activeAlert ?: foregroundOverride?.second, arm)

    fun fireAlert(rule: AppRule) {
        if (!_enabled.value) return

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
            arm = false,
            preview = null,
        )
    }

    private fun holdAlert(alert: JSONObject, durationMs: Int, arm: Boolean, preview: Ambient?) {
        activeAlert = alert
        alertIsPreview = preview != null
        _previewLook.value = preview

        send(_enabled.value || preview != null, alert, arm)
        alertExpiry?.let { main.removeCallbacks(it) }
        val r = Runnable {
            alertExpiry = null
            releaseAlert()
        }
        alertExpiry = r
        main.postDelayed(r, durationMs.toLong() + 150)
    }

    private fun releaseAlert() {
        activeAlert = null
        alertIsPreview = false
        _previewLook.value = null
        pushCurrent(arm = false)
    }

    fun cancelAlert() {
        if (activeAlert == null) return
        alertExpiry?.let { main.removeCallbacks(it) }
        alertExpiry = null
        releaseAlert()
    }

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
            durationMs = 0,
            speedMs = rule.speedMs,
            brightness = rule.brightness,
        )
        pushCurrent(arm = false)
    }

    private var alertIsPreview = false

    private val _previewLook = MutableStateFlow<Ambient?>(null)
    val previewLook: StateFlow<Ambient?> = _previewLook.asStateFlow()

    fun preview(pattern: Pattern, color: Int, speedMs: Int, brightness: Float, durationMs: Int = 4000) {
        holdAlert(
            alert = Bridge.alertJson(
                Bridge.nextAlertId(), pattern, color, durationMs, speedMs, brightness,
            ),
            durationMs = durationMs,
            arm = true,
            preview = Ambient(
                pattern = pattern, color = color, speedMs = speedMs, brightness = brightness,
            ),
        )
    }

    fun stopPreview() {
        if (!alertIsPreview) return
        alertExpiry?.let { main.removeCallbacks(it) }
        alertExpiry = null

        releaseAlert()
    }

    private fun batteryPct(): Int {
        val i = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 100
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return 100

        return if (charging) 100 else level * 100 / scale
    }

    private fun inQuietWindow(nowMin: Int): Boolean {
        val start = _quietStart.value
        val end = _quietEnd.value
        if (start == end) return false
        return if (start < end) nowMin in start until end
        else nowMin >= start || nowMin < end
    }

    private fun nowMinutes(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private fun screenOn(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true

    private fun powerSaveMode(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode ?: false

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

    private fun suppressionNow(): Suppression? = guardState().suppression()

    private fun dimFactor(): Float =
        if (_quietEnabled.value && _quietDim.value && inQuietWindow(nowMinutes())) {
            _quietDimPct.value / 100f
        } else {
            1f
        }

    fun inDimmedWindow(): Boolean = dimFactor() < 1f

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
        val guards = guardState()
        val suppressed = guards.suppression()
        _suppression.value = suppressed

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
        val active = backend()
        _status.value = active.status()
        _activeTransport.value = active.transport
        if (active.transport == Transport.ADB && !_status.value.alive) AdbAccess.ensure(app)
    }

    private fun randomColor(): Int {
        val hsv = floatArrayOf((0..359).random().toFloat(), 1f, 1f)
        return android.graphics.Color.HSVToColor(hsv)
    }

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
