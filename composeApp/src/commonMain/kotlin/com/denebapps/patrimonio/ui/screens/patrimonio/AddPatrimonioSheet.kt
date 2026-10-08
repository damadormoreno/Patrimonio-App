package com.denebapps.patrimonio.ui.screens.patrimonio

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Full-height Patrimonio item destination (new item, or editing [itemId] with a delete action), styled with the
 *  [com.denebapps.patrimonio.ui.components.BottomSheet] visual language but rendered as a normal
 *  `composable<AddPatrimonio>` entry (design.md Decision: sheets as full pushed destinations). Ports
 *  `design-reference/patrimonio.jsx`'s `AddPatrimonioSheet` minus its optional "Detalle" field, which
 *  is not part of this change's spec/design contract. Collects
 *  [AddPatrimonioSheetViewModel.state] and pops the back stack once
 *  [AddPatrimonioSheetViewModel.navigateBack] emits.
 */
@Composable
fun AddPatrimonioSheet(
    isLiability: Boolean,
    groupId: String?,
    modifier: Modifier = Modifier,
    itemId: String? = null,
    onNavigateBack: () -> Unit = {},
    viewModel: AddPatrimonioSheetViewModel = koinViewModel(parameters = { parametersOf(isLiability, groupId, itemId) }),
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
            .background(
                color = colors.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp, bottom = 6.dp)
                .height(4.dp)
                .width(36.dp)
                .background(color = colors.line, shape = RoundedCornerShape(999.dp)),
        )

        TopBar(
            title = when {
                state.isEditing && state.isLiability -> "Editar pasivo"
                state.isEditing -> "Editar activo"
                state.isLiability -> "Nuevo pasivo"
                else -> "Nuevo activo"
            },
            canSave = state.canSave,
            onCancel = onNavigateBack,
            onSave = viewModel::onSave,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // An edited item keeps its kind: assets and liabilities are stored apart.
            if (!state.isEditing) {
                ModeSegmented(isLiability = state.isLiability, onModeChange = viewModel::onModeChange)
            }

            GroupSection(
                options = state.groupOptions,
                selectedId = state.selectedGroupId,
                onSelect = viewModel::onGroupSelect,
            )

            NameSection(
                name = state.name,
                isLiability = state.isLiability,
                onNameChange = viewModel::onNameChange,
            )

            AmountSection(
                isLiability = state.isLiability,
                amountText = state.amountText,
                currency = state.currency,
                eurHint = state.eurHint,
                onAmountChange = viewModel::onAmountChange,
                onCurrencyChange = viewModel::onCurrencyChange,
            )

            Text(
                text = if (state.isLiability) {
                    "El importe pendiente se resta del patrimonio neto. Podrás actualizarlo cuando hagas pagos."
                } else {
                    "El valor se suma al patrimonio neto. Si está en otra divisa se convierte a EUR al cambio actual."
                },
                color = colors.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (state.isEditing) {
                Text(
                    text = if (state.isLiability) "Borrar pasivo" else "Borrar activo",
                    color = colors.expense,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { confirmDelete = true },
                        )
                        .padding(vertical = 12.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar «${state.name.trim()}»?") },
            text = {
                Text(
                    listOfNotNull(
                        if (state.isLiability) "Se quitará de tus pasivos." else "Se quitará de tu patrimonio.",
                        state.deleteWarning,
                    ).joinToString(" "),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.onDelete()
                    },
                ) { Text("Borrar", color = colors.expense) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TopBar(title: String, canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
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
        Text(
            text = title,
            color = colors.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).sp,
        )
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
private fun ModeSegmented(isLiability: Boolean, onModeChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.bg2, shape = RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = colors.line2, shape = RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeSegmentButton(
            label = "Activo",
            icon = AppIcons.arrowUp,
            active = !isLiability,
            activeColor = colors.income,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(false) },
        )
        ModeSegmentButton(
            label = "Pasivo",
            icon = AppIcons.arrowDown,
            active = isLiability,
            activeColor = colors.expense,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(true) },
        )
    }
    Spacer(modifier = Modifier.height(22.dp))
}

@Composable
private fun ModeSegmentButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val fg = if (active) activeColor else colors.muted

    Row(
        modifier = modifier
            .background(
                color = if (active) colors.surface2 else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text = label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionLabel(text: String) {
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
private fun GroupSection(options: List<GroupOptionUi>, selectedId: String?, onSelect: (String) -> Unit) {
    SectionLabel("Tipo")
    options.chunked(3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { option ->
                GroupTile(
                    option = option,
                    selected = option.id == selectedId,
                    onClick = { onSelect(option.id) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun GroupTile(option: GroupOptionUi, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val borderColor = if (selected) colors.ink else colors.line
    val bg = if (selected) colors.surface2 else colors.surface

    Column(
        modifier = modifier
            .background(color = bg, shape = RoundedCornerShape(14.dp))
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(36.dp).background(groupTileTone(option.id), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = groupTileIcon(option.id),
                contentDescription = null,
                tint = colors.surface2,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = option.label,
            color = colors.ink2,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun NameSection(name: String, isLiability: Boolean, onNameChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    val placeholder = if (isLiability) "Ej: Hipoteca piso Madrid" else "Cómo lo llamarás"

    SectionLabel("Nombre")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surface2, shape = RoundedCornerShape(14.dp))
            .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (name.isEmpty()) {
            Text(text = placeholder, color = colors.muted, fontSize = 15.sp)
        }
        BasicTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.ink, fontSize = 15.sp),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(modifier = Modifier.height(22.dp))
}

@Composable
private fun AmountSection(
    isLiability: Boolean,
    amountText: String,
    currency: Currency,
    eurHint: String?,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
) {
    val colors = LocalAppColors.current

    SectionLabel(if (isLiability) "Importe pendiente" else "Valor actual")
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
            text = currencySymbolFor(currency),
            color = colors.muted2,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
        BasicTextField(
            value = amountText,
            onValueChange = { onAmountChange(filterAmountInput(it)) },
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
        CurrencyDropdown(value = currency, onChange = onCurrencyChange)
    }
    if (eurHint != null) {
        Text(
            text = eurHint,
            color = colors.muted,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textAlign = TextAlign.End,
        )
    }
    Spacer(modifier = Modifier.height(22.dp))
}

@Composable
private fun CurrencyDropdown(value: Currency, onChange: (Currency) -> Unit) {
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
            Text(
                text = value.code,
                color = colors.ink2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.04.sp,
            )
            Icon(
                imageVector = AppIcons.chevronD,
                contentDescription = null,
                tint = colors.ink2,
                modifier = Modifier.size(11.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Currency.entries.forEach { code ->
                DropdownMenuItem(
                    text = { Text("${code.code} · ${currencySymbolFor(code)}") },
                    onClick = {
                        onChange(code)
                        expanded = false
                    },
                    trailingIcon = {
                        if (code == value) {
                            Icon(imageVector = AppIcons.check, contentDescription = null, tint = colors.brand)
                        }
                    },
                )
            }
        }
    }
}

/** Icon/tone lookup by [GroupOptionUi.id] (the [Asset.AssetGroup]/[Liability.LiabilityGroup] enum
 *  `.name`), ported 1:1 from `design-reference/shared.jsx`'s `ASSET_GROUPS`/`LIAB_GROUPS` — matches
 *  [PatrimonioScreen]'s private `groupIcon` lookup. */
private fun groupTileIcon(groupId: String): ImageVector = when (groupId) {
    Asset.AssetGroup.BANK.name -> AppIcons.bank
    Asset.AssetGroup.INVEST.name -> AppIcons.trend
    Asset.AssetGroup.REALESTATE.name -> AppIcons.building
    Asset.AssetGroup.CRYPTO.name -> AppIcons.coins
    Asset.AssetGroup.CASH.name -> AppIcons.wallet
    Liability.LiabilityGroup.MORTGAGE.name -> AppIcons.house
    Liability.LiabilityGroup.LOAN.name -> AppIcons.briefcase
    Liability.LiabilityGroup.CARD.name -> AppIcons.card
    else -> AppIcons.folder
}

@Composable
private fun groupTileTone(groupId: String): Color {
    val colors = LocalAppColors.current
    return when (groupId) {
        Asset.AssetGroup.BANK.name -> colors.catTrans
        Asset.AssetGroup.INVEST.name -> colors.catSalary
        Asset.AssetGroup.REALESTATE.name -> colors.catHome
        Asset.AssetGroup.CRYPTO.name -> colors.catFun
        Asset.AssetGroup.CASH.name -> colors.catOther
        Liability.LiabilityGroup.MORTGAGE.name -> colors.catRest
        Liability.LiabilityGroup.LOAN.name -> colors.catSubs
        Liability.LiabilityGroup.CARD.name -> colors.catFood
        else -> colors.catOther
    }
}

/** Accepts digits and at most one decimal comma (period is coerced to comma), matching
 *  `parseAmountToMinor`'s expected input format. */
private fun filterAmountInput(raw: String): String {
    val allowed = raw.filter { it.isDigit() || it == ',' || it == '.' }.replace('.', ',')
    val firstComma = allowed.indexOf(',')
    return if (firstComma == -1) {
        allowed
    } else {
        allowed.substring(0, firstComma + 1) + allowed.substring(firstComma + 1).filter { it.isDigit() }
    }
}

private fun currencySymbolFor(currency: Currency): String = when (currency) {
    Currency.USD -> "$"
    Currency.JPY -> "¥"
    Currency.GBP -> "£"
    Currency.EUR -> "€"
}
