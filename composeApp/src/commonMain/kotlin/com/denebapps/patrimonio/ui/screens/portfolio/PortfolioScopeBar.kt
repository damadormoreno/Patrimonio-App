package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.LinkButton
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes

/** What the portfolio selector can do, so it can be shown without a ViewModel. */
internal class PortfolioScopeActions(
    val onSelect: (String?) -> Unit,
    val onNew: () -> Unit,
)

private val SCOPE_GUTTER = 20.dp
private val SCOPE_CHIP_GAP = 8.dp

/**
 * The scope of the Cartera tab under its header. With several portfolios, a row of chips (`Todas`, each portfolio, and
 * `+ Nueva`) that scrolls sideways under the whole width of the screen and keeps the selected chip in view; with one,
 * just a discreet `Nueva cartera` link. Nothing else lives here: the filters sit above the list and a portfolio is
 * renamed or deleted from its detail.
 */
@Composable
internal fun PortfolioScopeBar(state: CarteraUiState, actions: PortfolioScopeActions, modifier: Modifier = Modifier) {
    if (state.showSelector) {
        // The padding is inside the scroll so the chips slide under the screen edge instead of being cut at the gutter.
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = SCOPE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(SCOPE_CHIP_GAP),
        ) {
            ScopeChip(
                text = "Todas",
                selected = state.selectedPortfolioId == null,
                onClick = { actions.onSelect(null) },
                keepInView = true,
            )
            state.portfolios.forEach { portfolio ->
                ScopeChip(
                    text = portfolio.name,
                    selected = portfolio.id == state.selectedPortfolioId,
                    onClick = { actions.onSelect(portfolio.id) },
                    keepInView = true,
                )
            }
            ScopeChip(text = "+ Nueva", selected = false, subtle = true, onClick = actions.onNew)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = SCOPE_GUTTER),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            LinkButton(text = "Nueva cartera", onClick = actions.onNew)
        }
    }
}

/**
 * A pill to pick one value. With [keepInView], while it is [selected] it scrolls itself (and a gutter around it) into
 * view inside a scrolling row, so the choice is never left half hidden.
 */
@Composable
internal fun ScopeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtle: Boolean = false,
    keepInView: Boolean = false,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(LocalAppShapes.current.full)
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gutter = with(LocalDensity.current) { SCOPE_GUTTER.toPx() }
    if (keepInView && selected) {
        LaunchedEffect(size) {
            if (size != IntSize.Zero) {
                requester.bringIntoView(Rect(-gutter, 0f, size.width + gutter, size.height.toFloat()))
            }
        }
    }
    Text(
        text = text,
        color = when {
            selected -> colors.bg
            subtle -> colors.muted
            else -> colors.ink2
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .bringIntoViewRequester(requester)
            .onSizeChanged { size = it }
            .background(if (selected) colors.ink else colors.surface, shape)
            .border(1.dp, if (selected) colors.ink else colors.line, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
