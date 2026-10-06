package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Screen title header with an optional eyebrow label and trailing content.
 * Ported from `shared.jsx`'s `ScreenHeader({ title, eyebrow, right, large })`.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    large: Boolean = true,
    right: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val verticalPadding = if (large) 14.dp else 10.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = verticalPadding),
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow,
                fontSize = 12.sp,
                color = colors.muted,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.01.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                fontSize = if (large) 30.sp else 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.02).sp,
                lineHeight = if (large) 33.sp else 24.2.sp,
                color = colors.ink,
            )
            right?.invoke()
        }
    }
}
