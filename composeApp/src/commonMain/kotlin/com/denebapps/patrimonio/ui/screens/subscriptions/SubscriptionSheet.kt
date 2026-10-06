package com.denebapps.patrimonio.ui.screens.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.ui.components.FormAmountField
import com.denebapps.patrimonio.ui.components.FormDateField
import com.denebapps.patrimonio.ui.components.FormOptionRow
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.FormSheetGrabber
import com.denebapps.patrimonio.ui.components.FormSheetTopBar
import com.denebapps.patrimonio.ui.components.FormTextField
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Create ([subscriptionId] null) or edit one subscription, as a full pushed destination like the
 *  other sheets. Edit mode adds the active toggle and a delete action. */
@Composable
fun SubscriptionSheet(
    subscriptionId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionSheetViewModel = koinViewModel(parameters = { parametersOf(subscriptionId) }),
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = colors.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        FormSheetGrabber()
        FormSheetTopBar(
            title = if (state.isEdit) "Editar suscripción" else "Nueva suscripción",
            canSave = state.canSave,
            onCancel = onNavigateBack,
            onSave = viewModel::onSave,
        )
        if (state.loaded) {
            SubscriptionForm(state = state, viewModel = viewModel, onDelete = { confirmDelete = true })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Borrar suscripción") },
            text = { Text("Se borrará \"${state.name.trim()}\". Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.onDelete()
                }) { Text("Borrar", color = colors.expense) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun ColumnScope.SubscriptionForm(
    state: SubscriptionSheetUiState,
    viewModel: SubscriptionSheetViewModel,
    onDelete: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        FormSectionLabel("Nombre")
        FormTextField(value = state.name, placeholder = "Ej: Netflix", onValueChange = viewModel::onNameChange)
        Spacer(Modifier.height(22.dp))

        FormSectionLabel("Importe de cada cargo")
        FormAmountField(
            amountText = state.amountText,
            currency = state.currency,
            onAmountChange = viewModel::onAmountChange,
            onCurrencyChange = viewModel::onCurrencyChange,
        )
        Spacer(Modifier.height(22.dp))

        FormSectionLabel("Periodicidad")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BillingCycle.entries.forEach { cycle ->
                FormOptionRow(
                    label = cycleLabel(cycle),
                    selected = cycle == state.cycle,
                    onClick = { viewModel.onCycleChange(cycle) },
                )
            }
        }
        Spacer(Modifier.height(22.dp))

        FormSectionLabel("Primer cargo")
        FormDateField(date = state.firstChargeDate, onDateChange = viewModel::onFirstChargeDateChange)
        Text(
            text = "Los siguientes cargos se calculan a partir de esta fecha.",
            color = colors.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(22.dp))

        if (state.assetOptions.isNotEmpty()) {
            FormSectionLabel("Se paga desde (opcional)")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormOptionRow(
                    label = "Sin indicar",
                    selected = state.paidFromAssetId == null,
                    onClick = { viewModel.onPaidFromChange(null) },
                )
                state.assetOptions.forEach { option ->
                    FormOptionRow(
                        label = option.name,
                        selected = option.id == state.paidFromAssetId,
                        onClick = { viewModel.onPaidFromChange(option.id) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        if (state.isEdit) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Activa", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Las pausadas no cuentan en los totales", color = colors.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = state.active,
                    onCheckedChange = viewModel::onActiveChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.brand),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Borrar suscripción",
                color = colors.expense,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClick = onDelete)
                    .padding(8.dp),
            )
        }

        state.errorMessage?.let {
            Text(text = it, color = colors.expense, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
