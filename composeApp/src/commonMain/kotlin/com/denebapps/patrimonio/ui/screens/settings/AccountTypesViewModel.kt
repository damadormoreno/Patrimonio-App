package com.denebapps.patrimonio.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.domain.repository.AccountTypeRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.ui.components.TypeLook
import com.denebapps.patrimonio.ui.components.typeLook
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val ACCOUNT_TYPES_STOP_TIMEOUT_MS = 5_000L

/** One of the user's types and how many accounts use it (they move to "Otros" if it is deleted). */
data class CustomTypeRowUi(val type: CustomAccountType, val look: TypeLook, val accountCount: Int)

data class AccountTypesUiState(
    val assetTypes: List<CustomTypeRowUi> = emptyList(),
    val liabilityTypes: List<CustomTypeRowUi> = emptyList(),
)

/** Ajustes → Tipos de cuenta: the user's own types, to create, edit and delete. Built-in types are fixed. */
class AccountTypesViewModel(
    private val accountTypeRepository: AccountTypeRepository,
    assetRepository: AssetRepository,
    liabilityRepository: LiabilityRepository,
) : ViewModel() {
    val state: StateFlow<AccountTypesUiState> = combine(
        accountTypeRepository.observeAll(),
        assetRepository.observeAll(),
        liabilityRepository.observeAll(),
    ) { types, assets, liabilities ->
        val counts = (assets.map { it.group } + liabilities.map { it.group }).groupingBy { it }.eachCount()
        val rows = types.sortedBy { it.position }
            .map { CustomTypeRowUi(it, typeLook(it.id, mapOf(it.id to it)), counts[it.id] ?: 0) }
        AccountTypesUiState(
            assetTypes = rows.filter { it.type.kind == AccountKind.ASSET },
            liabilityTypes = rows.filter { it.type.kind == AccountKind.LIABILITY },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ACCOUNT_TYPES_STOP_TIMEOUT_MS), AccountTypesUiState())

    fun onCreate(kind: AccountKind, name: String, emoji: String, color: TypeColor) {
        viewModelScope.launch { accountTypeRepository.create(kind, name, emoji, color) }
    }

    fun onUpdate(id: String, name: String, emoji: String, color: TypeColor) {
        viewModelScope.launch { accountTypeRepository.update(id, name, emoji, color) }
    }

    /** Its accounts move to "Otros" / "Otras deudas". */
    fun onDelete(id: String) {
        viewModelScope.launch { accountTypeRepository.delete(id) }
    }
}
