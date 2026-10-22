package com.denebapps.patrimonio.ui.screens.lock

import androidx.compose.runtime.Composable

/** A browser has no app switcher snapshot to cover. */
@Composable
actual fun rememberAppSwitcherCover(enabled: Boolean): Boolean = false
