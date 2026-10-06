package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes

/** Semantic tone for [Pill] — maps to a background/foreground/border token triple. */
enum class PillTone { Neutral, Income, Expense, Alert, Brand }

/** Sizing for [Pill] — controls padding and font size. */
enum class PillSize { Sm, Md }

/**
 * Small capsule label. Ported from `shared.jsx`'s `Pill({ children, tone, size })`.
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
    size: PillSize = PillSize.Sm,
) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current

    val (bg, fg, border) = when (tone) {
        PillTone.Neutral -> Triple(colors.bg2, colors.ink2, colors.line)
        PillTone.Income -> Triple(colors.incomeSoft, colors.income, Color.Transparent)
        PillTone.Expense -> Triple(colors.expenseSoft, colors.expense, Color.Transparent)
        PillTone.Alert -> Triple(colors.alertSoft, colors.alert, Color.Transparent)
        PillTone.Brand -> Triple(colors.brandSoft, colors.brand, Color.Transparent)
    }

    val isSm = size == PillSize.Sm
    val padding = if (isSm) {
        PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    } else {
        PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    }
    val fontSize = if (isSm) 11.sp else 12.sp

    Text(
        text = text,
        modifier = modifier
            .background(color = bg, shape = RoundedCornerShape(shapes.full))
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(shapes.full))
            .padding(padding),
        color = fg,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.01.sp,
        lineHeight = fontSize * 1.2,
    )
}
