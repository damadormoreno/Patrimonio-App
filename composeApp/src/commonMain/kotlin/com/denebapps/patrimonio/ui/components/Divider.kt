package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * Horizontal rule. Ported from `shared.jsx`'s `Divider({ text })`: a plain 1dp
 * [AppColors.line] when [text] is null, otherwise an uppercase muted label
 * centered between two rule segments.
 */
@Composable
fun Divider(modifier: Modifier = Modifier, text: String? = null) {
    val colors = LocalAppColors.current

    if (text == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color = colors.line),
        )
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DividerRule(colors.line, Modifier.weight(1f))
        Text(
            text = text.uppercase(),
            color = colors.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.06.sp,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        DividerRule(colors.line, Modifier.weight(1f))
    }
}

@Composable
private fun RowScope.DividerRule(color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Box(modifier = modifier.height(1.dp).background(color = color))
}
