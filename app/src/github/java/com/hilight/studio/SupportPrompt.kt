package com.hilight.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** GitHub builds stay billing-free and never interrupt pattern selection. */
internal class SupportPromptState {
    fun onPatternSelected(pattern: Pattern, apply: () -> Unit) = apply()

    @Composable
    fun Content(showCard: Boolean = true) = Unit
}

@Composable
internal fun rememberSupportPromptState(): SupportPromptState = remember { SupportPromptState() }
