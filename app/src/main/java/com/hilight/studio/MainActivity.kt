package com.hilight.studio

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val store = Store.get(this)
        setContent {
            val dynamic by store.dynamicColor.collectAsStateWithLifecycle()
            HiLightTheme(dynamicColor = dynamic) {
                App(store)
            }
        }
    }

    /** Without this the app's own notifications are dropped, including the Setup self test. */
    private fun requestNotificationPermissionIfNeeded() {
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Store.get(this).apply {
            syncForegroundWatcher()
            refreshStatus()
        }
    }

    /** A test the user started by hand must not outlive the screen they started it from. */
    override fun onStop() {
        super.onStop()
        Store.get(this).stopPreview()
    }
}

private enum class Tab(@StringRes val labelRes: Int, val icon: ImageVector) {
    LIVE(R.string.tab_live, Icons.Rounded.Lightbulb),
    AMBIENT(R.string.tab_style, Icons.Rounded.Tune),
    APPS(R.string.tab_apps, Icons.Rounded.Apps),
    SETUP(R.string.tab_setup, Icons.Rounded.DisplaySettings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(store: Store) {
    // saved, so a rotation or a recreated activity does not drop the user back on Live
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[tabIndex.coerceIn(0, Tab.entries.lastIndex)]
    val status by store.status.collectAsStateWithLifecycle()
    val active by store.activeTransport.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Tied to the lifecycle, not just the composition: a plain LaunchedEffect keeps its coroutine
    // running once the activity stops, so this polled the helper over binder and file I/O every 1.5s
    // in the background, for a screen nobody was looking at.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                store.refreshStatus()
                delay(1500)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // single-line bar: the hero already carries the visual weight
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.hilight_logo),
                            contentDescription = "HiLight Studio logo",
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("HiLight", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    val rendererConnected = store.isRendererConnectedForUi(status)
                    LivePill(
                        text = if (rendererConnected) {
                            stringResource(
                                R.string.main_connected_pill,
                                status.ledCount,
                                stringResource(active.labelRes),
                            )
                        } else {
                            stringResource(R.string.main_not_connected)
                        },
                        ok = rendererConnected,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (tab != t) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            tabIndex = t.ordinal
                        },
                        icon = { Icon(t.icon, contentDescription = stringResource(t.labelRes)) },
                        label = { Text(stringResource(t.labelRes)) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { pad ->
        // tabs slide in the direction of travel, like the system's pagers
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(320)) { w -> dir * w / 8 } + fadeIn(tween(220)))
                    .togetherWith(
                        slideOutHorizontally(tween(320)) { w -> -dir * w / 8 } + fadeOut(tween(160))
                    )
            },
            label = "tab",
            modifier = Modifier.padding(pad),
        ) { current ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (current) {
                    Tab.LIVE -> LiveScreen(store)
                    Tab.AMBIENT -> AmbientScreen(store)
                    Tab.APPS -> AppRulesScreen(store)
                    Tab.SETUP -> SetupScreen(store)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
