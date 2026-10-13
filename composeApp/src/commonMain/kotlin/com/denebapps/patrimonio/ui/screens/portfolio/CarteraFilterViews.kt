package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.EmptyState
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.GhostButton
import com.denebapps.patrimonio.ui.components.LinkButton
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes

/** What the filters of Cartera can do, so they can be shown without a ViewModel. */
internal class CarteraFilterActions(
    val onToggle: (FilterDimension, String) -> Unit,
    val onRemove: (FilterDimension, String) -> Unit,
    val onClear: () -> Unit,
)

/** Side of the round filter button; the same circle as the icon buttons of the headers, a bit smaller. */
private val FILTER_BUTTON_SIZE = 36.dp
private val FILTER_BADGE_SIZE = 16.dp

/**
 * The round filter button above the list: the filter icon, and the number of active filters in a badge once there are
 * some (then the button is filled, like a chosen chip). It reads "Filtrar la cartera" and, with filters on, how many.
 */
@Composable
internal fun FilterButton(activeCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val active = activeCount > 0
    val description = if (active) "Filtrar la cartera, $activeCount activos" else "Filtrar la cartera"
    Box(
        modifier = modifier
            .size(FILTER_BUTTON_SIZE)
            .background(if (active) colors.ink else colors.surface, CircleShape)
            .border(1.dp, if (active) colors.ink else colors.line, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.filter,
            contentDescription = null,
            tint = if (active) colors.bg else colors.ink2,
            modifier = Modifier.size(17.dp),
        )
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .defaultMinSize(minWidth = FILTER_BADGE_SIZE, minHeight = FILTER_BADGE_SIZE)
                    .background(colors.brand, CircleShape)
                    .border(1.5.dp, colors.bg, CircleShape)
                    .padding(horizontal = 3.dp)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = activeCount.toString(),
                    color = colors.brandInk,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** The active filters as chips above the list, each with a cross that removes it; scrolls sideways when many. */
@Composable
internal fun ActiveFilterChips(
    active: List<ActiveFilterUi>,
    onRemove: (FilterDimension, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(LocalAppShapes.current.full)
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        active.forEach { filter ->
            Row(
                modifier = Modifier
                    .background(colors.brandSoft, shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Quitar el filtro ${filter.label}",
                        onClick = { onRemove(filter.dimension, filter.key) },
                    )
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = filter.label,
                    color = colors.brand,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Icon(
                    AppIcons.close,
                    contentDescription = null,
                    tint = colors.brand,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** The muted start of the list header: "Filtrado · N de M productos" while filters are on, "Productos" otherwise. */
@Composable
internal fun FilterSummaryLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalAppColors.current.muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

/** What the list shows when the scope has products but none passes the filters. */
@Composable
internal fun NoMatchesState(onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        EmptyState(
            icon = AppIcons.filter,
            title = "Ningún producto coincide con los filtros",
            message = "Prueba con otra combinación o quita los filtros para ver toda la cartera.",
        )
        GhostButton(text = "Quitar filtros", onClick = onClear)
    }
}

/**
 * The sheet that chooses the filters: one group of chips per dimension (class, bank or broker, type), only with the
 * values the scope has. Every tap applies at once, so the list behind already shows the result; "Limpiar" forgets all
 * the filters and "Listo" closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CarteraFilterSheet(filter: CarteraFilterUi, actions: CarteraFilterActions, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Filtrar",
                    color = colors.ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (filter.active.isNotEmpty()) LinkButton(text = "Limpiar", onClick = actions.onClear)
            }
            FilterGroup("Clase de activo", FilterDimension.ASSET_CLASS, filter.assetClasses, actions.onToggle)
            FilterGroup("Bróker", FilterDimension.BROKER, filter.brokers, actions.onToggle)
            FilterGroup("Tipo", FilterDimension.TYPE, filter.types, actions.onToggle)
            PrimaryButton(text = "Listo", onClick = onDismiss, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(
    title: String,
    dimension: FilterDimension,
    options: List<FilterOptionUi>,
    onToggle: (FilterDimension, String) -> Unit,
) {
    if (options.isEmpty()) return
    Column {
        FormSectionLabel(text = title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                ScopeChip(
                    text = option.label,
                    selected = option.selected,
                    onClick = { onToggle(dimension, option.key) },
                )
            }
        }
    }
}
