package com.denebapps.patrimonio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.ui.navigation.RootNavHost
import com.denebapps.patrimonio.ui.theme.AppTheme
import org.koin.compose.viewmodel.koinViewModel

/** App root: boots straight into [RootNavHost] (the three-zone root `NavHost`, starting at `Main`). */
@Composable
fun App() {
    val viewModel: AppViewModel = koinViewModel()
    val themeMode by viewModel.themeMode.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    AppTheme(dark = dark) {
        RootNavHost()
    }
}
