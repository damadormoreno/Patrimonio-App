package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.AppViewModel
import com.denebapps.patrimonio.domain.time.currentMonthFlow
import com.denebapps.patrimonio.ui.screens.account.AccountViewModel
import com.denebapps.patrimonio.ui.screens.history.NetWorthHistoryViewModel
import com.denebapps.patrimonio.ui.screens.importing.ImportMyInvestorViewModel
import com.denebapps.patrimonio.ui.screens.lock.LockViewModel
import com.denebapps.patrimonio.ui.screens.patrimonio.AddPatrimonioSheetViewModel
import com.denebapps.patrimonio.ui.screens.patrimonio.GruposViewModel
import com.denebapps.patrimonio.ui.screens.patrimonio.PatrimonioViewModel
import com.denebapps.patrimonio.ui.screens.perfil.ProfileViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.AddHoldingViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.CarteraViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.HoldingDetailViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.InstrumentPreviewViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.MovementFormViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.PortfolioDetailViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.PositionDetailViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.PositionLotsViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.PositionMovementsViewModel
import com.denebapps.patrimonio.ui.screens.portfolio.TransferFormViewModel
import com.denebapps.patrimonio.ui.screens.savings.SavingsGoalsViewModel
import com.denebapps.patrimonio.ui.screens.settings.AccountTypesViewModel
import com.denebapps.patrimonio.ui.screens.settings.BackupViewModel
import com.denebapps.patrimonio.ui.screens.settings.SecurityViewModel
import com.denebapps.patrimonio.ui.screens.settings.SettingsViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Presentation-layer Koin module: one `viewModel { }` definition per screen ViewModel, resolved via
 * `koinViewModel()` in composables. Depends on [dataModule]'s repository + `Clock`/`() -> TimeZone`
 * bindings.
 *
 * [AddPatrimonioSheetViewModel] takes the `AddPatrimonio.isLiability`/`groupId`/`itemId` nav args via Koin's
 * parameter-injection lambda (`parametersOf(isLiability, groupId, itemId)`). [SavingsGoalsViewModel] backs
 * three destinations (the Metas de ahorro section, `NewGoal`, `GoalAllocate`): the first two resolve
 * it with empty params, while `GoalAllocateSheet` passes `parametersOf(goalId, withdraw)`. [HoldingDetailViewModel]
 * takes the `HoldingDetail.holdingId` nav arg as `parametersOf(holdingId)`, and so does [PositionDetailViewModel] with
 * `PositionDetail.holdingId`, as do [PositionMovementsViewModel] and [PositionLotsViewModel]; [InstrumentPreviewViewModel]
 * takes the `InstrumentCandidate` it previews.
 * [MovementFormViewModel] takes `parametersOf(holdingId, movementId, openingBalance)` and [TransferFormViewModel]
 * `parametersOf(holdingId, transferId)`, both ids after the first being null for a new movement or transfer.
 */
val presentationModule = module {
    viewModel { AppViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
    viewModel { SecurityViewModel(get(), get()) }
    viewModel { AccountTypesViewModel(get(), get(), get()) }
    viewModel { LockViewModel(get(), get(), get(), get()) }
    viewModel { BackupViewModel(get(), get(), get()) }
    viewModel { ProfileViewModel(get(), get()) }
    viewModel { AccountViewModel(get(), get(), get(), get()) }
    viewModel {
        NetWorthHistoryViewModel(
            assetRepository = get(),
            liabilityRepository = get(),
            netWorthRepository = get(),
            fxRepository = get(),
            monthFlow = currentMonthFlow(get<Clock>(), get<() -> TimeZone>()),
        )
    }
    viewModel {
        val clock = get<Clock>()
        val zoneProvider = get<() -> TimeZone>()
        PatrimonioViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            clock,
            zoneProvider,
            currentMonthFlow(clock, zoneProvider),
        )
    }
    viewModel { params ->
        AddPatrimonioSheetViewModel(
            assetRepository = get(),
            liabilityRepository = get(),
            fxRepository = get(),
            savingsGoalRepository = get(),
            accountGroupRepository = get(),
            accountTypeRepository = get(),
            // By position: groupId and itemId are both nullable Strings.
            initialIsLiability = params[0],
            initialGroupId = params[1],
            editingItemId = params[2],
        )
    }
    viewModel { params ->
        GruposViewModel(
            assetRepository = get(),
            accountGroupRepository = get(),
            savingsGoalRepository = get(),
            fxRepository = get(),
            accountTypeRepository = get(),
            editingGroupId = params.getOrNull(),
        )
    }
    viewModel { params ->
        SavingsGoalsViewModel(
            savingsGoalRepository = get(),
            assetRepository = get(),
            accountGroupRepository = get(),
            fxRepository = get(),
            clock = get(),
            zoneProvider = get(),
            initialGoalId = params.getOrNull(),
            initialWithdraw = params.getOrNull() ?: false,
        )
    }
    viewModel { CarteraViewModel(get(), get(), get(), get(), get(), get<() -> TimeZone>()) }
    viewModel { AddHoldingViewModel(get(), get(), get(), get(), get(), get(), get(), get<() -> TimeZone>()) }
    viewModel {
        ImportMyInvestorViewModel(
            quoteSource = get(),
            portfolioRepository = get(),
            portfolios = get(),
            preferences = get(),
            movementRepository = get(),
            clock = get(),
        )
    }
    viewModel { params -> HoldingDetailViewModel(params.get(), get(), get(), get(), get(), get<() -> TimeZone>()) }
    viewModel { params ->
        MovementFormViewModel(
            holdingId = params[0],
            // By position: the movement is null for a new one.
            movementId = params[1],
            openingBalance = params[2],
            portfolioRepository = get(),
            movementRepository = get(),
            fxHistoryRepository = get(),
            clock = get(),
            zoneProvider = get<() -> TimeZone>(),
        )
    }
    viewModel { params ->
        TransferFormViewModel(
            holdingId = params[0],
            // By position: the transfer is null for a new one.
            transferId = params[1],
            portfolioRepository = get(),
            portfolios = get(),
            movementRepository = get(),
            quoteSource = get(),
            clock = get(),
            zoneProvider = get<() -> TimeZone>(),
        )
    }
    viewModel { params ->
        PortfolioDetailViewModel(
            portfolioId = params.getOrNull(),
            portfolios = get(),
            allocationTargets = get(),
            portfolioRepository = get(),
            movementRepository = get(),
            fxRepository = get(),
            fxHistoryRepository = get(),
            historyRepository = get(),
            clock = get(),
            zoneProvider = get<() -> TimeZone>(),
        )
    }
    viewModel { params ->
        InstrumentPreviewViewModel(
            candidate = params.get(),
            previewLoader = get(),
            seriesLoader = get(),
            portfolios = get(),
            preferences = get(),
            clock = get(),
            zoneProvider = get<() -> TimeZone>(),
        )
    }
    viewModel { params ->
        PositionDetailViewModel(
            holdingId = params.get(),
            portfolioRepository = get(),
            portfolios = get(),
            movementRepository = get(),
            fxRepository = get(),
            fxHistoryRepository = get(),
            historyRepository = get(),
            clock = get(),
            zoneProvider = get<() -> TimeZone>(),
        )
    }
    viewModel { params ->
        PositionMovementsViewModel(
            holdingId = params.get(),
            portfolioRepository = get(),
            movementRepository = get(),
        )
    }
    viewModel { params ->
        PositionLotsViewModel(
            holdingId = params.get(),
            portfolioRepository = get(),
            movementRepository = get(),
        )
    }
}
