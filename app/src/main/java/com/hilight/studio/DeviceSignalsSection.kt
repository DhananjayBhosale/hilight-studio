package com.hilight.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun DeviceSignalsSection(store: Store) {
    val settings by store.deviceSignals.settings.collectAsStateWithLifecycle()
    SectionTitle(stringResource(R.string.device_signals_title))
    PixelCard {
        Text(stringResource(R.string.device_signals_description), style = MaterialTheme.typography.bodySmall)
        ToggleRow(stringResource(R.string.device_signals_charging), settings.chargingEnabled) {
            store.deviceSignals.updateSettings { s -> s.copy(chargingEnabled = it) }
        }
        if (settings.chargingEnabled) {
            Text(stringResource(R.string.device_signals_charging_description), style = MaterialTheme.typography.bodySmall)
            ColorPicker(settings.chargingColor, { color -> store.deviceSignals.updateSettings { it.copy(chargingColor = color) } }, stringResource(R.string.device_signals_charging_color))
            ColorPicker(settings.chargedColor, { color -> store.deviceSignals.updateSettings { it.copy(chargedColor = color) } }, stringResource(R.string.device_signals_charged_color))
            Text(stringResource(R.string.device_signals_full_percent, settings.fullPercent))
            Slider(value = settings.fullPercent.toFloat(), onValueChange = { percent ->
                store.deviceSignals.updateSettings { it.copy(fullPercent = percent.roundToInt()) }
            }, valueRange = 1f..100f, steps = 98)
        }
        ToggleRow(stringResource(R.string.device_signals_dnd), settings.dndEnabled) {
            store.deviceSignals.updateSettings { s -> s.copy(dndEnabled = it) }
        }
        if (settings.dndEnabled) {
            Text(stringResource(R.string.device_signals_dnd_description), style = MaterialTheme.typography.bodySmall)
            ColorPicker(settings.dndColor, { color -> store.deviceSignals.updateSettings { it.copy(dndColor = color) } })
        }
        ToggleRow(stringResource(R.string.device_signals_calls), settings.callsEnabled) {
            store.deviceSignals.updateSettings { s -> s.copy(callsEnabled = it) }
        }
        if (settings.callsEnabled) {
            Text(stringResource(R.string.device_signals_calls_description), style = MaterialTheme.typography.bodySmall)
            ColorPicker(settings.callColor, { color -> store.deviceSignals.updateSettings { it.copy(callColor = color) } })
        }
    }
}
