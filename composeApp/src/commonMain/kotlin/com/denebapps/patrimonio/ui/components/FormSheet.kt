package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DatePicker
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.datetime.LocalDate

// Building blocks for full-screen form sheets (same look as the goal and patrimonio sheets, which
// still carry private copies of them).

private const val MILLIS_PER_DAY = 86_400_000L

@Composable
fun FormSheetGrabber() {
    val colors = LocalAppColors.current
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
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
fun FormSheetTopBar(title: String, canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
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
            modifier = Modifier.plainClickable(onClick = onCancel).padding(6.dp),
        )
        Text(text = title, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Guardar",
            color = if (canSave) colors.brand else colors.muted2,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.plainClickable(enabled = canSave, onClick = onSave).padding(6.dp),
        )
    }
}

@Composable
fun FormSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = LocalAppColors.current.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.06.sp,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun FormTextField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Box(modifier = Modifier.fillMaxWidth().fieldBox()) {
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

/** Large amount input with the currency symbol and a currency picker. Input is filtered with
 *  [filterAmountInput] before reaching [onAmountChange]. */
@Composable
fun FormAmountField(
    amountText: String,
    currency: Currency,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().fieldBox(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = currencySymbol(currency), color = colors.muted2, fontSize = 28.sp, fontWeight = FontWeight.Medium)
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
        CurrencyPicker(value = currency, onChange = onCurrencyChange)
    }
}

@Composable
fun CurrencyPicker(value: Currency, onChange: (Currency) -> Unit) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(999.dp))
                .background(color = colors.bg2, shape = RoundedCornerShape(999.dp))
                .plainClickable(onClick = { expanded = true })
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = value.code, color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Icon(AppIcons.chevronD, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(11.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Currency.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.code} · ${currencySymbol(option)}") },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                    trailingIcon = {
                        if (option == value) Icon(AppIcons.check, contentDescription = null, tint = colors.brand)
                    },
                )
            }
        }
    }
}

/** Tappable date row opening a Material date picker. Dates travel as UTC-midnight millis, the
 *  picker's own convention, so no time zone can shift the selected day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDateField(date: LocalDate, onDateChange: (LocalDate) -> Unit) {
    val colors = LocalAppColors.current
    var pickerOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().plainClickable(onClick = { pickerOpen = true }).fieldBox(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcons.calendar, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
        Text(text = formatDateLong(date), color = colors.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }

    if (pickerOpen) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDays() * MILLIS_PER_DAY)
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
                    pickerState.selectedDateMillis?.let {
                        onDateChange(LocalDate.fromEpochDays((it / MILLIS_PER_DAY).toInt()))
                    }
                    pickerOpen = false
                }) { Text("Aceptar", color = colors.brand) }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Cancelar", color = colors.muted) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** One selectable row of a single-choice list. */
@Composable
fun FormOptionRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = if (selected) colors.surface2 else colors.surface, shape = RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.ink else colors.line,
                shape = RoundedCornerShape(12.dp),
            )
            .plainClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            color = colors.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(AppIcons.check, contentDescription = null, tint = colors.brand, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Modifier.fieldBox(): Modifier {
    val colors = LocalAppColors.current
    return background(color = colors.surface2, shape = RoundedCornerShape(14.dp))
        .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(14.dp))
        .padding(horizontal = 16.dp, vertical = 14.dp)
}

@Composable
private fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    enabled = enabled,
    onClick = onClick,
)
