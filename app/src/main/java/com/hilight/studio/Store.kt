package com.hilight.studio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    /**
     * Chats the listener has seen, newest first, across all apps.
     *
     * This is the whole reason the user never has to type a name into a conversation rule: the picker
     * only ever offers strings that arrived in a real notification, so a rule cannot be one nickname
     * or one emoji away from matching nothing.
     */
    private val _conversations = MutableStateFlow(loadConversations())
    val conversations: StateFlow<List<ConversationRef>> = _conversations.asStateFlow()

    /** Package the rules screen is waiting on, or null when idle. */
    private val _learnTarget = MutableStateFlow<String?>(null)
    val learnTarget: StateFlow<String?> = _learnTarget.asStateFlow()

    /** The chat captured while learning; the UI reads it then calls [clearLearned]. */
    private val _learned = MutableStateFlow<ConversationRef?>(null)
    val learned: StateFlow<ConversationRef?> = _learned.asStateFlow()

    /**
     * The last few notifications the listener looked at, newest first. Never persisted.
     *
     * A [MessageInfo] carries the sender and the message body, so writing this list to disk would turn
     * a debugging aid into a copy of the user's messages that outlives the process that needed it.
     * Losing the list whenever the listener restarts is the right trade: it only exists to answer "why
     * didn't my rule fire?" about a notification that just arrived.
     */
    private val _recentPeeks = MutableStateFlow<List<MessageInfo>>(emptyList())
    val recentPeeks: StateFlow<List<MessageInfo>> = _recentPeeks.asStateFlow()

    /**
     * When each rule last matched, keyed by [AppRule.id].
     *
     * A flow rather than a plain map because the rules screen shows this per card, and it is the one
     * diagnostic the whole per-chat feature leans on: a rule that never matches is indistinguishable
     * from a broken app until this line moves. Written on main only, replaced rather than mutated.
     */
    private val _lastMatch = MutableStateFlow(loadLastMatch())
    val lastMatch: StateFlow<Map<String, Long>> = _lastMatch.asStateFlow()

    /**
     * The pending coalesced write of everything the listener learns; non-null means one is scheduled.
     *
     * Both flags and this handle are only ever touched on main, which is where every note* call ends
     * up, so no locking is needed around them.
     */
    private var pendingFlush: Runnable? = null
    private var conversationsDirty = false
    private var lastMatchDirty = false

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

    /** Adds or updates one stable rule. Foreground remains singular per package. */
    fun upsertRule(rule: AppRule, replacing: AppRule? = null) {
        val current = _rules.value
        val oldId = replacing?.id ?: rule.id
        val live = current.firstOrNull { it.id == oldId }
        // A notification can learn a stable chat id while its editor is open. Preserve that newly
        // learned id unless the user explicitly opened a keyed rule and chose "re-learn this chat".
        val incoming = if (
            replacing != null &&
            replacing.conversationKey.isNullOrBlank() &&
            rule.conversationKey.isNullOrBlank() &&
            !live?.conversationKey.isNullOrBlank()
        ) {
            rule.copy(conversationKey = live.conversationKey)
        } else {
            rule
        }
        val out = ArrayList<AppRule>(current.size + 1)
        var placed = false

        for (existing in current) {
            val sameRule = existing.id == oldId || existing.id == incoming.id
            val displacedForeground = incoming.trigger == Trigger.FOREGROUND &&
                existing.pkg == incoming.pkg &&
                existing.trigger == Trigger.FOREGROUND &&
                !sameRule

            when {
                sameRule && !placed -> {
                    out += incoming
                    placed = true
                }
                sameRule || displacedForeground -> Unit
                else -> out += existing
            }
        }
        if (!placed) out += incoming

        _rules.value = out
        saveRules()
        ForegroundWatcher.syncRunning(app, _rules.value, _enabled.value)
    }

    fun removeRule(rule: AppRule) {
        _rules.value = _rules.value.filterNot { it.id == rule.id }
        saveRules()
        ForegroundWatcher.syncRunning(app, _rules.value, _enabled.value)
    }

    /** Restores a just-deleted rule at its old position, preserving text-rule priority. */
    fun restoreRule(rule: AppRule, index: Int) {
        if (_rules.value.any { it.id == rule.id || ConversationMatch.sameTarget(it, rule) }) return
        val restored = _rules.value.toMutableList()
        restored.add(index.coerceIn(0, restored.size), rule)
        _rules.value = restored
        saveRules()
        ForegroundWatcher.syncRunning(app, _rules.value, _enabled.value)
    }

    /** Moves one app-wide text rule relative to the other text rules for the same app. */
    fun moveTextRule(rule: AppRule, delta: Int) {
        if (delta == 0 || rule.isConversationRule || rule.keyword.isBlank()) return
        val current = _rules.value.toMutableList()
        val peers = current.withIndex().filter { (_, saved) ->
            saved.pkg == rule.pkg &&
                saved.trigger == Trigger.NOTIFICATION &&
                !saved.isConversationRule &&
                saved.keyword.isNotBlank()
        }
        val peerIndex = peers.indexOfFirst { it.value.id == rule.id }
        if (peerIndex < 0) return
        val targetPeer = (peerIndex + delta).takeIf { it in peers.indices } ?: return
        val from = peers[peerIndex].index
        val to = peers[targetPeer].index
        val swap = current[from]
        current[from] = current[to]
        current[to] = swap
        _rules.value = current
        saveRules()
    }

    fun savePreset(name: String) {
        // The caller supplies the fallback name: it is user-visible text, and only a composable can
        // resolve it from resources. An empty name arriving here would be a bug in that caller.
        val clean = name.trim()
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

    /**
     * A rule for this package if there is one, otherwise the catch-all.
     *
     * Conversation rules are skipped deliberately. This is the lookup the FOREGROUND watcher uses, and
     * "while this app is open" has no chat attached to it, so a per-chat rule must never be the answer
     * here — it would hold a contact's colour for as long as the app stayed on screen. [ruleForMessage]
     * is the notification-side lookup, and the only one that understands conversations.
     */
    fun ruleFor(pkg: String, trigger: Trigger): AppRule? {
        val enabled = _rules.value.filter {
            it.enabled && it.trigger == trigger && !it.isConversationRule
        }
        return enabled.firstOrNull { it.pkg == pkg }
            ?: enabled.firstOrNull { it.isCatchAll }
    }

    // ------------------------------------------------------------------ conversations

    /**
     * Forgets every remembered chat.
     *
     * The picker's convenience is built on a list of real contact names kept on the device, so there
     * has to be a way to throw it away — a user who tried the feature and moved on should not have to
     * uninstall the app to get rid of it. Written straight through rather than through the debounced
     * flush: this is the one place where the user has explicitly asked for data to be gone.
     *
     * Existing rules are left alone. They hold their own copy of the name they matched on, so a rule
     * keeps working, and deleting somebody's rule without being asked would be the greater surprise.
     */
    fun forgetConversations() {
        onMain {
            _conversations.value = emptyList()
            conversationsDirty = false
            prefs.edit().putString("conversations", JSONArray().toString()).apply()
        }
    }

    /** Chats seen for one app, newest first. What the rule editor's chat picker lists. */
    fun conversationsFor(pkg: String): List<ConversationRef> =
        _conversations.value.filter { it.pkg == pkg }

    /**
     * The rule [info] should fire, or null. Conversation rules win over the plain per-app one.
     *
     * The ladder itself lives in [ConversationMatch.resolve] so it can be unit-tested without a
     * device; this only hands it the current rule set.
     */
    fun ruleForMessage(info: MessageInfo): AppRule? =
        runCatching { ConversationMatch.resolve(_rules.value, info) }
            .onFailure { Log.w(TAG, "rule resolution failed", it) }
            .getOrNull()

    /**
     * Waits for the next chat notification from [pkg], so the user picks a chat by messaging it.
     *
     * Learn mode is purely an observer of the stream [noteConversation] already sees, so arming it
     * cannot change which rules fire, or when.
     */
    fun startLearning(pkg: String) {
        _learned.value = null
        _learnTarget.value = pkg
    }

    /** Stops waiting. The UI must call this when it leaves the screen; nothing times out on its own. */
    fun stopLearning() {
        _learnTarget.value = null
    }

    /** Drops the captured chat once the UI has read it. */
    fun clearLearned() {
        _learned.value = null
    }

    /**
     * When [rule] last matched, or null for never.
     *
     * The rules screen reads [lastMatch] directly so its cards update as matches arrive; this is for
     * anywhere that wants one answer without collecting the flow.
     */
    fun lastMatchedMs(rule: AppRule): Long? = _lastMatch.value[rule.id]

    /**
     * Records that the listener looked at [info], for the inspector.
     *
     * Called for every notification, including the ones no rule wanted — a peek that matched nothing
     * is the interesting one when the user is asking why their rule stayed quiet.
     */
    fun notePeek(info: MessageInfo) {
        onMain {
            _recentPeeks.value = (listOf(info) + _recentPeeks.value).take(MAX_PEEKS)
        }
    }

    /**
     * Remembers the chat [info] came from, so the picker can offer it later.
     *
     * Called for every notification, matched or not: an app with no rule yet is exactly the one the
     * user is about to write a rule for, and a picker that only knew about chats which already had
     * rules would be empty when it mattered.
     */
    fun noteConversation(info: MessageInfo) {
        onMain {
            // A summary is the app's own "3 new messages" wrapper. It stands for no single chat and
            // its title is usually just the app name, so remembering it only puts junk in the picker.
            if (info.isGroupSummary || info.isOngoing) return@onMain
            // Anything with a name is worth keeping, even from an app that never adopted
            // MessagingStyle: Slack and Discord put the chat in the title and nowhere else, so
            // insisting on info.isConversation here would leave their rules with nothing to pick
            // from. The price is that a few non-chat notifications from such apps are remembered too.
            //
            // Some apps set a shortcutId and leave it empty. A blank key is worse than no key at all:
            // it compares equal to every other blank one, so clean it once at the edge and let the
            // normal name fallback handle that conversation everywhere downstream.
            val ref = info.toRef()?.let { r -> r.copy(key = r.key?.takeIf { it.isNotBlank() }) }
                ?: return@onMain
            captureLearned(ref)
            val merged = withConversation(_conversations.value, ref)
            if (merged == _conversations.value) return@onMain
            _conversations.value = merged
            conversationsDirty = true
            scheduleFlush()
        }
    }

    /**
     * Bookkeeping for a rule that has just fired: when it last matched, and its chat key if it was
     * still matching by name.
     *
     * [atMs] defaults at the call site rather than in here, so the stamp is when the notification
     * arrived and not whenever the main thread got round to this.
     */
    fun noteRuleFired(rule: AppRule, info: MessageInfo, atMs: Long = System.currentTimeMillis()) {
        onMain {
            val fired = healKey(rule, info)
            _lastMatch.value = _lastMatch.value + (fired.id to atMs)
            lastMatchDirty = true
            scheduleFlush()
        }
    }

    /**
     * Hands the rules screen the chat it is waiting for.
     *
     * The first sighting wins and disarms the wait, so a busy phone cannot walk the user's choice on
     * to whichever chat happened to speak next while they were reading the dialog.
     */
    private fun captureLearned(ref: ConversationRef) {
        if (_learnTarget.value != ref.pkg) return
        _learned.value = ref
        _learnTarget.value = null
    }

    /**
     * Fills in a name-matched rule's [AppRule.conversationKey] from the notification that matched it.
     * Stable rule ids mean learning the chat id no longer moves the rule to another storage slot.
     */
    private fun healKey(rule: AppRule, info: MessageInfo): AppRule {
        val key = info.shortcutId
        if (key.isNullOrBlank()) return rule
        if (!rule.isConversationRule || !rule.conversationKey.isNullOrBlank()) return rule
        if (_rules.value.none { it.id == rule.id }) return rule

        // Two people really can be saved under the same name. Until one already has a stable id,
        // narrowing the name-only rule to whichever one spoke first would be surprising.
        val ambiguous = _conversations.value.count { seen ->
            seen.pkg == rule.pkg &&
                ConversationMatch.normalise(seen.name) ==
                ConversationMatch.normalise(rule.conversationName) &&
                seen.key != null && seen.key != key
        } > 0
        if (ambiguous) {
            Log.i(TAG, "not healing ${rule.pkg}: more than one known chat answers to that name")
            return rule
        }

        val healed = rule.copy(conversationKey = key)
        Log.i(TAG, "learned a stable chat id for a ${rule.pkg} rule; renames can no longer break it")
        _rules.value = _rules.value.map { if (it.id == rule.id) healed else it }
        saveRules()
        return healed
    }

    /**
     * [list] with [incoming] folded in, newest first and capped.
     *
     * Which row a sighting belongs to is decided by the same ladder the matcher climbs: two keys settle
     * it outright, and only when one side has none does the normalised name get a say. Exactly one row
     * is ever updated, which is what stops a keyless sighting from merging two chats that their keys
     * had told apart — two contacts really can be saved under the same name.
     */
    private fun withConversation(
        list: List<ConversationRef>,
        incoming: ConversationRef,
    ): List<ConversationRef> {
        val slot = list.indexOfFirst { old ->
            if (old.pkg != incoming.pkg) return@indexOfFirst false
            // Two keys settle it outright: equal is the same chat, different is a different chat
            // whatever the two names happen to say.
            if (incoming.key != null && old.key != null) return@indexOfFirst incoming.key == old.key
            sameName(incoming, old)
        }
        val out = ArrayList<ConversationRef>(list.size + 1)
        list.forEachIndexed { i, old ->
            if (i == slot) {
                // The newer sighting owns the name and the group flag — a renamed contact should show
                // its new name in the picker — while the timestamp only ever moves forward and a key,
                // once learned, is never handed back: it is the one matcher a rename cannot break.
                out += incoming.copy(
                    key = incoming.key ?: old.key,
                    lastSeenMs = maxOf(incoming.lastSeenMs, old.lastSeenMs),
                )
                return@forEachIndexed
            }
            // A keyed sighting also clears out the keyless row the same chat left behind from before
            // its app got round to posting a shortcutId. That row would otherwise sit in the picker as
            // a second, weaker copy of one chat, with nothing on screen to say which of the two will
            // still work once the contact is renamed.
            val redundant = incoming.key != null && old.key == null &&
                old.pkg == incoming.pkg && sameName(incoming, old)
            if (!redundant) out += old
        }
        if (slot < 0) out += incoming
        return out.sortedByDescending { it.lastSeenMs }.take(ConversationRef.MAX_REMEMBERED)
    }

    /** Names compared exactly as the matcher compares them, so the picker agrees with what will fire. */
    private fun sameName(a: ConversationRef, b: ConversationRef): Boolean {
        val n = ConversationMatch.normalise(a.name)
        return n.isNotEmpty() && n == ConversationMatch.normalise(b.name)
    }

    /**
     * Queues the one write this burst of notifications needs.
     *
     * Persisting only on a real change is not enough by itself: every message from a known chat moves
     * its lastSeenMs, and a catch-all rule matches every notification there is, so both of these
     * genuinely change on almost every notification the listener sees. That is a hot path, and a
     * SharedPreferences edit on it is a disk write per message. So the first change arms one delayed
     * write and every change after it rides along, which turns a busy group chat into a single edit
     * every couple of seconds. Coalescing rather than restarting the timer matters: a phone that never
     * stops buzzing would otherwise never write at all.
     *
     * Neither of these is precious. Losing the last two seconds of sightings to a process death costs
     * the user one notification's worth of freshness in a picker they are not looking at.
     */
    private fun scheduleFlush() {
        if (pendingFlush != null) return
        val r = Runnable {
            pendingFlush = null
            val edit = prefs.edit()
            if (conversationsDirty) {
                conversationsDirty = false
                edit.putString("conversations", conversationsJson())
            }
            if (lastMatchDirty) {
                lastMatchDirty = false
                edit.putString("ruleLastMatch", lastMatchJson())
            }
            edit.apply()
        }
        pendingFlush = r
        main.postDelayed(r, FLUSH_MS)
    }

    /**
     * Runs [block] on the main thread, where every other mutation in this class already happens.
     *
     * The notification listener calls in on its own thread while Compose reads these flows, and noting
     * a peek or a chat is a read-modify-write of a list, which two threads cannot safely do at once.
     * Posting also moves any throw off the listener — where it would take the whole service down and
     * every rule with it — but a throw inside a Handler kills the process instead, so the block is
     * caught here rather than merely moved.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        main.post {
            runCatching { block() }.onFailure { Log.w(TAG, "notification bookkeeping failed", it) }
        }
    }

    // ------------------------------------------------------------------ light output

    /** The highest layer that should currently be showing: alert, else override, else ambient. */
    fun pushCurrent(arm: Boolean = true) =
        send(_enabled.value, activeAlert ?: foregroundOverride?.second, arm)

    /** Fires a one-shot alert, then falls back to the override/ambient layer. */
    fun fireAlert(rule: AppRule) {
        // The notification listener calls this from its own thread, while the alert slot below, its
        // expiry callback and every other push are main-thread state. Hopping once here keeps the top
        // layer single-threaded instead of trusting two threads not to interleave over it — an alert
        // half-installed while the screen-on receiver was cancelling one is exactly how a colour gets
        // stuck on. The bridge write this ends in already happens on main for every slider the user
        // moves, so it is not a new cost.
        if (Looper.myLooper() != main.looper) {
            main.post { runCatching { fireAlert(rule) }.onFailure { Log.w(TAG, "alert failed", it) } }
            return
        }
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
        pruneLastMatch()
    }

    private fun loadConversations(): List<ConversationRef> =
        prefs.getString("conversations", null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).mapNotNull { i ->
                    runCatching { ConversationRef.fromJson(a.getJSONObject(i)) }.getOrNull()
                }
            }
                // Unreadable stored chats mean the picker silently comes up empty, and the user has no
                // way to tell that from "HiLight has not seen a message yet". At least leave a trace.
                .onFailure { Log.w(TAG, "stored chat list unreadable, starting the list again", it) }
                .getOrNull()
        }?.sortedByDescending { it.lastSeenMs } ?: emptyList()

    private fun conversationsJson(): String {
        val a = JSONArray()
        _conversations.value.forEach { a.put(it.toJson()) }
        return a.toString()
    }

    private fun loadLastMatch(): Map<String, Long> =
        prefs.getString("ruleLastMatch", null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                val out = HashMap<String, Long>()
                o.keys().forEach { k -> out[k] = o.optLong(k, 0L) }
                out
            }
                // Losing this makes every rule read "not matched yet", which looks exactly like a
                // feature that has never worked. Worth a line in the log before it happens silently.
                .onFailure { Log.w(TAG, "stored match history unreadable, starting it again", it) }
                .getOrNull()
        } ?: emptyMap()

    private fun lastMatchJson(): String {
        val o = JSONObject()
        _lastMatch.value.forEach { (id, ms) -> o.put(id, ms) }
        return o.toString()
    }

    /** Forgets match-history entries belonging to rules that no longer exist. */
    private fun pruneLastMatch() {
        val live = _rules.value.mapTo(HashSet()) { it.id }
        val kept = _lastMatch.value.filterKeys { it in live }
        if (kept.size != _lastMatch.value.size) {
            _lastMatch.value = kept
            lastMatchDirty = true
            scheduleFlush()
        }
    }

    companion object {
        private const val TAG = "HiLightStore"

        /** How many peeks the inspector keeps — enough to cover the last minute of a busy phone. */
        private const val MAX_PEEKS = 30

        /** How long anything the listener learns may sit in memory before it is written. */
        private const val FLUSH_MS = 2_000L

        @Volatile
        private var instance: Store? = null

        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }
    }
}
