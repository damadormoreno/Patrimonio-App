package com.denebapps.patrimonio.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.denebapps.patrimonio.ui.components.Fab
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.TabBar
import com.denebapps.patrimonio.ui.components.TabItem
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.screens.history.NetWorthHistoryScreen
import com.denebapps.patrimonio.ui.screens.patrimonio.AddPatrimonioSheet
import com.denebapps.patrimonio.ui.screens.patrimonio.GruposSheet
import com.denebapps.patrimonio.ui.screens.patrimonio.NuevoGrupoSheet
import com.denebapps.patrimonio.ui.screens.patrimonio.PatrimonioScreen
import com.denebapps.patrimonio.ui.screens.patrimonio.PatrimonioView
import com.denebapps.patrimonio.ui.screens.perfil.PerfilScreen
import com.denebapps.patrimonio.ui.screens.savings.GoalAllocateSheet
import com.denebapps.patrimonio.ui.screens.savings.NewGoalSheet
import com.denebapps.patrimonio.ui.screens.settings.SettingsScreen
import com.denebapps.patrimonio.ui.screens.subscriptions.SubscriptionSheet
import com.denebapps.patrimonio.ui.screens.subscriptions.SubscriptionsScreen

/** Maps a [TabKey] to its route object for `mainNavController.navigate(...)` calls. */
private fun routeForTab(tab: TabKey): Any = when (tab) {
    TabKey.PATRIMONIO -> Patrimonio
    TabKey.SUBSCRIPTIONS -> Subscriptions
}

/** The start tab doubles as the `popUpTo` anchor for tab switches. */
private val StartTab = TabKey.PATRIMONIO

/**
 * `Main` zone content: a [Scaffold] whose `bottomBar` is [TabBar] (only once there is more than
 * one tab, see [TabMapping.tabBarVisible]) and whose `floatingActionButton` is [Fab], wrapping an
 * **inner** `NavHost` (`mainNavController`) that hosts the tabs plus pushed Settings/Profile/sheets.
 * Tab switches use `launchSingleTop`/`popUpTo(start){saveState}`/`restoreState` (no back-stack
 * growth); Settings/Profile/sheets are pushed (back returns to the prior tab).
 * [TabMapping.tabForRoute] drives the highlighted tab and FAB visibility from the current back
 * stack entry.
 */
@Composable
fun MainScaffold() {
    val mainNavController = rememberNavController()
    val currentBackStackEntry by mainNavController.currentBackStackEntryAsState()
    val routeName = currentBackStackEntry?.destination?.route
    // Hoisted from PatrimonioScreen's onViewChange — the FAB lives outside
    // that composable's scope but needs the active Activos/Pasivos toggle to route its tap.
    var patrimonioIsLiability by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { MainHeaderAffordances(routeName = routeName, mainNavController = mainNavController) },
        bottomBar = {
            if (TabMapping.tabBarVisible) {
                TabBar(
                    tabs = TabMapping.tabs.map { tab ->
                        TabItem(tab.id, tab.label, AppIcons.get(tab.iconName), tab.iconName)
                    },
                    selected = TabMapping.tabForRoute(routeName)?.id.orEmpty(),
                    onSelect = { id ->
                        TabKey.byId(id)?.let { tab ->
                            if (tab == StartTab) {
                                // The start tab is the popUpTo anchor: navigate() with
                                // launchSingleTop is elided when it is already in the stack (not
                                // top), so pushed routes would stay visible. Pop back to it instead.
                                mainNavController.popBackStack(routeForTab(StartTab), inclusive = false)
                            } else {
                                mainNavController.navigate(routeForTab(tab)) {
                                    launchSingleTop = true
                                    popUpTo(routeForTab(StartTab)) { saveState = true }
                                    restoreState = true
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (TabMapping.fabVisible(routeName)) {
                Fab(
                    onClick = {
                        when (TabMapping.tabForRoute(routeName)) {
                            TabKey.PATRIMONIO -> mainNavController.navigate(
                                AddPatrimonio(isLiability = patrimonioIsLiability, groupId = null),
                            )
                            TabKey.SUBSCRIPTIONS -> mainNavController.navigate(EditSubscription())
                            null -> Unit
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = mainNavController,
            startDestination = routeForTab(StartTab),
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<Patrimonio> {
                PatrimonioScreen(
                    onOpenGroups = { mainNavController.navigate(Grupos) },
                    onOpenHistory = { mainNavController.navigate(NetWorthHistory) },
                    onAddItem = { view, groupId ->
                        mainNavController.navigate(
                            AddPatrimonio(isLiability = view == PatrimonioView.PASIVOS, groupId = groupId),
                        )
                    },
                    onViewChange = { patrimonioIsLiability = it == PatrimonioView.PASIVOS },
                    onNewGoal = { mainNavController.navigate(NewGoal) },
                    onGoalTap = { goalId -> mainNavController.navigate(GoalAllocate(goalId = goalId)) },
                )
            }
            composable<Settings> {
                SettingsScreen(onOpenProfile = { mainNavController.navigate(Profile) })
            }
            composable<Profile> { PerfilScreen(onBack = { mainNavController.popBackStack() }) }
            composable<NetWorthHistory> { NetWorthHistoryScreen(onBack = { mainNavController.popBackStack() }) }
            composable<AddPatrimonio> { backStackEntry ->
                val route = backStackEntry.toRoute<AddPatrimonio>()
                AddPatrimonioSheet(
                    isLiability = route.isLiability,
                    groupId = route.groupId,
                    onNavigateBack = { mainNavController.popBackStack() },
                )
            }
            composable<Grupos> {
                GruposSheet(
                    onNavigateBack = { mainNavController.popBackStack() },
                    onNewGroup = { mainNavController.navigate(NuevoGrupo) },
                    onNewAsset = { mainNavController.navigate(AddPatrimonio(isLiability = false, groupId = null)) },
                )
            }
            composable<NuevoGrupo> {
                NuevoGrupoSheet(onNavigateBack = { mainNavController.popBackStack() })
            }
            composable<NewGoal> {
                NewGoalSheet(onNavigateBack = { mainNavController.popBackStack() })
            }
            composable<GoalAllocate> { backStackEntry ->
                val route = backStackEntry.toRoute<GoalAllocate>()
                GoalAllocateSheet(
                    goalId = route.goalId,
                    withdraw = route.withdraw,
                    onNavigateBack = { mainNavController.popBackStack() },
                )
            }
            composable<Subscriptions> {
                SubscriptionsScreen(
                    onAdd = { mainNavController.navigate(EditSubscription()) },
                    onOpen = { id -> mainNavController.navigate(EditSubscription(subscriptionId = id)) },
                )
            }
            composable<EditSubscription> { backStackEntry ->
                val route = backStackEntry.toRoute<EditSubscription>()
                SubscriptionSheet(
                    subscriptionId = route.subscriptionId,
                    onNavigateBack = { mainNavController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Header row hosting the non-tab navigation affordances: a settings cog (visible on any tab,
 * pushes [Settings]). There is intentionally NO Perfil/user affordance here — Perfil is reached
 * from the Settings profile card instead.
 */
@Composable
private fun MainHeaderAffordances(routeName: String?, mainNavController: androidx.navigation.NavHostController) {
    val onTab = TabMapping.fabVisible(routeName)
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (onTab) {
            HeaderIconBtn(icon = AppIcons.cog, onClick = { mainNavController.navigate(Settings) }, label = "Settings")
        }
    }
}
