package com.denebapps.patrimonio.ui.screens.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.MonthTrend
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.formatEur
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.min

private const val MAX_X_LABELS = 12

/**
 * "Evolución mensual": how net worth moved month by month. A diverging column chart shows each month's
 * change (up = gain, down = loss, grey = stable); tapping a column or a row selects that month, whose
 * figures appear in the detail card. The table below repeats every value, so nothing depends on colour.
 */
@Composable
fun NetWorthHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NetWorthHistoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current

    Column(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderIconBtn(icon = AppIcons.chevronL, onClick = onBack, label = "Volver a Patrimonio")
            Text(
                text = "Evolución mensual",
                color = colors.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.02).sp,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            RangeSelector(selected = state.range, onSelect = viewModel::onRangeSelect)
            PeriodSummary(state = state, modifier = Modifier.padding(top = 18.dp))

            if (state.months.count { it.change != null } == 0) {
                Text(
                    text = "Aún no hay meses que comparar. Cada mes en que actualices tus cuentas quedará " +
                        "guardado y aparecerá aquí.",
                    color = colors.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                ChangeChart(
                    months = state.months,
                    selected = state.selected?.month,
                    onSelect = viewModel::onMonthSelect,
                    modifier = Modifier.padding(top = 20.dp),
                )
                state.selected?.let { MonthDetail(month = it, modifier = Modifier.padding(top = 14.dp)) }
            }

            Text(
                text = "MES A MES",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.sp,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )
            MonthTable(
                months = state.months.asReversed(),
                selected = state.selected?.month,
                onSelect = viewModel::onMonthSelect,
            )
            Text(
                text = "Patrimonio neto en EUR. Un mes sin cambios guardados mantiene el valor del anterior; " +
                    "variaciones menores del 0,5 % cuentan como estables.",
                color = colors.muted2,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun RangeSelector(selected: HistoryRange, onSelect: (HistoryRange) -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bg2, shape)
            .border(1.dp, colors.line2, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HistoryRange.entries.forEach { range ->
            val active = range == selected
            Text(
                text = range.label,
                color = if (active) colors.ink else colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) colors.surface2 else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(range) },
                    )
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PeriodSummary(state: NetWorthHistoryUiState, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(modifier = modifier) {
        Text(
            text = periodLabel(state).uppercase(),
            color = colors.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            val change = state.periodChange
            if (change != null) TrendIcon(trendOf(change), size = 22)
            Text(
                text = change?.let(::formatSigned) ?: "—",
                color = colors.ink,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.02).sp,
            )
        }
        Text(
            text = "Patrimonio actual: ${formatEur(state.currentNetWorth)}",
            color = colors.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            TrendCount(MonthTrend.UP, state.upMonths, "ganando")
            TrendCount(MonthTrend.DOWN, state.downMonths, "perdiendo")
            TrendCount(MonthTrend.STABLE, state.stableMonths, if (state.stableMonths == 1) "estable" else "estables")
        }
    }
}

private fun periodLabel(state: NetWorthHistoryUiState): String = when (state.range) {
    HistoryRange.ALL -> "Desde el primer mes"
    else -> "En los últimos ${state.range.months} meses"
}

@Composable
private fun TrendCount(trend: MonthTrend, count: Int, label: String) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TrendIcon(trend, size = 14)
        Text(text = "$count $label", color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Diverging columns around a zero baseline; the scale fits the largest gain above and the largest loss
 *  below, so a history with only gains uses the whole height. */
@Composable
private fun ChangeChart(
    months: List<MonthRowUi>,
    selected: YearMonth?,
    onSelect: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val gain = colors.chartGain
    val loss = colors.chartLoss
    val stable = colors.muted2
    val baseline = colors.line
    val labelStep = ceil(months.size / MAX_X_LABELS.toFloat()).toInt().coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .pointerInput(months) {
                    detectTapGestures { offset ->
                        val index = (offset.x / (size.width.toFloat() / months.size)).toInt()
                        months.getOrNull(index)?.let { onSelect(it.month) }
                    }
                },
        ) {
            val maxUp = months.maxOf { (it.change?.minorUnits ?: 0L).coerceAtLeast(0L) }
            val maxDown = months.maxOf { -(it.change?.minorUnits ?: 0L).coerceAtMost(0L) }
            val span = (maxUp + maxDown).toFloat()
            val zeroY = if (span == 0f) size.height / 2 else size.height * maxUp / span
            val slot = size.width / months.size
            val barWidth = min(24.dp.toPx(), slot * 0.6f)
            val radius = CornerRadius(4.dp.toPx())
            val stub = 2.dp.toPx()

            months.forEachIndexed { index, month ->
                val change = month.change ?: return@forEachIndexed
                val left = index * slot + (slot - barWidth) / 2
                val height = if (span == 0f) 0f else size.height * change.minorUnits.absoluteValue / span
                val color = when (month.trend) {
                    MonthTrend.UP -> gain
                    MonthTrend.DOWN -> loss
                    else -> stable
                }.copy(alpha = if (selected == null || month.month == selected) 1f else 0.45f)
                val h = height.coerceAtLeast(stub)
                val bar = if (change.minorUnits >= 0) {
                    RoundRect(
                        left = left,
                        top = zeroY - h,
                        right = left + barWidth,
                        bottom = zeroY,
                        topLeftCornerRadius = radius,
                        topRightCornerRadius = radius,
                    )
                } else {
                    RoundRect(
                        left = left,
                        top = zeroY,
                        right = left + barWidth,
                        bottom = zeroY + h,
                        bottomRightCornerRadius = radius,
                        bottomLeftCornerRadius = radius,
                    )
                }
                drawPath(Path().apply { addRoundRect(bar) }, color)
            }
            drawRect(baseline, topLeft = Offset(0f, zeroY - 0.5.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            months.forEachIndexed { index, month ->
                val isSelected = month.month == selected
                Text(
                    text = if (index % labelStep == 0 || isSelected) month.shortLabel else "",
                    color = if (isSelected) colors.ink else colors.muted,
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MonthDetail(month: MonthRowUi, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text = month.longLabel, color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = formatEur(month.netWorth),
            color = colors.ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            month.trend?.let { TrendIcon(it, size = 16) }
            Text(text = changeDescription(month), color = colors.ink2, fontSize = 13.sp)
        }
        if (!month.recorded) {
            Text(
                text = "No guardaste cambios este mes: se mantiene el valor del anterior.",
                color = colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MonthTable(months: List<MonthRowUi>, selected: YearMonth?, onSelect: (YearMonth) -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
    ) {
        months.forEach { month ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(month.month) },
                    )
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = month.longLabel,
                        color = colors.ink,
                        fontSize = 14.sp,
                        fontWeight = if (month.month == selected) FontWeight.Bold else FontWeight.Medium,
                    )
                    Text(text = formatEur(month.netWorth), color = colors.muted, fontSize = 12.sp)
                }
                month.trend?.let { TrendIcon(it, size = 14) }
                Text(
                    text = month.change?.let(::formatSigned) ?: "—",
                    color = colors.ink2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Arrow (or dash) in the chart colour next to the text, so the trend never relies on colour alone. */
@Composable
private fun TrendIcon(trend: MonthTrend, size: Int) {
    val colors = LocalAppColors.current
    val (icon: ImageVector, tint: Color, label: String) = when (trend) {
        MonthTrend.UP -> Triple(AppIcons.arrowUp, colors.chartGain, "Gana")
        MonthTrend.DOWN -> Triple(AppIcons.arrowDown, colors.chartLoss, "Pierde")
        MonthTrend.STABLE -> Triple(AppIcons.minus, colors.muted2, "Estable")
    }
    Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(size.dp))
}

private fun trendOf(change: Money): MonthTrend = when {
    change.minorUnits > 0 -> MonthTrend.UP
    change.minorUnits < 0 -> MonthTrend.DOWN
    else -> MonthTrend.STABLE
}

/** "Ganas 1.230,00 € (+2,8 %) respecto al mes anterior". */
internal fun changeDescription(month: MonthRowUi): String {
    val change = month.change ?: return "Primer mes con datos"
    val percent = month.changePercentTenths?.let { " (${formatPercentTenths(it)})" }.orEmpty()
    val verb = when (month.trend) {
        MonthTrend.UP -> "Ganas"
        MonthTrend.DOWN -> "Pierdes"
        else -> "Estable:"
    }
    return "$verb ${formatSigned(change)}$percent respecto al mes anterior"
}

internal fun formatSigned(money: Money): String = if (money.minorUnits > 0) "+${formatEur(money)}" else formatEur(money)

internal fun formatPercentTenths(tenths: Long): String {
    val sign = if (tenths > 0) "+" else if (tenths < 0) "-" else ""
    val abs = tenths.absoluteValue
    return "$sign${abs / 10},${abs % 10} %"
}
