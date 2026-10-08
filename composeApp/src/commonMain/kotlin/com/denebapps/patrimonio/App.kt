package com.denebapps.patrimonio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.denebapps.patrimonio.domain.repository.AppLockState
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.ui.navigation.RootNavHost
import com.denebapps.patrimonio.ui.screens.lock.LockCover
import com.denebapps.patrimonio.ui.screens.lock.LockScreen
import com.denebapps.patrimonio.ui.screens.lock.rememberAppSwitcherCover
import com.denebapps.patrimonio.ui.theme.AppTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * App root: [RootNavHost] (the three-zone root `NavHost`, starting at `Main`) under the app lock. While locked
 * the app stays composed (its navigation is kept) but hidden from sight and from accessibility services. The
 * lock screen goes in its own dialog window so it also covers any sheet or dialog left open, and the back
 * button cannot get past it.
 */
@Composable
fun App() {
    val viewModel: AppViewModel = koinViewModel()
    val themeMode by viewModel.themeMode.collectAsState()
    val lockState by viewModel.lockState.collectAsState()
    val lockSettings by viewModel.lockSettings.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val coverForSwitcher = rememberAppSwitcherCover(enabled = lockSettings.pinSet)
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    AppTheme(dark = dark) {
        Box(Modifier.fillMaxSize()) {
            val locked = lockState != AppLockState.Unlocked
            Box(if (locked) Modifier.clearAndSetSemantics { } else Modifier) {
                RootNavHost()
            }
            when (lockState) {
                AppLockState.Checking -> LockCover()
                is AppLockState.Locked -> {
                    LockCover()
                    Dialog(
                        onDismissRequest = {},
                        properties = DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            usePlatformDefaultWidth = false,
                        ),
                    ) { LockScreen() }
                }
                AppLockState.Unlocked -> Unit
            }
            if (coverForSwitcher) LockCover()
        }
    }
}
