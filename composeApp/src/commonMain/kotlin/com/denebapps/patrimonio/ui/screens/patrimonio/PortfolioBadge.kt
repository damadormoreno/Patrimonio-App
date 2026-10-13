package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.components.PORTFOLIO_LOOK
import com.denebapps.patrimonio.ui.components.toColor
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** The square badge of a portfolio product: the Cartera colour with the trend icon, as on the Patrimonio Cartera card. */
@Composable
internal fun PortfolioBadge(modifier: Modifier = Modifier, size: Dp = 32.dp, cornerRadius: Dp = 9.dp) {
    Box(
        modifier = modifier.size(size).background(PORTFOLIO_LOOK.toColor(), RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.trend,
            contentDescription = null,
            tint = LocalAppColors.current.surface2,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
