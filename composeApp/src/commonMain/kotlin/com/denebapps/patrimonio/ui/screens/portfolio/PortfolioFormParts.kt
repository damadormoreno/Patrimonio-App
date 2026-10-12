package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.FormTextField
import com.denebapps.patrimonio.ui.components.currencySymbol
import com.denebapps.patrimonio.ui.components.filterAmountInput
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** Two-or-more-way toggle in the style of the Activo/Pasivo switch of the Patrimonio form. */
@Composable
internal fun <T> SegmentedToggle(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = colors.bg2, shape = RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = colors.line2, shape = RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                text = label,
                color = if (active) colors.ink else colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (active) colors.surface2 else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
    }
}

/** Big right-aligned decimal field for a number of units, with its [suffix] on the right. */
@Composable
internal fun UnitsField(text: String, onTextChange: (String) -> Unit, suffix: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier.fillMaxWidth().fieldBox(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { onTextChange(filterUnitsInput(it)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(
                color = colors.ink,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.weight(1f),
        )
        Text(text = suffix, color = colors.muted2, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The units of a position that has movements: drawn like [UnitsField] but muted and not editable, because they come
 * from the movements; [note] says so under it.
 */
@Composable
internal fun LockedUnitsField(text: String, suffix: String, note: String?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().fieldBox(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = text,
                color = colors.muted,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Text(text = suffix, color = colors.muted2, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        note?.let {
            Text(
                text = it,
                color = colors.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Big right-aligned amount field in a fixed [currency] (the one a quote came in), unlike `FormAmountField`. While
 * [text] is empty, [placeholder] (when given) shows muted in its place, e.g. the value the field would follow.
 */
@Composable
internal fun FixedCurrencyAmountField(
    text: String,
    currency: Currency,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val colors = LocalAppColors.current
    val valueStyle = TextStyle(
        color = colors.ink,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.End,
    )
    Row(
        modifier = modifier.fillMaxWidth().fieldBox(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = currencySymbol(currency), color = colors.muted2, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        BasicTextField(
            value = text,
            onValueChange = { onTextChange(filterAmountInput(it)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = valueStyle,
            cursorBrush = SolidColor(colors.ink),
            decorationBox = { field ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty() && placeholder != null) {
                        Text(text = placeholder, style = valueStyle.copy(color = colors.muted2))
                    }
                    field()
                }
            },
            modifier = Modifier.weight(1f),
        )
        Text(text = currency.code, color = colors.muted2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The optional "Banco o bróker" field of the add and detail forms: free text, no suggestions. */
@Composable
internal fun BrokerField(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        FormSectionLabel("Banco o bróker")
        FormTextField(value = text, placeholder = "Opcional · p. ej. MyInvestor", onValueChange = onTextChange)
    }
}

/** The muted "≈ ..." line under an entry field. */
@Composable
internal fun PreviewLine(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text = text,
        color = LocalAppColors.current.muted,
        fontSize = 13.sp,
        textAlign = TextAlign.End,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun Modifier.fieldBox(): Modifier {
    val colors = LocalAppColors.current
    return background(color = colors.surface2, shape = RoundedCornerShape(14.dp))
        .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(14.dp))
        .padding(horizontal = 16.dp, vertical = 14.dp)
}

/**
 * The "Cartera" picker of the add and detail forms: a field showing the chosen portfolio that opens a menu of all of
 * them. Shown only when there is a choice ([MIN_PORTFOLIOS_TO_CHOOSE] portfolios at least), so it takes up no room
 * when the user has a single portfolio.
 */
@Composable
internal fun PortfolioPicker(
    options: List<PortfolioOptionUi>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.size < MIN_PORTFOLIOS_TO_CHOOSE) return
    PickerField(
        title = "Cartera",
        valueText = options.firstOrNull { it.id == selectedId }?.name.orEmpty(),
        clickLabel = "Elegir cartera",
        modifier = modifier,
    ) { dismiss ->
        options.forEach { option ->
            PickerItem(label = option.name, selected = option.id == selectedId) {
                dismiss()
                onSelect(option.id)
            }
        }
    }
}

/**
 * The "Clase de activo" picker of the add and detail forms: a field showing the class of the holding that opens a menu
 * with the automatic option first ([AssetClassChoiceUi.automaticLabel]: the inferred class, or unclassified) and then
 * every class. [onSelect] gets `null` for the automatic option.
 */
@Composable
internal fun AssetClassPicker(
    choice: AssetClassChoiceUi,
    onSelect: (AssetClass?) -> Unit,
    modifier: Modifier = Modifier,
) {
    PickerField(
        title = "Clase de activo",
        valueText = choice.text,
        clickLabel = "Elegir clase de activo",
        modifier = modifier,
    ) { dismiss ->
        PickerItem(label = choice.automaticLabel, selected = choice.isAutomatic) {
            dismiss()
            onSelect(null)
        }
        AssetClass.entries.forEach { assetClass ->
            PickerItem(label = assetClassLabel(assetClass), selected = choice.explicit == assetClass) {
                dismiss()
                onSelect(assetClass)
            }
        }
    }
}

/**
 * A labelled picker of one of [options] (a value and its label): the field shows the label of [selected] or
 * [placeholder] while there is none. The optional [extra] is one more entry at the end that runs an action instead of
 * choosing a value ("Otro fondo…").
 */
@Composable
internal fun <T> OptionPicker(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    placeholder: String,
    clickLabel: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    extra: Pair<String, () -> Unit>? = null,
) {
    PickerField(
        title = title,
        valueText = options.firstOrNull { it.first == selected }?.second ?: placeholder,
        clickLabel = clickLabel,
        modifier = modifier,
    ) { dismiss ->
        options.forEach { (value, label) ->
            PickerItem(label = label, selected = value == selected) {
                dismiss()
                onSelect(value)
            }
        }
        extra?.let { (label, action) ->
            PickerItem(label = label, selected = false) {
                dismiss()
                action()
            }
        }
    }
}

/** A labelled field showing [valueText] that opens the menu [items], which receives a function that closes it. */
@Composable
private fun PickerField(
    title: String,
    valueText: String,
    clickLabel: String,
    modifier: Modifier = Modifier,
    items: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }

    Column(modifier) {
        FormSectionLabel(title)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = clickLabel,
                        onClick = { expanded = true },
                    )
                    .fieldBox(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = valueText,
                    color = colors.ink,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(AppIcons.chevronD, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(14.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items { expanded = false }
            }
        }
    }
}

@Composable
private fun PickerItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = {
            if (selected) Icon(AppIcons.check, contentDescription = null, tint = LocalAppColors.current.brand)
        },
    )
}
