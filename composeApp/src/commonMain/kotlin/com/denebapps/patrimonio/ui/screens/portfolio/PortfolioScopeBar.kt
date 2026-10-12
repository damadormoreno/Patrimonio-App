package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.LinkButton
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes

/** What the portfolio selector and its management menu can do, so they can be shown without a ViewModel. */
internal class PortfolioScopeActions(
    val onSelect: (String?) -> Unit,
    val onNew: () -> Unit,
    val onRename: (String) -> Unit,
    val onDelete: (String) -> Unit,
)

/**
 * The scope of the Cartera tab under its header. With several portfolios, a row of chips (`Todas`, each portfolio, and
 * `+ Nueva`) that scrolls sideways; with one, just a discreet `Nueva cartera` link. Either way a `Gestionar` menu
 * renames or deletes the portfolio in scope ([CarteraUiState.managedPortfolioId]) while there is one. At the end of the
 * row, before `Gestionar`, `Filtrar` ([onFilter]) opens the filters of the list while the scope has products.
 */
@Composable
internal fun PortfolioScopeBar(
    state: CarteraUiState,
    actions: PortfolioScopeActions,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.showSelector) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopeChip(
                    text = "Todas",
                    selected = state.selectedPortfolioId == null,
                    onClick = { actions.onSelect(null) },
                )
                state.portfolios.forEach { portfolio ->
                    ScopeChip(
                        text = portfolio.name,
                        selected = portfolio.id == state.selectedPortfolioId,
                        onClick = { actions.onSelect(portfolio.id) },
                    )
                }
                ScopeChip(text = "+ Nueva", selected = false, subtle = true, onClick = actions.onNew)
            }
        } else {
            Spacer(Modifier.weight(1f))
            LinkButton(text = "Nueva cartera", onClick = actions.onNew)
        }
        if (state.filter.hasOptions) {
            FilterButton(activeCount = state.filter.active.size, onClick = onFilter)
        }
        state.managedPortfolioId?.let { id ->
            ManageMenu(portfolioId = id, deleteBlockedReason = state.deleteBlockedReason, actions = actions)
        }
    }
}

@Composable
internal fun ScopeChip(text: String, selected: Boolean, onClick: () -> Unit, subtle: Boolean = false) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(LocalAppShapes.current.full)
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

/** "Gestionar" with a menu: rename, and delete unless it is the last portfolio (then it says why it is disabled). */
@Composable
private fun ManageMenu(portfolioId: String, deleteBlockedReason: String?, actions: PortfolioScopeActions) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(LocalAppShapes.current.full)
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .border(1.dp, colors.line, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Gestionar la cartera",
                    onClick = { open = true },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "Gestionar", color = colors.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(AppIcons.chevronD, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(11.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Renombrar") },
                leadingIcon = { Icon(AppIcons.pencil, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onRename(portfolioId)
                },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Eliminar", color = if (deleteBlockedReason == null) colors.expense else colors.muted)
                        deleteBlockedReason?.let { Text(it, color = colors.muted, fontSize = 11.sp) }
                    }
                },
                leadingIcon = {
                    Icon(
                        AppIcons.trash,
                        contentDescription = null,
                        tint = if (deleteBlockedReason == null) colors.expense else colors.muted,
                    )
                },
                enabled = deleteBlockedReason == null,
                onClick = {
                    open = false
                    actions.onDelete(portfolioId)
                },
            )
        }
    }
}
