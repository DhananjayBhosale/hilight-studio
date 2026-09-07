package com.hilight.studio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

private enum class PlayUpdateState {
    IDLE,
    CHECKING,
    DOWNLOADING,
    DOWNLOADED,
    CURRENT,
    CANCELED,
    FAILED,
}

internal enum class PlayUpdateDecision {
    DOWNLOADED,
    START_FLEXIBLE,
    CURRENT,
    UNAVAILABLE,
}

internal fun resolvePlayUpdateDecision(
    updateAvailability: Int,
    installStatus: Int,
    flexibleAllowed: Boolean,
): PlayUpdateDecision = when {
    installStatus == InstallStatus.DOWNLOADED -> PlayUpdateDecision.DOWNLOADED
    updateAvailability == UpdateAvailability.UPDATE_AVAILABLE && flexibleAllowed ->
        PlayUpdateDecision.START_FLEXIBLE
    updateAvailability == UpdateAvailability.UPDATE_NOT_AVAILABLE -> PlayUpdateDecision.CURRENT
    else -> PlayUpdateDecision.UNAVAILABLE
}

/** A user-triggered flexible update handled entirely by the Google Play Store. */
@Composable
internal fun DistributionUpdateCard() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember(context.applicationContext) {
        AppUpdateManagerFactory.create(context.applicationContext)
    }
    var state by remember { mutableStateOf(PlayUpdateState.IDLE) }

    fun refreshDownloadedState() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                state = PlayUpdateState.DOWNLOADED
            }
        }
    }

    val updateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK && state != PlayUpdateState.DOWNLOADED) {
            state = PlayUpdateState.CANCELED
        }
    }

    DisposableEffect(manager, lifecycleOwner) {
        val installListener = InstallStateUpdatedListener { installState ->
            state = when (installState.installStatus()) {
                InstallStatus.DOWNLOADING,
                InstallStatus.PENDING,
                InstallStatus.INSTALLING,
                -> PlayUpdateState.DOWNLOADING
                InstallStatus.DOWNLOADED -> PlayUpdateState.DOWNLOADED
                InstallStatus.CANCELED -> PlayUpdateState.CANCELED
                InstallStatus.FAILED -> PlayUpdateState.FAILED
                else -> state
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshDownloadedState()
        }
        manager.registerListener(installListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            manager.unregisterListener(installListener)
        }
    }

    LaunchedEffect(manager) {
        refreshDownloadedState()
    }

    fun checkForUpdate() {
        val host = activity
        if (host == null) {
            state = PlayUpdateState.FAILED
            return
        }
        state = PlayUpdateState.CHECKING
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when (
                    resolvePlayUpdateDecision(
                        updateAvailability = info.updateAvailability(),
                        installStatus = info.installStatus(),
                        flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                    )
                ) {
                    PlayUpdateDecision.DOWNLOADED -> {
                        state = PlayUpdateState.DOWNLOADED
                    }
                    PlayUpdateDecision.START_FLEXIBLE -> {
                        state = PlayUpdateState.DOWNLOADING
                        val started = manager.startUpdateFlowForResult(
                            info,
                            updateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                        )
                        if (!started) state = PlayUpdateState.FAILED
                    }
                    PlayUpdateDecision.CURRENT -> state = PlayUpdateState.CURRENT
                    PlayUpdateDecision.UNAVAILABLE -> state = PlayUpdateState.FAILED
                }
            }
            .addOnFailureListener { state = PlayUpdateState.FAILED }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_play_updates_title),
            trailing = {
                Caption(
                    stringResource(
                        R.string.setup_updates_installed,
                        BuildConfig.VERSION_NAME,
                    )
                )
            },
        )
        Caption(
            stringResource(
                when (state) {
                    PlayUpdateState.IDLE -> R.string.setup_play_updates_body
                    PlayUpdateState.CHECKING -> R.string.setup_play_updates_checking
                    PlayUpdateState.DOWNLOADING -> R.string.setup_play_updates_downloading
                    PlayUpdateState.DOWNLOADED -> R.string.setup_play_updates_downloaded
                    PlayUpdateState.CURRENT -> R.string.setup_updates_current
                    PlayUpdateState.CANCELED -> R.string.setup_play_updates_canceled
                    PlayUpdateState.FAILED -> R.string.setup_play_updates_failed
                }
            )
        )
        FilledTonalButton(
            onClick = {
                if (state == PlayUpdateState.DOWNLOADED) {
                    manager.completeUpdate().addOnFailureListener {
                        state = PlayUpdateState.FAILED
                    }
                } else {
                    checkForUpdate()
                }
            },
            enabled = state != PlayUpdateState.CHECKING && state != PlayUpdateState.DOWNLOADING,
        ) {
            ButtonLabel(
                stringResource(
                    when (state) {
                        PlayUpdateState.DOWNLOADED -> R.string.setup_play_updates_restart
                        PlayUpdateState.CHECKING -> R.string.setup_play_updates_checking
                        PlayUpdateState.DOWNLOADING -> R.string.setup_play_updates_downloading_button
                        else -> R.string.setup_play_updates_check
                    }
                )
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
