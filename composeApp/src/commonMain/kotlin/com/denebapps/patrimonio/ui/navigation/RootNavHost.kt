package com.denebapps.patrimonio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Root `NavHost` over the three top-level zones — [AuthZone], [LockZone], [MainZone].
 * Starts at [MainZone] (no gating in this change: `Auth`/`Lock` are structurally
 * defined for a future lock/login change but unreachable here).
 */
@Composable
fun RootNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = MainZone) {
        composable<AuthZone> { PlaceholderScreen(title = "Auth") }
        composable<LockZone> { PlaceholderScreen(title = "Lock") }
        composable<MainZone> { MainScaffold() }
    }
}
