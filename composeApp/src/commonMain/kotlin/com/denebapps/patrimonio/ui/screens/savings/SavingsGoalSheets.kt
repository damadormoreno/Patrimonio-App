package com.denebapps.patrimonio.ui.screens.savings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.components.PillTone
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Goal form for the `NewGoal` destination (spec: Create Goal Form); with [goalId] it edits that goal
 * (name, target, date and link; the currency stays). Ports the same full-
 * pushed-destination sheet chrome as [com.denebapps.patrimonio.ui.screens.patrimonio.AddPatrimonioSheet]
 * (design.md Decision: sheets as full pushed destinations). The goal can follow several accounts
 * (checkboxes, any currency: the balance is converted) or one group, never both.
 */
@Composable
fun NewGoalSheet(
    modifier: Modifier = Modifier,
    goalId: String? = null,
    onNavigateBack: () -> Unit = {},
    viewModel: SavingsGoalsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }
    LaunchedEffect(goalId) {
        goalId?.let(viewModel::onStartEditing)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = colors.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        SheetGrabber()

        SheetTopBar(
            title = if (goalId == null) "Nueva meta" else "Editar meta",
            canSave = state.canSaveNewGoal,
            onCancel = onNavigateBack,
            onSave = viewModel::onSaveNewGoal,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SheetSectionLabel("Nombre")
            SheetTextField(
                value = state.newGoalName,
                placeholder = "Ej: Fondo de emergencia",
                onValueChange = viewModel::onNewGoalNameChange,
            )
            Spacer(modifier = Modifier.height(22.dp))

            SheetSectionLabel("Importe objetivo")
            TargetAmountRow(
                amountText = state.newGoalTargetText,
                currency = state.newGoalCurrency,
                onAmountChange = { viewModel.onNewGoalTargetChange(filterAmountInputSavings(it)) },
                onCurrencyChange = viewModel::onNewGoalCurrencyChange,
                // Existing allocations are in the goal's currency, so an edit keeps it.
                currencyEditable = goalId == null,
            )
            Spacer(modifier = Modifier.height(22.dp))

            TargetDateSection(date = state.newGoalTargetDate, onDateChange = viewModel::onNewGoalDateChange)

            if (state.linkableAssets.isNotEmpty() || state.linkableGroups.isNotEmpty()) {
                LinkTargetSection(
                    assets = state.linkableAssets,
                    groups = state.linkableGroups,
                    goalCurrency = state.newGoalCurrency,
                    selectedAssetIds = state.newGoalLinkedAssetIds,
                    selectedGroupId = state.newGoalLinkedGroupId,
                    onUnlink = viewModel::onNewGoalUnlink,
                    onToggleAsset = viewModel::onNewGoalAssetToggle,
                    onSelectGroup = viewModel::onNewGoalGroupLinkChange,
                )
            }

            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage.orEmpty(),
                    color = colors.expense,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Allocate/withdraw + cancel destination for one goal (spec: Allocate and Withdraw Funds, Cancel and
 * Closed-Goal Restrictions). A closed goal (cancelled or explicitly closed) hides every mutation
 * affordance and shows only its final preserved/read-only progress (spec: "Closed goal hides
 * mutation actions"). Any goal, open or closed, can be deleted after a confirmation; the sheet then
 * closes.
 */
@Composable
fun GoalAllocateSheet(
    goalId: String,
    withdraw: Boolean,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    viewModel: SavingsGoalsViewModel = koinViewModel(parameters = { parametersOf(goalId, withdraw) }),
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current
    val goal = state.selectedGoal
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = colors.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        SheetGrabber()

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cerrar",
                color = colors.ink2,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNavigateBack,
                    )
                    .padding(6.dp),
            )
            Text(
                text = goal?.name.orEmpty(),
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).sp,
            )
            if (goal != null && !goal.closed) {
                Text(
                    text = "Editar",
                    color = colors.brand,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onEdit,
                        )
                        .padding(6.dp),
                )
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        if (goal == null) return@Column

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            GoalProgressSummary(goal = goal)

            if (goal.closed) {
                Text(
                    text = "Esta meta está cerrada. Su progreso final se conserva pero ya no admite " +
                        "nuevos movimientos.",
                    color = colors.muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else if (goal.tracksBalance) {
                Text(
                    text = "${trackedBalanceCaption(goal)}. El progreso se actualiza solo cuando cambia ese " +
                        "saldo, así que aquí no se asigna ni se retira dinero.",
                    color = colors.muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                AllocateModeSegmented(withdraw = state.withdraw, onModeChange = viewModel::onWithdrawModeChange)

                SheetSectionLabel(if (state.withdraw) "Importe a retirar" else "Importe a asignar")
                SheetAmountField(
                    amountText = state.allocateAmountText,
                    currency = goal.target.currency,
                    onAmountChange = { viewModel.onAllocateAmountChange(filterAmountInputSavings(it)) },
                )

                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        color = colors.expense,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                SubmitButton(
                    label = if (state.withdraw) "Retirar" else "Asignar",
                    enabled = state.canSubmitAllocate,
                    onClick = viewModel::onSubmitAllocate,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            if (!goal.closed) {
                Text(
                    text = "Cancelar meta",
                    color = colors.expense,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { confirmCancel = true },
                        )
                        .padding(vertical = 10.dp),
                )
            }

            if ((goal.closed || goal.tracksBalance) && state.errorMessage != null) {
                Text(
                    text = state.errorMessage.orEmpty(),
                    color = colors.expense,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Text(
                text = "Eliminar meta",
                color = colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (goal.closed) 20.dp else 4.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { confirmDelete = true },
                    )
                    .padding(vertical = 10.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (confirmCancel && goal != null) {
        val released = when {
            goal.tracksBalance -> "El saldo de ${goal.linkedTargetLabel.orEmpty()} no cambia y la"
            goal.progress > Money.ZERO ->
                "Se liberarán los ${formatSavingsAmount(goal.progress, goal.target.currency)} asignados y la"
            else -> "La"
        }
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancelar meta") },
            text = {
                Text(
                    "$released meta quedará cerrada: seguirás viéndola, pero ya no admitirá movimientos. " +
                        "Esta acción no se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    viewModel.onCancelGoal()
                }) { Text("Cancelar meta", color = colors.expense) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Volver") }
            },
        )
    }

    if (confirmDelete && goal != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar meta") },
            text = {
                Text(
                    "Se borrará \"${goal.name}\" con todo su historial de aportaciones. " +
                        "Esta acción no se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.onDeleteGoal()
                }) { Text("Eliminar", color = colors.expense) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun GoalProgressSummary(goal: SavingsGoalRowUi, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(modifier = modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatSavingsAmount(goal.progress, goal.target.currency),
                color = colors.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "de ${formatSavingsAmount(goal.target.amount, goal.target.currency)}",
                color = colors.muted,
                fontSize = 13.sp,
            )
        }
        if (goal.targetReached) {
            Text(
                text = "Meta alcanzada · ${goal.progressPct}%",
                color = colors.income,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AllocateModeSegmented(withdraw: Boolean, onModeChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 18.dp)
            .background(color = colors.bg2, shape = RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = colors.line2, shape = RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AllocateModeButton(
            label = "Asignar",
            active = !withdraw,
            activeColor = colors.income,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(false) },
        )
        AllocateModeButton(
            label = "Retirar",
            active = withdraw,
            activeColor = colors.expense,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(true) },
        )
    }
}

@Composable
private fun AllocateModeButton(
    label: String,
    active: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .background(
                color = if (active) colors.surface else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Text(
            text = label,
            color = if (active) activeColor else colors.muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SheetGrabber() {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp, bottom = 6.dp)
                .height(4.dp)
                .width(36.dp)
                .background(color = colors.line, shape = RoundedCornerShape(999.dp)),
        )
    }
}

@Composable
private fun SheetTopBar(title: String, canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Cancelar",
            color = colors.ink2,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                )
                .padding(6.dp),
        )
        Text(text = title, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Guardar",
            color = if (canSave) colors.brand else colors.muted2,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = canSave,
                    onClick = onSave,
                )
                .padding(6.dp),
        )
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    val colors = LocalAppColors.current
    Text(
        text = text.uppercase(),
        color = colors.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.06.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SheetTextField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surface2, shape = RoundedCornerShape(14.dp))
            .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, color = colors.muted, fontSize = 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.ink, fontSize = 15.sp),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TargetAmountRow(
    amountText: String,
    currency: Currency,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
    currencyEditable: Boolean = true,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surface2, shape = RoundedCornerShape(14.dp))
            .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = currencySymbolSavings(currency),
            color = colors.muted2,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
        BasicTextField(
            value = amountText,
            onValueChange = onAmountChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.ink,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.weight(1f),
        )
        if (currencyEditable) {
            SavingsCurrencyDropdown(value = currency, onChange = onCurrencyChange)
        } else {
            Text(text = currency.code, color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SheetAmountField(amountText: String, currency: Currency, onAmountChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surface2, shape = RoundedCornerShape(14.dp))
            .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = currencySymbolSavings(currency),
            color = colors.muted2,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
        BasicTextField(
            value = amountText,
            onValueChange = onAmountChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.ink,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SavingsCurrencyDropdown(value: Currency, onChange: (Currency) -> Unit) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(999.dp))
                .background(color = colors.bg2, shape = RoundedCornerShape(999.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = true },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = value.code, color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Icon(AppIcons.chevronD, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(11.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Currency.entries.forEach { code ->
                DropdownMenuItem(
                    text = { Text("${code.code} · ${currencySymbolSavings(code)}") },
                    onClick = {
                        onChange(code)
                        expanded = false
                    },
                    trailingIcon = {
                        if (code == value) {
                            Icon(AppIcons.check, contentDescription = null, tint = colors.brand)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetDateSection(date: LocalDate?, onDateChange: (LocalDate?) -> Unit) {
    val colors = LocalAppColors.current
    var pickerOpen by remember { mutableStateOf(false) }

    SheetSectionLabel("Fecha objetivo (opcional)")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surface2, shape = RoundedCornerShape(14.dp))
            .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { pickerOpen = true },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcons.calendar, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
        Text(
            text = date?.let { formatDateLongSavings(it) } ?: "Sin fecha",
            color = if (date != null) colors.ink else colors.muted,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (date != null) {
            Icon(
                imageVector = AppIcons.close,
                contentDescription = "Quitar fecha",
                tint = colors.muted,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onDateChange(null) },
                    ),
            )
        }
    }
    Spacer(modifier = Modifier.height(22.dp))

    if (pickerOpen) {
        val millisPerDay = 86_400_000L
        val initialMillis = (date ?: Clock.System.todayIn(TimeZone.currentSystemDefault())).toEpochDays() *
            millisPerDay
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            colors = DatePickerDefaults.colors(
                containerColor = colors.surface,
                titleContentColor = colors.ink,
                headlineContentColor = colors.ink,
                weekdayContentColor = colors.muted,
                subheadContentColor = colors.muted,
                selectedDayContainerColor = colors.ink,
                selectedDayContentColor = colors.bg,
                todayContentColor = colors.brand,
                todayDateBorderColor = colors.brand,
            ),
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        onDateChange(LocalDate.fromEpochDays((it / millisPerDay).toInt()))
                    }
                    pickerOpen = false
                }) { Text("Aceptar", color = colors.brand) }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Cancelar", color = colors.muted) }
            },
        ) {
            androidx.compose.material3.DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun LinkTargetSection(
    assets: List<LinkableAssetUi>,
    groups: List<LinkableGroupUi>,
    goalCurrency: Currency,
    selectedAssetIds: Set<String>,
    selectedGroupId: String?,
    onUnlink: () -> Unit,
    onToggleAsset: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
) {
    SheetSectionLabel("Seguir el saldo de cuentas o de un grupo (opcional)")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkOptionRow(
            label = "Sin vincular",
            selected = selectedAssetIds.isEmpty() && selectedGroupId == null,
            onClick = onUnlink,
        )
        if (assets.isNotEmpty()) {
            LinkSubheader("Cuentas · puedes marcar varias")
            assets.forEach { option ->
                LinkOptionRow(
                    label = option.name,
                    selected = option.id in selectedAssetIds,
                    onClick = { onToggleAsset(option.id) },
                    kind = LinkOptionKind.Account,
                    currencyTag = option.currency.takeIf { it != goalCurrency }?.code,
                )
            }
        }
        if (groups.isNotEmpty()) {
            LinkSubheader("Grupos")
            groups.forEach { option ->
                LinkOptionRow(
                    label = option.name,
                    selected = option.id == selectedGroupId,
                    onClick = { onSelectGroup(option.id) },
                    kind = LinkOptionKind.Group,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(22.dp))
}

@Composable
private fun LinkSubheader(text: String) {
    Text(
        text = text,
        color = LocalAppColors.current.ink2,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** [Account] rows are checkboxes (several can be picked); the rest pick one option. */
private enum class LinkOptionKind { None, Account, Group }

/** One row of the link picker. Account rows get a checkbox and, when it differs from the goal's, their
 *  currency; group rows a folder icon and a "Grupo" pill so they read apart from account rows. */
@Composable
private fun LinkOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    kind: LinkOptionKind = LinkOptionKind.None,
    currencyTag: String? = null,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) colors.surface2 else colors.surface,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.ink else colors.line,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (kind) {
            LinkOptionKind.Account -> CheckMark(checked = selected)
            LinkOptionKind.Group ->
                Icon(AppIcons.folder, contentDescription = null, tint = colors.brand, modifier = Modifier.size(16.dp))
            LinkOptionKind.None -> Unit
        }
        Text(
            text = label,
            color = colors.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        currencyTag?.let { Pill(text = it, tone = PillTone.Neutral) }
        if (kind == LinkOptionKind.Group) {
            Pill(text = "Grupo", tone = PillTone.Brand)
        }
        if (selected && kind != LinkOptionKind.Account) {
            Icon(AppIcons.check, contentDescription = null, tint = colors.brand, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SubmitButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (enabled) colors.ink else colors.bg2,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) colors.bg else colors.muted2,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Accepts digits and at most one decimal comma (period coerced to comma) — duplicated per-file
 *  (`AddPatrimonioSheet.filterAmountInput` precedent). */
private fun filterAmountInputSavings(raw: String): String {
    val allowed = raw.filter { it.isDigit() || it == ',' || it == '.' }.replace('.', ',')
    val firstComma = allowed.indexOf(',')
    return if (firstComma == -1) {
        allowed
    } else {
        allowed.substring(0, firstComma + 1) + allowed.substring(firstComma + 1).filter { it.isDigit() }
    }
}

private fun currencySymbolSavings(currency: Currency): String = when (currency) {
    Currency.USD -> "$"
    Currency.JPY -> "¥"
    Currency.GBP -> "£"
    Currency.EUR -> "€"
}

/** Formats [money] in its own [currency] (not EUR-converted) — duplicated per-file
 *  (`PatrimonioScreen.formatItemAmount` precedent). */
private fun formatSavingsAmount(money: Money, currency: Currency): String {
    val decimals = currency.decimals
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val abs = kotlin.math.abs(money.minorUnits)
    val intPart = if (decimals == 0) abs else abs / factor
    val grouped = intPart.toString().reversed().chunked(3).joinToString(".").reversed()
    val symbol = currencySymbolSavings(currency)
    return if (decimals == 0) {
        "$grouped $symbol"
    } else {
        val fracPart = (abs % factor).toString().padStart(decimals, '0')
        "$grouped,$fracPart $symbol"
    }
}

/** "{day} de {month} de {year}" (es-ES long form). */
private fun formatDateLongSavings(date: LocalDate): String {
    val monthName = MONTHS_ES_LONG_SAVINGS[date.monthNumber - 1]
    return "${date.dayOfMonth} de $monthName de ${date.year}"
}

private val MONTHS_ES_LONG_SAVINGS = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)

/** Square checkbox for the multi-select account rows (group rows pick a single option). */
@Composable
private fun CheckMark(checked: Boolean) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(color = if (checked) colors.ink else Color.Transparent, shape = shape)
            .border(1.5.dp, if (checked) colors.ink else colors.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(AppIcons.check, contentDescription = null, tint = colors.bg, modifier = Modifier.size(13.dp))
        }
    }
}
