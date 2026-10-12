package com.denebapps.patrimonio.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.ui.components.Fab
import com.denebapps.patrimonio.ui.components.TabBar
import com.denebapps.patrimonio.ui.components.TabItem
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.screens.account.AccountScreen
import com.denebapps.patrimonio.ui.screens.history.NetWorthHistoryScreen
import com.denebapps.patrimonio.ui.screens.importing.ImportMyInvestorScreen
import com.denebapps.patrimonio.ui.screens.patrimonio.AddPatrimonioSheet
import com.denebapps.patrimonio.ui.screens.patrimonio.GruposSheet
import com.denebapps.patrimonio.ui.screens.patrimonio.NuevoGrupoSheet
import com.denebapps.patrimonio.ui.screens.patrimonio.PatrimonioScreen
import com.denebapps.patrimonio.ui.screens.patrimonio.PatrimonioView
import com.denebapps.patrimonio.ui.screens.perfil.PerfilScreen
import com.denebapps.patrimonio.ui.screens.perfil.ProfileAvatar
import com.denebapps.patrimonio.ui.screens.perfil.ProfileViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.AddHoldingSheet
import com.denebapps.patrimonio.ui.screens.portfolio.AddHoldingViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.CarteraScreen
import com.denebapps.patrimonio.ui.screens.portfolio.HoldingDetailSheet
import com.denebapps.patrimonio.ui.screens.portfolio.InstrumentPreviewScreen
import com.denebapps.patrimonio.ui.screens.portfolio.MovementFormSheet
import com.denebapps.patrimonio.ui.screens.portfolio.PortfolioDetailScreen
import com.denebapps.patrimonio.ui.screens.portfolio.PositionDetailScreen
import com.denebapps.patrimonio.ui.screens.portfolio.PositionLotsScreen
import com.denebapps.patrimonio.ui.screens.portfolio.PositionMovementsScreen
import com.denebapps.patrimonio.ui.screens.portfolio.TransferFormSheet
import com.denebapps.patrimonio.ui.screens.savings.GoalAllocateSheet
import com.denebapps.patrimonio.ui.screens.savings.NewGoalSheet
import com.denebapps.patrimonio.ui.screens.settings.SettingsScreen
import org.koin.compose.viewmodel.koinViewModel

/** Maps a [TabKey] to its route object for `mainNavController.navigate(...)` calls. */
private fun routeForTab(tab: TabKey): Any = when (tab) {
    TabKey.PATRIMONIO -> Patrimonio
    TabKey.CARTERA -> Cartera
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

    // Shared by the tab bar and by in-screen shortcuts (the Patrimonio "Cartera" card).
    fun selectTab(tab: TabKey) {
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

    Scaffold(
        topBar = { MainHeaderAffordances(routeName = routeName, mainNavController = mainNavController) },
        bottomBar = {
            if (TabMapping.tabBarVisible) {
                TabBar(
                    tabs = TabMapping.tabs.map { tab ->
                        TabItem(tab.id, tab.label, AppIcons.get(tab.iconName), tab.iconName)
                    },
                    selected = TabMapping.tabForRoute(routeName)?.id.orEmpty(),
                    onSelect = { id -> TabKey.byId(id)?.let(::selectTab) },
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
                            TabKey.CARTERA -> mainNavController.navigate(AddHolding)
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
                    onEditItem = { view, itemId ->
                        mainNavController.navigate(
                            AddPatrimonio(isLiability = view == PatrimonioView.PASIVOS, itemId = itemId),
                        )
                    },
                    onViewChange = { patrimonioIsLiability = it == PatrimonioView.PASIVOS },
                    onNewGoal = { mainNavController.navigate(NewGoal()) },
                    onGoalTap = { goalId -> mainNavController.navigate(GoalAllocate(goalId = goalId)) },
                    onOpenCartera = { selectTab(TabKey.CARTERA) },
                )
            }
            composable<Cartera> {
                CarteraScreen(
                    onAddHolding = { mainNavController.navigate(AddHolding) },
                    onImport = { mainNavController.navigate(ImportMyInvestor) },
                    onOpenHolding = { holdingId -> mainNavController.navigate(PositionDetail(holdingId)) },
                    onOpenPortfolio = { portfolioId -> mainNavController.navigate(PortfolioDetail(portfolioId)) },
                )
            }
            composable<Settings> {
                SettingsScreen(
                    onOpenProfile = { mainNavController.navigate(Profile) },
                    onOpenAccount = { mainNavController.navigate(Account) },
                )
            }
            composable<Profile> { PerfilScreen(onBack = { mainNavController.popBackStack() }) }
            composable<Account> { AccountScreen(onBack = { mainNavController.popBackStack() }) }
            composable<NetWorthHistory> { NetWorthHistoryScreen(onBack = { mainNavController.popBackStack() }) }
            composable<AddPatrimonio> { backStackEntry ->
                val route = backStackEntry.toRoute<AddPatrimonio>()
                AddPatrimonioSheet(
                    isLiability = route.isLiability,
                    groupId = route.groupId,
                    itemId = route.itemId,
                    onNavigateBack = { mainNavController.popBackStack() },
                )
            }
            composable<AddHolding> {
                AddHoldingSheet(
                    onNavigateBack = { mainNavController.popBackStack() },
                    onOpenPreview = { ref -> mainNavController.navigate(InstrumentPreview.of(ref)) },
                )
            }
            composable<ImportMyInvestor> { ImportMyInvestorScreen(onBack = { mainNavController.popBackStack() }) }
            composable<InstrumentPreview> { backStackEntry ->
                InstrumentPreviewDestination(
                    ref = backStackEntry.toRoute<InstrumentPreview>().ref,
                    // The add form below this screen keeps the search; the candidate is read from there.
                    addHoldingEntry = remember(backStackEntry) { mainNavController.getBackStackEntry<AddHolding>() },
                    onBack = { mainNavController.popBackStack() },
                )
            }
            composable<PositionDetail> { backStackEntry ->
                val holdingId = backStackEntry.toRoute<PositionDetail>().holdingId
                PositionDetailScreen(
                    holdingId = holdingId,
                    onBack = { mainNavController.popBackStack() },
                    onEdit = { mainNavController.navigate(HoldingDetail(holdingId)) },
                    onOpenMovement = { movementId, openingBalance ->
                        mainNavController.navigate(MovementForm(holdingId, movementId, openingBalance))
                    },
                    onOpenTransfer = { transferId -> mainNavController.navigate(TransferForm(holdingId, transferId)) },
                    onOpenMovements = { mainNavController.navigate(PositionMovements(holdingId)) },
                    onOpenLots = { mainNavController.navigate(PositionLots(holdingId)) },
                )
            }
            composable<PositionMovements> { backStackEntry ->
                val holdingId = backStackEntry.toRoute<PositionMovements>().holdingId
                PositionMovementsScreen(
                    holdingId = holdingId,
                    onBack = { mainNavController.popBackStack() },
                    onOpenMovement = { movementId, openingBalance ->
                        mainNavController.navigate(MovementForm(holdingId, movementId, openingBalance))
                    },
                    onOpenTransfer = { transferId -> mainNavController.navigate(TransferForm(holdingId, transferId)) },
                )
            }
            composable<PositionLots> { backStackEntry ->
                PositionLotsScreen(
                    holdingId = backStackEntry.toRoute<PositionLots>().holdingId,
                    onBack = { mainNavController.popBackStack() },
                )
            }
            composable<MovementForm> { backStackEntry ->
                val route = backStackEntry.toRoute<MovementForm>()
                MovementFormSheet(
                    holdingId = route.holdingId,
                    movementId = route.movementId,
                    openingBalance = route.openingBalance,
                    onNavigateBack = { mainNavController.popBackStack() },
                    // The transfer takes the place of this sheet, so that closing it returns to the position.
                    onOpenTransfer = {
                        mainNavController.popBackStack()
                        mainNavController.navigate(TransferForm(route.holdingId))
                    },
                )
            }
            composable<TransferForm> { backStackEntry ->
                val route = backStackEntry.toRoute<TransferForm>()
                TransferFormSheet(
                    holdingId = route.holdingId,
                    transferId = route.transferId,
                    onNavigateBack = { mainNavController.popBackStack() },
                )
            }
            composable<PortfolioDetail> { backStackEntry ->
                PortfolioDetailScreen(
                    portfolioId = backStackEntry.toRoute<PortfolioDetail>().portfolioId,
                    onBack = { mainNavController.popBackStack() },
                )
            }
            composable<HoldingDetail> { backStackEntry ->
                HoldingDetailSheet(
                    holdingId = backStackEntry.toRoute<HoldingDetail>().holdingId,
                    onNavigateBack = { mainNavController.popBackStack() },
                )
            }
            composable<Grupos> {
                GruposSheet(
                    onNavigateBack = { mainNavController.popBackStack() },
                    onNewGroup = { mainNavController.navigate(NuevoGrupo()) },
                    onEditGroup = { groupId -> mainNavController.navigate(NuevoGrupo(groupId = groupId)) },
                    onNewAsset = { mainNavController.navigate(AddPatrimonio(isLiability = false, groupId = null)) },
                    onEditAsset = { assetId -> mainNavController.navigate(AddPatrimonio(itemId = assetId)) },
                )
            }
            composable<NuevoGrupo> { backStackEntry ->
                NuevoGrupoSheet(
                    groupId = backStackEntry.toRoute<NuevoGrupo>().groupId,
                    onNavigateBack = { mainNavController.popBackStack() },
                )
            }
            composable<NewGoal> { backStackEntry ->
                NewGoalSheet(
                    goalId = backStackEntry.toRoute<NewGoal>().goalId,
                    onNavigateBack = { mainNavController.popBackStack() },
                )
            }
            composable<GoalAllocate> { backStackEntry ->
                val route = backStackEntry.toRoute<GoalAllocate>()
                GoalAllocateSheet(
                    goalId = route.goalId,
                    withdraw = route.withdraw,
                    onNavigateBack = { mainNavController.popBackStack() },
                    onEdit = { mainNavController.navigate(NewGoal(goalId = route.goalId)) },
                )
            }
        }
    }
}

/**
 * The preview of the search result [ref]: its candidate is looked up in the add form ([addHoldingEntry], whose
 * ViewModel holds the search) and `Añadir a la cartera` hands it back to that form picked, then closes the preview.
 * A search that is gone (the form was recreated) leaves nothing to preview, so it closes.
 */
@Composable
private fun InstrumentPreviewDestination(ref: InstrumentRef, addHoldingEntry: NavBackStackEntry, onBack: () -> Unit) {
    val addHolding: AddHoldingViewModel = koinViewModel(viewModelStoreOwner = addHoldingEntry)
    val candidate = remember(ref) { addHolding.candidate(ref) }
    if (candidate == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    InstrumentPreviewScreen(
        candidate = candidate,
        onBack = onBack,
        onAdd = { portfolioId ->
            addHolding.onPick(ref, portfolioId)
            onBack()
        },
    )
}

/**
 * Header row on every tab: the profile avatar (initials, or the user icon without a name), which pushes
 * [Settings], where the profile card and the rest of the settings live.
 */
@Composable
private fun MainHeaderAffordances(
    routeName: String?,
    mainNavController: androidx.navigation.NavHostController,
    profileViewModel: ProfileViewModel = koinViewModel(),
) {
    val profile by profileViewModel.state.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (TabMapping.fabVisible(routeName)) {
            ProfileAvatar(
                initials = profile.initials,
                size = 38.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Abrir ajustes", role = Role.Button) {
                        mainNavController.navigate(Settings)
                    },
            )
        }
    }
}
