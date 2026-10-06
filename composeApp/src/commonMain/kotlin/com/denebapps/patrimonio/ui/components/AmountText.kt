package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import com.denebapps.patrimonio.ui.theme.numericTextStyle

/** Transaction direction — signs and colors [AmountText]. */
enum class AmountType { Income, Expense }

/**
 * Signed monetary amount with semantic color and an optional non-EUR currency
 * tag pill. Ported from `shared.jsx`'s `Amount({ type, amount, currency, size,
 * weight, showCurrencyTag })`.
 */
@Composable
fun AmountText(
    type: AmountType,
    amount: Double,
    modifier: Modifier = Modifier,
    currency: String = "EUR",
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    fontWeight: Int = 600,
    showCurrencyTag: Boolean = true,
) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current

    val sign = if (type == AmountType.Income) "+" else "−"
    val color = if (type == AmountType.Income) colors.income else colors.ink
    val decimals = if (currency == "JPY") 0 else 2
    val symbol = currencySymbol(currency)
    val amountText = formatAmount(kotlin.math.abs(amount), decimals)

    Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = "$sign$amountText $symbol",
            style = numericTextStyle(
                fontSize = fontSize,
                fontWeight = androidx.compose.ui.text.font.FontWeight(fontWeight),
            ),
            color = color,
        )
        if (showCurrencyTag && currency != "EUR") {
            Text(
                text = currency,
                fontSize = 10.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                letterSpacing = 0.05.sp,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(color = colors.bg2, shape = RoundedCornerShape(shapes.full))
                    .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(shapes.full))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

private fun currencySymbol(currency: String): String = when (currency) {
    "USD" -> "$"
    "JPY" -> "¥"
    "GBP" -> "£"
    else -> "€"
}

/** Formats [value] with thousands grouping and a fixed number of [decimals] (locale-agnostic, KMP-safe). */
private fun formatAmount(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val totalMinorUnits = kotlin.math.round(value * factor).toLong()
    val intPart = if (decimals == 0) totalMinorUnits else totalMinorUnits / factor
    val grouped = intPart.toString().reversed().chunked(3).joinToString(".").reversed()
    if (decimals == 0) return grouped
    val fracPart = (totalMinorUnits % factor).toString().padStart(decimals, '0')
    return "$grouped,$fracPart"
}
