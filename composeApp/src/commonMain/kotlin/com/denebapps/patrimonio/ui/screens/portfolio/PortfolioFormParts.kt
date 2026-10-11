package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.ui.components.currencySymbol
import com.denebapps.patrimonio.ui.components.filterAmountInput
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

/** Big right-aligned amount field in a fixed [currency] (the one a quote came in), unlike `FormAmountField`. */
@Composable
internal fun FixedCurrencyAmountField(
    text: String,
    currency: Currency,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
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
            textStyle = TextStyle(
                color = colors.ink,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.weight(1f),
        )
        Text(text = currency.code, color = colors.muted2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
