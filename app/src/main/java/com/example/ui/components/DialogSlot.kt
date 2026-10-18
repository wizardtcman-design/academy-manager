package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Keeps a single "currently open" dialog slot for a screen, so an action item
 * that lives in one place (for example the Settings hub) can open a dialog that
 * is hosted somewhere else on the same screen.
 */
class DialogSlot {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    fun open(builder: @Composable () -> Unit) {
        content = builder
    }

    fun close() {
        content = null
    }
}

@Composable
fun rememberDialogSlot(): DialogSlot = remember { DialogSlot() }

/** Renders whatever is currently open in this slot. */
@Composable
fun HostDialogSlot(slot: DialogSlot) {
    val latest by rememberUpdatedState(slot.content)
    latest?.invoke()
}
