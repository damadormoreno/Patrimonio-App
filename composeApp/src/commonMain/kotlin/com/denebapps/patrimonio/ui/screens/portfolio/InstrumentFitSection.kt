package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.SectionRow
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private val BANNER_SHAPE = RoundedCornerShape(12.dp)

/**
 * "Encaje con tu cartera": the verdict on how the product fits the portfolio, the holdings it resembles most and, with
 * an amount, what the portfolio would look like after adding it. [amountText] is the amount field, in euros.
 */
@Composable
internal fun InstrumentFitSection(fit: FitSectionUi, amountText: String, onAmountChange: (String) -> Unit) {
    SectionLabel(fit.title)
    fit.message?.let { FitMessage(it) }
    fit.verdict?.let { VerdictBanner(it) }
    if (fit.similar.isNotEmpty()) {
        SectionLabel("Más parecidos en tu cartera")
        SimilarHoldingsCard(fit.similar)
    }
    if (fit.canProject) {
        FormSectionLabel("Si inviertes")
        FixedCurrencyAmountField(text = amountText, currency = Currency.EUR, onTextChange = onAmountChange)
        fit.projection?.let { ProjectionCard(it) }
    }
}

@Composable
private fun FitMessage(text: String) {
    Text(text = text, color = LocalAppColors.current.muted, fontSize = 13.sp, lineHeight = 19.sp)
}

/** The verdict in the color of its [FitVerdictUi.tone], the sentence that explains it and the muted figures. */
@Composable
private fun VerdictBanner(verdict: FitVerdictUi) {
    val colors = LocalAppColors.current
    val (background, accent) = when (verdict.tone) {
        FitTone.ALERT -> colors.alertSoft to colors.alert
        FitTone.NEUTRAL -> colors.surface2 to colors.ink
        FitTone.POSITIVE -> colors.incomeSoft to colors.income
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, BANNER_SHAPE)
            .border(1.dp, colors.line2, BANNER_SHAPE)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = verdict.title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = verdict.detail,
            color = colors.ink2,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(text = verdict.caption, color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SimilarHoldingsCard(similar: List<SimilarHoldingUi>) {
    val colors = LocalAppColors.current
    DetailCard {
        similar.forEachIndexed { index, holding ->
            SectionRow(
                title = holding.name,
                subtitle = holding.note,
                showDivider = index != similar.lastIndex,
                trailing = { RowValue(holding.correlationText, colors.ink) },
            )
        }
    }
}

/** What adding the amount would do: its weight, the average cost and risk before and after, and the warning. */
@Composable
private fun ProjectionCard(projection: ProjectionUi) {
    val colors = LocalAppColors.current
    Text(
        text = projection.weightText,
        color = colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
    if (projection.terText != null || projection.riskText != null) {
        DetailCard {
            projection.terText?.let { text ->
                SectionRow(
                    title = "Gastos corrientes medios (TER)",
                    subtitle = projection.terCoverage,
                    showDivider = projection.riskText != null,
                    trailing = { RowValue(text, colors.ink) },
                )
            }
            projection.riskText?.let { text ->
                SectionRow(
                    title = "Riesgo medio",
                    subtitle = projection.riskCoverage,
                    showDivider = false,
                    trailing = { RowValue(text, colors.ink) },
                )
            }
        }
    }
    projection.warning?.let { WarningBanner(it, Modifier.padding(top = 10.dp)) }
}
