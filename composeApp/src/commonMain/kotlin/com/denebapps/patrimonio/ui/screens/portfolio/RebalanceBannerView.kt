package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** The amber notice under the total of Cartera: a portfolio drifted from its targets; tapping opens its detail. */
@Composable
internal fun RebalanceBannerView(banner: RebalanceBannerUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.alertSoft, shape)
            .clickable(onClickLabel = "Ver el objetivo de reparto", onClick = onClick)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = banner.title,
                color = colors.alert,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
            )
            banner.detail?.let { BannerLine(it) }
            banner.suggestion?.let { BannerLine(it) }
        }
        Icon(
            imageVector = AppIcons.chevronR,
            contentDescription = null,
            tint = colors.alert,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun BannerLine(text: String) {
    Text(
        text = text,
        color = LocalAppColors.current.alert,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 18.sp,
    )
}
