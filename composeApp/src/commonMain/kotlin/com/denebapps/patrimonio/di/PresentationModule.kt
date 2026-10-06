package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.AppViewModel
import com.denebapps.patrimonio.domain.time.currentMonthFlow
import com.denebapps.patrimonio.ui.screens.patrimonio.AddPatrimonioSheetViewModel
import com.denebapps.patrimonio.ui.screens.patrimonio.GruposViewModel
import com.denebapps.patrimonio.ui.screens.patrimonio.PatrimonioViewModel
import com.denebapps.patrimonio.ui.screens.perfil.ProfileViewModel
import com.denebapps.patrimonio.ui.screens.savings.SavingsGoalsViewModel
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
 * [AddPatrimonioSheetViewModel] takes the `AddPatrimonio.isLiability`/`groupId` nav args via Koin's
 * parameter-injection lambda (`parametersOf(isLiability, groupId)`). [SavingsGoalsViewModel] backs
 * three destinations (the Metas de ahorro section, `NewGoal`, `GoalAllocate`): the first two resolve
 * it with empty params, while `GoalAllocateSheet` passes `parametersOf(goalId, withdraw)`.
 */
val presentationModule = module {
    viewModel { AppViewModel(get()) }
    viewModel { SettingsViewModel(get(), get()) }
    viewModel { ProfileViewModel(get()) }
    viewModel {
        val clock = get<Clock>()
        val zoneProvider = get<() -> TimeZone>()
        PatrimonioViewModel(
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
            initialIsLiability = params.get(),
            initialGroupId = params.getOrNull(),
        )
    }
    viewModel { GruposViewModel(assetRepository = get(), accountGroupRepository = get(), fxRepository = get()) }
    viewModel { params ->
        SavingsGoalsViewModel(
            savingsGoalRepository = get(),
            assetRepository = get(),
            initialGoalId = params.getOrNull(),
            initialWithdraw = params.getOrNull() ?: false,
        )
    }
}
