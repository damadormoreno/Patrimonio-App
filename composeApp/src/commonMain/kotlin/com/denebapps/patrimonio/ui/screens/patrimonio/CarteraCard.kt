package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private const val NO_PRICE_LABEL = "Sin precio"

/**
 * The investment portfolio as a card among the assets: its total and size, expanding to one row per product. A row
 * opens that product's detail ([onOpenHolding]); the footer link opens the Cartera tab ([onOpenCartera]). While the
 * account filter narrows the list ([PortfolioSummaryUi.filtered]) the matching products stay in view.
 */
@Composable
internal fun CarteraCard(
    portfolio: PortfolioSummaryUi,
    onOpenCartera: () -> Unit,
    onOpenHolding: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val showItems = expanded || portfolio.filtered

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = if (showItems) "Contraer cartera" else "Ver productos de la cartera",
                    enabled = !portfolio.filtered,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PortfolioBadge()
            Column(Modifier.weight(1f)) {
                Text("Cartera", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = productCountLabel(shown = portfolio.holdingCount, total = portfolio.totalCount),
                    color = colors.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Text(
                text = formatMoneyEsGrupos(portfolio.total),
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                imageVector = AppIcons.chevronR,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(17.dp).rotate(if (showItems) 90f else 0f),
            )
        }
        if (showItems) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                portfolio.items.forEach { item ->
                    CarteraProductRow(item = item, showDivider = true, onClick = { onOpenHolding(item.id) })
                }
                Text(
                    text = "Abrir cartera",
                    color = colors.brand,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Abrir cartera", onClick = onOpenCartera)
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

/** One product of the Cartera card: its name, kind, EUR value and the group and goal icons. */
@Composable
private fun CarteraProductRow(
    item: PatrimonioHoldingUi,
    showDivider: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val lineColor = colors.line2

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (showDivider) {
                    Modifier.drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClickLabel = "Abrir producto", onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PortfolioBadge(size = 26.dp, cornerRadius = 8.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = item.name,
                    color = colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.inGoal) UsageIcon(AppIcons.spark, "En una meta")
                if (item.inGroup) UsageIcon(AppIcons.folder, "En un grupo")
            }
            Text(
                text = item.subtitle,
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Text(
            text = item.valueEur?.let(::formatMoneyEsGrupos) ?: NO_PRICE_LABEL,
            color = if (item.valueEur == null || item.closed) colors.muted else colors.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
