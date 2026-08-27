package com.hilight.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clears any renderer already holding the array, before a new one is started.
 *
 * Only one renderer may drive the LEDs, but nothing stops a second from opening its own session, and
 * a leftover one goes on pushing black — which wins over the live renderer, so the array stays dark
 * while every status readout insists it is working. Two ways in: running the start command twice
 * without a reboot, and a Shizuku user service, which is a daemon and survives both Shizuku being
 * stopped and this app being uninstalled.
 *
 * The reset enumerates `/proc`, accepts only the exact helper entry point or Shizuku process name,
 * sends cooperative TERM, and waits up to 6.5 seconds for exact exit. That exceeds Engine's bounded
 * four-second stop path. Launch is in the same phone-shell command and is skipped on any survivor,
 * so copying the setup command cannot create a second writer after a failed reset.
 */
private const val ADB_PHONE_RESET =
    "live=1; i=0; while [ ${'$'}i -lt 65 ] && [ -n \"${'$'}live\" ]; do live=\"\"; " +
        "for d in /proc/[0-9]*; do p=${'$'}{d#/proc/}; " +
        "c=${'$'}(tr \"\\000\" \" \" < ${'$'}d/cmdline 2>/dev/null); " +
        "if [ -z \"${'$'}c\" ]; then e=${'$'}(readlink ${'$'}d/exe 2>/dev/null); " +
        "x=${'$'}{e##*/}; if [ \"${'$'}x\" = app_process ] || " +
        "[ \"${'$'}x\" = app_process32 ] || " +
        "[ \"${'$'}x\" = app_process64 ]; then exit 1; fi; continue; fi; " +
        "set -- ${'$'}c; " +
        "x=${'$'}{1##*/}; if { { [ \"${'$'}x\" = app_process ] || " +
        "[ \"${'$'}x\" = app_process32 ] || [ \"${'$'}x\" = app_process64 ]; } && " +
        "[ \"${'$'}{2:-x}\" = / ] && " +
        "[ \"${'$'}{3:-x}\" = com.hilight.core.AdbHelper ]; } || " +
        "[ \"${'$'}{1:-x}\" = com.hilight.studio:hilight ]; then " +
        "kill -TERM ${'$'}p 2>/dev/null || exit 1; live=1; fi; done; " +
        "[ -n \"${'$'}live\" ] && sleep 0.1; i=${'$'}((i + 1)); done; " +
        "[ -z \"${'$'}live\" ] || exit 1"

private const val ADB_PHONE_RESET_CMD =
    "live=1; i=0; while [ ${'$'}i -lt 65 ] && [ ${'$'}live = 1 ]; do live=0; " +
        "for d in /proc/[0-9]*; do p=${'$'}{d#/proc/}; " +
        "c=${'$'}(tr '\\000' ' ' < ${'$'}d/cmdline 2>/dev/null); set -- ${'$'}c; " +
        "if [ ${'$'}# -eq 0 ]; then e=${'$'}(readlink ${'$'}d/exe 2>/dev/null); " +
        "x=${'$'}{e##*/}; if [ ${'$'}{x:-none} = app_process ] || " +
        "[ ${'$'}{x:-none} = app_process32 ] || [ ${'$'}{x:-none} = app_process64 ]; " +
        "then exit 1; fi; continue; fi; " +
        "x=${'$'}{1##*/}; if { { [ ${'$'}x = app_process ] || " +
        "[ ${'$'}x = app_process32 ] || [ ${'$'}x = app_process64 ]; } && " +
        "[ ${'$'}{2:-x} = / ] && " +
        "[ ${'$'}{3:-x} = com.hilight.core.AdbHelper ]; } || " +
        "[ ${'$'}{1:-x} = com.hilight.studio:hilight ]; then " +
        "kill -TERM ${'$'}p 2>/dev/null || exit 1; live=1; fi; done; " +
        "[ ${'$'}live = 1 ] && sleep 0.1; i=${'$'}((i + 1)); done; " +
        "[ ${'$'}live = 0 ] || exit 1"

const val ADB_RESET =
    "adb shell '$ADB_PHONE_RESET'"

/**
 * Starts the renderer out of the installed APK.
 *
 * Single quotes matter: they stop the desktop shell touching `$(...)`, so the *phone* resolves the
 * APK path with its own `pm`. Substituting on the desktop instead makes the command shell-specific —
 * it needs a second `adb shell`, it breaks on any adb that ends lines with CRLF (the trailing return
 * lands in CLASSPATH), and it cannot work in a Windows shell at all, where `head` and `cut` are
 * missing. All of those fail silently, with an empty log.
 *
 * Windows Command Prompt has no single quotes; [ADB_COMMAND_CMD] is the same thing with double ones.
 * Quoting keeps `|`, parentheses, redirects, and `&` away from cmd.exe, while cmd.exe leaves `$`
 * alone, so the phone receives and expands the command substitution.
 */
const val ADB_COMMAND =
    "adb shell '$ADB_PHONE_RESET; " +
        "instance=adb-${'$'}(cat /proc/sys/kernel/random/uuid); " +
        "CLASSPATH=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2) " +
        "nohup app_process / com.hilight.core.AdbHelper --owner adb " +
        "--instance \"${'$'}instance\" --exclusive > /data/local/tmp/hilight.log 2>&1 &'"

/** The same pair for Windows Command Prompt, which does not understand single quotes. */
const val ADB_COMMAND_CMD =
    "adb shell \"$ADB_PHONE_RESET_CMD; " +
        "instance=adb-${'$'}(cat /proc/sys/kernel/random/uuid); " +
        "CLASSPATH=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2) " +
        "nohup app_process / com.hilight.core.AdbHelper --owner adb " +
        "--instance ${'$'}instance --exclusive > /data/local/tmp/hilight.log 2>&1 &\""

@Composable
fun SetupScreen(store: Store) {
    val ctx = LocalContext.current
    val status by store.status.collectAsStateWithLifecycle()
    val masterEnabled by store.enabled.collectAsStateWithLifecycle()
    val manualCleanupPending by store.manualLedCleanupPending.collectAsStateWithLifecycle()
    val manualCleanupInProgress = manualCleanupPending ||
        (status.blackClearPending && status.blackClearCycleSource == "manual")
    val transport by store.transport.collectAsStateWithLifecycle()
    val active by store.activeTransport.collectAsStateWithLifecycle()
    val shizukuState by store.shizuku.state.collectAsStateWithLifecycle()
    val rootState by store.root.state.collectAsStateWithLifecycle()
    val priority by store.priority.collectAsStateWithLifecycle()
    val dynamicColor by store.dynamicColor.collectAsStateWithLifecycle()
    val timeoutMs by store.ambientTimeoutMs.collectAsStateWithLifecycle()
    val quietEnabled by store.quietEnabled.collectAsStateWithLifecycle()
    val quietStart by store.quietStart.collectAsStateWithLifecycle()
    val quietEnd by store.quietEnd.collectAsStateWithLifecycle()
    val batteryGuard by store.batteryGuard.collectAsStateWithLifecycle()
    val batteryMinPct by store.batteryMinPct.collectAsStateWithLifecycle()
    val saverGuard by store.saverGuard.collectAsStateWithLifecycle()
    val suppression by store.suppression.collectAsStateWithLifecycle()
    val respectDnd by store.respectDnd.collectAsStateWithLifecycle()
    val quietDim by store.quietDim.collectAsStateWithLifecycle()
    val quietDimPct by store.quietDimPct.collectAsStateWithLifecycle()
    val screenOffOnly by store.screenOffOnly.collectAsStateWithLifecycle()

    var notifAccess by remember { mutableStateOf(hasNotificationAccess(ctx)) }
    var usageAccess by remember { mutableStateOf(ForegroundWatcher.hasUsageAccess(ctx)) }
    var inspecting by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var checkingForUpdates by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val updateScope = rememberCoroutineScope()
    val conversations by store.conversations.collectAsStateWithLifecycle()

    val checkForUpdates: () -> Unit = {
        checkingForUpdates = true
        updateScope.launch {
            updateResult = withContext(Dispatchers.IO) {
                GitHubUpdateChecker.check(BuildConfig.VERSION_NAME)
            }
            checkingForUpdates = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            notifAccess = hasNotificationAccess(ctx)
            usageAccess = ForegroundWatcher.hasUsageAccess(ctx)
            store.shizuku.refresh()
            delay(1500)
        }
    }

    PixelCard(tone = 2) {
        SectionTitle(
            stringResource(R.string.setup_auto_off_title),
            trailing = { Caption(formatDuration(timeoutMs)) },
        )
        Caption(stringResource(R.string.setup_auto_off_body))
        Caption(stringResource(R.string.setup_auto_off_protection))
        GatedDurationSlider(
            label = stringResource(R.string.setup_stay_on_for),
            valueMs = timeoutMs,
            minMs = 5_000,
            safeMaxMs = Limits.WARN_ABOVE_MS,
            extendedMaxMs = Limits.AMBIENT_MAX_MS,
            unlockLabel = stringResource(R.string.setup_allow_five_minutes),
            warnFirst = stringResource(R.string.setup_warn_long_title) to
                stringResource(R.string.setup_warn_long_body),
            warnSecond = stringResource(R.string.setup_warn_long_confirm_title) to
                stringResource(R.string.setup_warn_long_confirm_body),
            onChange = { store.setAmbientTimeoutMs(it) },
        )
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_dark_title),
            trailing = { suppression?.let { LivePill(stringResource(it.shortRes), ok = false) } },
        )
        ToggleRow(stringResource(R.string.setup_screen_off_only), screenOffOnly) {
            store.setScreenOffOnly(it)
        }
        // The toggle and the suppression pill above say the same two words about the same thing, so
        // they share the one string.
        ToggleRow(stringResource(R.string.suppression_quiet_hours), quietEnabled) {
            store.setQuietHours(it)
        }
        if (quietEnabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { pickTime(ctx, quietStart) { store.setQuietHours(true, startMin = it) } },
                    modifier = Modifier.weight(1f),
                ) { ButtonLabel(stringResource(R.string.setup_quiet_from, clock(quietStart))) }
                FilledTonalButton(
                    onClick = { pickTime(ctx, quietEnd) { store.setQuietHours(true, endMin = it) } },
                    modifier = Modifier.weight(1f),
                ) { ButtonLabel(stringResource(R.string.setup_quiet_until, clock(quietEnd))) }
            }
            ToggleRow(stringResource(R.string.setup_quiet_dim), quietDim) { store.setQuietDim(it) }
            if (quietDim) {
                PixelSlider(
                    stringResource(R.string.setup_dim_to),
                    quietDimPct.toFloat(),
                    2f..40f,
                    { store.setQuietDim(true, it.toInt()) },
                ) { stringResource(R.string.setup_percent, it.toInt()) }
            }
        }
        ToggleRow(stringResource(R.string.setup_respect_dnd), respectDnd) { store.setRespectDnd(it) }
        ToggleRow(stringResource(R.string.setup_pause_saver), saverGuard) { store.setSaverGuard(it) }
        ToggleRow(stringResource(R.string.setup_pause_low_battery), batteryGuard) {
            store.setBatteryGuard(it)
        }
        if (batteryGuard) {
            PixelSlider(
                stringResource(R.string.setup_pause_below),
                batteryMinPct.toFloat(),
                Limits.BATTERY_MIN_PCT.toFloat()..Limits.BATTERY_MAX_PCT.toFloat(),
                { store.setBatteryGuard(true, it.toInt()) },
            ) { stringResource(R.string.setup_percent, it.toInt()) }
            Caption(stringResource(R.string.setup_battery_note))
        }
    }

    val rootPresent = rootState in setOf(
        RootBackend.State.AVAILABLE,
        RootBackend.State.REQUESTING,
        RootBackend.State.STARTING,
        RootBackend.State.RUNNING,
    )
    if (rootPresent) {
        PixelCard(tone = 2) {
            SectionTitle(
                stringResource(R.string.setup_root_title),
                trailing = {
                    LivePill(
                        stringResource(
                            if (rootState == RootBackend.State.RUNNING)
                                R.string.setup_root_active else R.string.setup_root_available
                        ),
                        ok = true,
                    )
                },
            )
            Caption(
                stringResource(
                    when (rootState) {
                        RootBackend.State.AVAILABLE -> R.string.setup_root_available_body
                        RootBackend.State.REQUESTING -> R.string.setup_root_requesting_body
                        RootBackend.State.STARTING -> R.string.setup_root_starting_body
                        else -> R.string.setup_root_active_body
                    }
                )
            )
        }
    } else {
        PixelCard(tone = 2) {
            SectionTitle(stringResource(R.string.setup_privileged_title))
            Caption(stringResource(R.string.setup_privileged_body))
            val selectable = listOf(Transport.AUTO, Transport.SHIZUKU, Transport.ADB)
            val transportLabels = selectable.associateWith { stringResource(it.labelRes) }
            SegmentedSelector(
                options = selectable,
                selected = transport.takeIf { it in selectable } ?: Transport.AUTO,
                label = { transportLabels.getValue(it) },
                onSelect = { store.setTransport(it) },
            )
            if (transport == Transport.AUTO) Caption(stringResource(R.string.setup_transport_auto_note))
            if (rootState == RootBackend.State.DENIED || rootState == RootBackend.State.ERROR) {
                Caption(
                    store.root.errorText()
                        ?: stringResource(R.string.setup_root_error_body)
                )
                TextButton(onClick = store::retryRoot) {
                    ButtonLabel(stringResource(R.string.setup_root_retry))
                }
            }
        }

        AnimatedContent(
            targetState = transport,
            transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
            label = "transportCards",
        ) { t ->
            Column {
                if (t != Transport.ADB) ShizukuCard(store, shizukuState)
                if (t != Transport.SHIZUKU) AdbCard(ctx)
            }
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_notif_title),
            trailing = {
                LivePill(
                    stringResource(
                        if (notifAccess) R.string.setup_state_granted else R.string.setup_state_needed
                    ),
                    notifAccess,
                )
            },
        )
        Caption(stringResource(R.string.setup_notif_body))
        FilledTonalButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        ) { ButtonLabel(stringResource(R.string.setup_open_notif_access)) }
        Caption(stringResource(R.string.setup_inspector_body))
        TextButton(onClick = { inspecting = true }) {
            ButtonLabel(stringResource(R.string.setup_inspector_button))
        }
        // The chat picker's convenience comes from a list of real contact names held on the device,
        // so there has to be a way to be rid of it without uninstalling. Rules keep their own copy of
        // the name they match on, so clearing this list leaves working rules working.
        Caption(
            if (conversations.isEmpty()) {
                stringResource(R.string.setup_chats_none)
            } else {
                stringResource(R.string.setup_chats_remembered, conversations.size)
            }
        )
        if (conversations.isNotEmpty()) {
            TextButton(onClick = { forgetting = true }) {
                ButtonLabel(stringResource(R.string.setup_forget_chats_button))
            }
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_usage_title),
            trailing = {
                LivePill(
                    stringResource(
                        if (usageAccess) R.string.setup_state_granted else R.string.setup_state_optional
                    ),
                    usageAccess,
                )
            },
        )
        Caption(stringResource(R.string.setup_usage_body))
        FilledTonalButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
            ButtonLabel(stringResource(R.string.setup_open_usage_access))
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.setup_appearance_title))
        ToggleRow(stringResource(R.string.setup_wallpaper_colours), dynamicColor) {
            store.setDynamicColor(it)
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_updates_title),
            trailing = {
                Caption(
                    stringResource(
                        R.string.setup_updates_installed,
                        BuildConfig.VERSION_NAME,
                    )
                )
            },
        )
        when {
            checkingForUpdates -> Caption(stringResource(R.string.setup_updates_checking))
            updateResult == null -> Caption(stringResource(R.string.setup_updates_body))
            updateResult is UpdateCheckResult.Available -> Caption(
                stringResource(
                    R.string.setup_updates_available,
                    (updateResult as UpdateCheckResult.Available).release.versionName,
                )
            )
            updateResult is UpdateCheckResult.Current ->
                Caption(stringResource(R.string.setup_updates_current))
            updateResult is UpdateCheckResult.NoPublishedRelease ->
                Caption(stringResource(R.string.setup_updates_none))
            else -> Caption(stringResource(R.string.setup_updates_failed))
        }

        val available = updateResult as? UpdateCheckResult.Available
        if (available != null && !checkingForUpdates) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { openRelease(ctx, available.release.pageUrl) }) {
                    ButtonLabel(stringResource(R.string.setup_updates_view_release))
                }
                TextButton(onClick = checkForUpdates) {
                    ButtonLabel(stringResource(R.string.setup_updates_check_again))
                }
            }
        } else {
            FilledTonalButton(
                onClick = checkForUpdates,
                enabled = !checkingForUpdates,
            ) {
                ButtonLabel(
                    stringResource(
                        if (checkingForUpdates) R.string.setup_updates_checking
                        else R.string.setup_updates_check,
                    )
                )
            }
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.setup_test_title))
        Caption(stringResource(R.string.setup_test_body))
        FilledTonalButton(onClick = { postSelfTestNotification(ctx) }) {
            ButtonLabel(stringResource(R.string.setup_test_button))
        }
    }

    PixelCard(tone = 2) {
        SectionTitle(stringResource(R.string.setup_led_diagnostics_title))
        Caption(stringResource(R.string.setup_led_diagnostics_body))
        FilledTonalButton(
            onClick = {
                updateScope.launch {
                    val payload = withContext(Dispatchers.IO) {
                        val snapshot = store.freshRendererStatusSnapshot()
                        RendererDiagnostics.format(
                            status = snapshot.status,
                            selectedTransport = snapshot.selectedTransport,
                            activeTransport = snapshot.activeTransport,
                            appVersionName = BuildConfig.VERSION_NAME,
                            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                            deviceModel = Build.MODEL,
                            buildId = Build.ID,
                            sdkInt = Build.VERSION.SDK_INT,
                            capturedAtEpochMs = snapshot.capturedAtEpochMs,
                        )
                    }
                    copy(ctx, payload, R.string.setup_led_diagnostics_copied)
                }
            },
        ) {
            ButtonLabel(stringResource(R.string.setup_led_diagnostics_copy))
        }
        Caption(stringResource(R.string.setup_led_cleanup_retry_body))
        if (!status.alive || status.rendererStale) {
            Caption(stringResource(R.string.setup_led_cleanup_renderer_unavailable))
        }
        FilledTonalButton(
            onClick = { store.retryLedCleanup() },
            enabled = store.isLedCleanupRetryEnabled(
                masterEnabled = masterEnabled,
                status = status,
                requestPending = manualCleanupPending,
            ),
        ) {
            ButtonLabel(
                stringResource(
                    if (manualCleanupInProgress) R.string.setup_led_cleanup_retry_pending
                    else R.string.setup_led_cleanup_retry,
                )
            )
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.setup_priority_title))
        Caption(stringResource(R.string.setup_priority_body))
        PixelSlider(
            stringResource(R.string.setup_priority_label),
            priority.toFloat(),
            -10f..10f,
            { store.setPriority(it.toInt()) },
        ) { it.toInt().toString() }
    }

    if (inspecting) {
        NotificationInspectorDialog(store) { inspecting = false }
    }

    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.setup_forget_chats_title)) },
            text = {
                Text(
                    stringResource(R.string.setup_forget_chats_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.forgetConversations()
                        forgetting = false
                    },
                ) { ButtonLabel(stringResource(R.string.setup_forget_chats_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = false }) {
                    ButtonLabel(stringResource(R.string.setup_forget_chats_dismiss))
                }
            },
        )
    }
}

@Composable
private fun ShizukuCard(store: Store, state: ShizukuBackend.State) {
    val ctx = LocalContext.current
    PixelCard {
        // The card is named after the transport it is about, so it uses that same name.
        SectionTitle(
            stringResource(R.string.transport_shizuku),
            trailing = {
                val pill = when (state) {
                    ShizukuBackend.State.CONNECTED -> R.string.shizuku_state_connected
                    ShizukuBackend.State.CONNECTING -> R.string.shizuku_state_connecting
                    ShizukuBackend.State.NEEDS_PERMISSION -> R.string.shizuku_state_needs_permission
                    ShizukuBackend.State.NOT_RUNNING -> R.string.shizuku_state_not_running
                    ShizukuBackend.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
                    ShizukuBackend.State.FAILED -> R.string.shizuku_state_failed
                }
                LivePill(stringResource(pill), state == ShizukuBackend.State.CONNECTED)
            },
        )

        Caption(stringResource(R.string.shizuku_reattach_note))

        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(100))) },
            label = "shizukuState",
        ) { s ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s) {
                    ShizukuBackend.State.NOT_INSTALLED -> {
                        Caption(stringResource(R.string.shizuku_not_installed_body))
                        Button(onClick = { openShizukuListing(ctx) }) {
                            ButtonLabel(stringResource(R.string.shizuku_get))
                        }
                    }

                    ShizukuBackend.State.NOT_RUNNING -> {
                        Caption(stringResource(R.string.shizuku_not_running_body))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { openShizuku(ctx) }) {
                                ButtonLabel(stringResource(R.string.shizuku_open))
                            }
                            TextButton(onClick = { store.shizuku.refresh() }) {
                                ButtonLabel(stringResource(R.string.shizuku_check_again))
                            }
                        }
                    }

                    ShizukuBackend.State.NEEDS_PERMISSION -> {
                        Caption(stringResource(R.string.shizuku_needs_permission_body))
                        Button(onClick = { store.shizuku.requestPermission() }) {
                            ButtonLabel(stringResource(R.string.shizuku_request_access))
                        }
                    }

                    ShizukuBackend.State.CONNECTED -> {
                        Caption(stringResource(R.string.shizuku_connected_body))
                        TextButton(onClick = { store.disconnectShizuku() }) {
                            ButtonLabel(stringResource(R.string.shizuku_disconnect))
                        }
                    }

                    else -> {
                        // Two kinds of failure text: the ones HiLight diagnoses itself, which are
                        // translated, and whatever the framework handed back, which is not ours to
                        // translate and is shown as it came.
                        Caption(
                            store.shizuku.errorRes()?.let { stringResource(it) }
                                ?: store.shizuku.errorText()
                                ?: stringResource(R.string.shizuku_unreachable)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { store.shizuku.refresh() }) {
                                ButtonLabel(stringResource(R.string.shizuku_retry))
                            }
                            TextButton(onClick = { openShizuku(ctx) }) {
                                ButtonLabel(stringResource(R.string.shizuku_open))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdbCard(ctx: Context) {
    PixelCard {
        SectionTitle(stringResource(R.string.adb_title))
        Caption(stringResource(R.string.adb_body))
        Text(
            ADB_COMMAND,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.shapes.medium,
                )
                .padding(14.dp),
        )
        Caption(stringResource(R.string.adb_shells_note))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { copy(ctx, ADB_COMMAND, R.string.adb_copied) }) {
                ButtonLabel(stringResource(R.string.adb_copy))
            }
            TextButton(onClick = { copy(ctx, ADB_COMMAND_CMD, R.string.adb_copied_cmd) }) {
                ButtonLabel(stringResource(R.string.adb_copy_cmd))
            }
        }
        Caption(stringResource(R.string.adb_verify_note))
        TextButton(onClick = { share(ctx, ADB_COMMAND) }) {
            ButtonLabel(stringResource(R.string.adb_send))
        }
    }
}

// The confirmation is a resource rather than a string because these run from a click, outside
// composition. "hilight" is the clipboard's own label for the clip, not something a reader sees.
private fun copy(ctx: Context, text: String, @StringRes toast: Int) {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("hilight", text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.adb_share_title)))
}

private fun openShizuku(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ShizukuBackend.SHIZUKU_PKG)
    if (launch != null) ctx.startActivity(launch) else openShizukuListing(ctx)
}

private fun openShizukuListing(ctx: Context) {
    val uri = Uri.parse("https://shizuku.rikka.app/")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(ctx, R.string.setup_no_browser, Toast.LENGTH_SHORT).show() }
}

private fun openRelease(ctx: Context, pageUrl: String) {
    val uri = Uri.parse(pageUrl)
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(ctx, R.string.setup_no_browser, Toast.LENGTH_SHORT).show() }
}

private fun postSelfTestNotification(ctx: Context) {
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    // The channel id stays a literal — it is a key, not a label. The channel *name* is a label: it
    // appears in the system's own notification settings for this app.
    nm.createNotificationChannel(
        android.app.NotificationChannel(
            "selftest",
            ctx.getString(R.string.setup_selftest_channel),
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        )
    )
    nm.notify(
        42,
        android.app.Notification.Builder(ctx, "selftest")
            .setContentTitle(ctx.getString(R.string.setup_selftest_title))
            .setContentText(ctx.getString(R.string.setup_selftest_body))
            .setSmallIcon(R.drawable.hilight_logo)
            .setAutoCancel(true)
            .build()
    )
}

/** minutes since midnight to a 24-hour clock string */
fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun pickTime(ctx: Context, currentMinutes: Int, onPicked: (Int) -> Unit) {
    android.app.TimePickerDialog(
        ctx,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        currentMinutes / 60,
        currentMinutes % 60,
        android.text.format.DateFormat.is24HourFormat(ctx),
    ).show()
}

private fun hasNotificationAccess(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?: return false
    return flat.contains(ctx.packageName)
}
