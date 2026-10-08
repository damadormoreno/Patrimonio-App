package com.denebapps.patrimonio.ui.screens.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** iOS takes the app switcher snapshot as the app leaves: cover it as soon as it stops being active. */
@Composable
actual fun rememberAppSwitcherCover(enabled: Boolean): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return enabled && !state.isAtLeast(Lifecycle.State.RESUMED)
}
