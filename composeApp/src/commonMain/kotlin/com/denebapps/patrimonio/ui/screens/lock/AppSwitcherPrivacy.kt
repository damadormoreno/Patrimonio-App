package com.denebapps.patrimonio.ui.screens.lock

import androidx.compose.runtime.Composable

/**
 * While [enabled] (the lock is on), keeps the app's content out of the system app switcher. Returns true
 * when the caller must cover the content itself right now (iOS, while the app is not active); Android
 * 13+ hides the recents thumbnail on its own and returns false.
 */
@Composable
expect fun rememberAppSwitcherCover(enabled: Boolean): Boolean
