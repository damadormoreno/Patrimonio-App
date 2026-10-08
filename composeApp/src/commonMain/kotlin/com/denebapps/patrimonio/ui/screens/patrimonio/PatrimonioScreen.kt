package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.AccountAssignment
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.ui.components.EmptyState
import com.denebapps.patrimonio.ui.components.FabContentBottomSpacing
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.components.PillTone
import com.denebapps.patrimonio.ui.components.ScreenHeader
import com.denebapps.patrimonio.ui.components.SectionRow
import com.denebapps.patrimonio.ui.components.typeIcon
import com.denebapps.patrimonio.ui.components.typeTone
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.screens.savings.SavingsGoalRowUi
import com.denebapps.patrimonio.ui.screens.savings.SavingsGoalsUiState
import com.denebapps.patrimonio.ui.screens.savings.SavingsGoalsViewModel
import com.denebapps.patrimonio.ui.screens.savings.SharedBalanceNoticeUi
import com.denebapps.patrimonio.ui.screens.savings.trackedBalanceCaption
import com.denebapps.patrimonio.ui.theme.AppShadows
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import com.denebapps.patrimonio.ui.theme.instrumentSerifItalicFontFamily
import org.koin.compose.viewmodel.koinViewModel

/** EUR is always 2 decimals; aggregated [Money] values are EUR by convention (see [Money] KDoc). */
private const val EUR_MINOR_SCALE = 100L

/**
 * Patrimonio tab: net-worth hero + sparkline, activos/pasivos stat toggle, Mis-grupos entry,
 * composition bar, per-group lists, and empty states. Ports `design-reference/patrimonio.jsx`.
 * [onOpenGroups] defaults to a no-op — its real navigation destination (`Grupos`) is wired in a
 * later slice of this change; the affordance renders now (present-but-inert) so the visual gate
 * covers the full screen. [onAddItem] is wired by [com.denebapps.patrimonio.ui.navigation.MainScaffold]
 * to push `AddPatrimonio`. [onViewChange] mirrors [PatrimonioViewModel.state]'s `view` field up to
 * the caller (fired on every change, including the initial value) — [MainScaffold]'s FAB lives
 * outside this composable's scope but still needs the active Activos/Pasivos toggle to route the FAB
 * tap correctly (design.md Data Flow: `FAB(Patrimonio) -> navigate(AddPatrimonio)`). The Metas de
 * ahorro section is backed by a second, independent [SavingsGoalsViewModel] (design.md Decision 8) —
 * [onNewGoal]/[onGoalTap] are wired by `MainScaffold` to push `NewGoal`/`GoalAllocate(goalId)`. Tapping or
 * long-pressing an account calls [onEditItem] with the active view and the account id.
 */
@Composable
fun PatrimonioScreen(
    modifier: Modifier = Modifier,
    viewModel: PatrimonioViewModel = koinViewModel(),
    savingsGoalsViewModel: SavingsGoalsViewModel = koinViewModel(),
    onOpenGroups: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onAddItem: (PatrimonioView, String?) -> Unit = { _, _ -> },
    onEditItem: (PatrimonioView, String) -> Unit = { _, _ -> },
    onViewChange: (PatrimonioView) -> Unit = {},
    onNewGoal: () -> Unit = {},
    onGoalTap: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val savingsState by savingsGoalsViewModel.state.collectAsState()
    val colors = LocalAppColors.current

    LaunchedEffect(state.view) { onViewChange(state.view) }

    Column(modifier.fillMaxSize().background(colors.bg)) {
        ScreenHeader(
            title = "Patrimonio",
            eyebrow = state.monthLabel,
            right = { HeaderIconBtn(icon = AppIcons.calendar, onClick = onOpenHistory, label = "Evolución mensual") },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            NetWorthCard(state)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PatrimonioStat(
                    label = "Activos",
                    amount = state.totalAssets,
                    count = state.assetCount,
                    isAsset = true,
                    active = state.view == PatrimonioView.ACTIVOS,
                    onClick = { viewModel.onViewSelect(PatrimonioView.ACTIVOS) },
                    modifier = Modifier.weight(1f),
                )
                PatrimonioStat(
                    label = "Pasivos",
                    amount = state.totalLiabs,
                    count = state.liabCount,
                    isAsset = false,
                    active = state.view == PatrimonioView.PASIVOS,
                    onClick = { viewModel.onViewSelect(PatrimonioView.PASIVOS) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.view == PatrimonioView.ACTIVOS) {
                MisGruposEntry(
                    groupsCount = state.groupsCount,
                    onClick = onOpenGroups,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            MetasSection(
                state = savingsState,
                onNewGoal = onNewGoal,
                onGoalTap = onGoalTap,
                modifier = Modifier.padding(top = 14.dp),
            )

            if (state.isEmpty) {
                PatrimonioEmptyState(
                    view = state.view,
                    onAddItem = { onAddItem(state.view, null) },
                    modifier = Modifier.padding(top = 14.dp),
                )
            } else {
                val viewLabel = if (state.view == PatrimonioView.ACTIVOS) "Activos" else "Pasivos"
                val viewTotal = if (state.view == PatrimonioView.ACTIVOS) state.totalAssets else state.totalLiabs

                SectionRow(
                    title = "Composición · $viewLabel",
                    trailing = { Text("${state.composition.size} grupos", color = colors.muted, fontSize = 12.sp) },
                    showDivider = false,
                )
                StackedShare(state.composition, modifier = Modifier.padding(bottom = 4.dp))

                SectionRow(
                    title = if (state.view == PatrimonioView.ACTIVOS) "Mis activos" else "Mis pasivos",
                    trailing = { Text(formatMoneyEs(viewTotal), color = colors.muted, fontSize = 12.sp) },
                    showDivider = false,
                )
                if (state.view == PatrimonioView.ACTIVOS) {
                    AccountFilterBar(
                        state = state,
                        onAssignmentChange = viewModel::onAssignmentFilterChange,
                        onTypeToggle = viewModel::onTypeFilterToggle,
                        onClear = viewModel::onClearFilters,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                if (state.groups.isEmpty() && state.filterSummary != null) {
                    Text(
                        text = "Ninguna cuenta coincide con el filtro.",
                        color = colors.muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    state.groups.forEach { group ->
                        PatrimonioGroupCard(
                            group = group,
                            onAdd = { onAddItem(state.view, group.groupId) },
                            onEditItem = { itemId -> onEditItem(state.view, itemId) },
                        )
                    }
                }

                AddPatrimonioCta(
                    label = if (state.view == PatrimonioView.ACTIVOS) "Nuevo activo" else "Nuevo pasivo",
                    onClick = { onAddItem(state.view, null) },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Text(
                text = "Actualizado a ${state.monthLabel.lowercase()} · saldos en EUR",
                color = colors.muted2,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
            )
            Spacer(Modifier.height(FabContentBottomSpacing))
        }
    }
}

@Composable
private fun NetWorthCard(state: PatrimonioUiState, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current
    val serif = instrumentSerifItalicFontFamily()

    val intPart = formatGroupedInt(state.netWorth.minorUnits / EUR_MINOR_SCALE)
    val decPart = (kotlin.math.abs(state.netWorth.minorUnits) % EUR_MINOR_SCALE).toString().padStart(2, '0')

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .then(AppShadows.sh2(shapes.xl))
            .background(
                brush = Brush.verticalGradient(listOf(colors.surface2, colors.surface)),
                shape = RoundedCornerShape(shapes.xl),
            )
            .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(shapes.xl))
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "PATRIMONIO NETO",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Pill(text = "EUR · €", tone = PillTone.Neutral)
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = buildAnnotatedString {
                    append(intPart)
                    withStyle(SpanStyle(color = colors.ink.copy(alpha = 0.45f))) {
                        append(",")
                        append(decPart)
                    }
                },
                color = colors.ink,
                fontSize = 48.sp,
                fontFamily = serif,
                fontStyle = FontStyle.Italic,
                lineHeight = 50.4.sp,
            )
            Text(text = "€", color = colors.ink2, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        state.delta?.let { delta -> NetWorthDeltaPill(netWorth = state.netWorth, delta = delta) }
        if (state.sparkValues.size >= 2) {
            NetWorthSparkline(values = state.sparkValues, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun NetWorthDeltaPill(netWorth: Money, delta: Money, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current
    val previous = netWorth - delta
    val positive = delta.minorUnits >= 0
    val pct = deltaPercentage(delta, previous)
    val toneColor = if (positive) colors.income else colors.expense
    val toneBg = if (positive) colors.incomeSoft else colors.expenseSoft
    val sign = if (positive) "+" else "−"

    Row(
        modifier = modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .background(color = toneBg, shape = RoundedCornerShape(shapes.full))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = if (positive) AppIcons.arrowUp else AppIcons.arrowDown,
                contentDescription = null,
                tint = toneColor,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "$sign${formatPct(pct)}%",
                color = toneColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "$sign${formatMoneyEs(Money(kotlin.math.abs(delta.minorUnits)))} · vs. mes anterior",
            color = colors.muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun PatrimonioStat(
    label: String,
    amount: Money,
    count: Int,
    isAsset: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val fg = if (isAsset) colors.income else colors.expense
    val bg = if (isAsset) colors.incomeSoft else colors.expenseSoft

    Column(
        modifier = modifier
            .background(color = colors.surface, shape = RoundedCornerShape(16.dp))
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) colors.ink else colors.line,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(20.dp).background(bg, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isAsset) AppIcons.arrowUp else AppIcons.arrowDown,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(text = label, color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = count.toString(),
                color = colors.muted,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(colors.bg2, RoundedCornerShape(999.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Text(
            text = formatMoneyEs(amount),
            color = colors.ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MisGruposEntry(groupsCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).background(colors.brandSoft, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.folder, contentDescription = null, tint = colors.brand, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Mis grupos", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Vistas personalizadas · una cuenta, varios grupos",
                color = colors.muted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Text(
            text = groupsCount.toString(),
            color = colors.muted,
            fontSize = 11.sp,
            modifier = Modifier
                .background(colors.bg2, RoundedCornerShape(999.dp))
                .border(1.dp, colors.line, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 1.dp),
        )
        Icon(AppIcons.chevronR, contentDescription = null, tint = colors.muted, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun PatrimonioEmptyState(view: PatrimonioView, onAddItem: () -> Unit, modifier: Modifier = Modifier) {
    val isActivos = view == PatrimonioView.ACTIVOS
    Column(modifier = modifier) {
        EmptyState(
            icon = if (isActivos) AppIcons.wallet else AppIcons.card,
            title = if (isActivos) "Aún no hay activos" else "Aún no hay pasivos",
            message = if (isActivos) {
                "Añade tu primera cuenta, inversión o propiedad."
            } else {
                "Añade tu primer préstamo, hipoteca o tarjeta."
            },
        )
        AddPatrimonioCta(
            label = if (isActivos) "Nuevo activo" else "Nuevo pasivo",
            onClick = onAddItem,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun StackedShare(groups: List<GroupShareUi>, modifier: Modifier = Modifier) {
    if (groups.isEmpty()) return
    val colors = LocalAppColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.bg2)
                .border(1.dp, colors.line2, RoundedCornerShape(999.dp)),
        ) {
            groups.forEach { share ->
                Box(
                    modifier = Modifier
                        .weight(share.total.minorUnits.toFloat().coerceAtLeast(0.0001f))
                        .fillMaxHeight()
                        .background(groupTone(share.groupId)),
                )
            }
        }
        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { share ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).background(groupTone(share.groupId), RoundedCornerShape(2.dp)))
                    Text(text = share.label, color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(text = "${share.sharePct}%", color = colors.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PatrimonioGroupCard(
    group: PatrimonioGroupUi,
    onAdd: () -> Unit,
    onEditItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val tone = groupTone(group.groupId)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(
                colors.surface2,
            ).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp).background(tone, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(
                    groupIcon(group.groupId),
                    contentDescription = null,
                    tint = colors.surface2,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(group.label, color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (group.filtered) {
                        group.itemCountLabel
                    } else {
                        "${group.itemCountLabel} · ${group.sharePct}% del total"
                    },
                    color = colors.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Text(text = formatMoneyEs(group.total), color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(colors.bg2, CircleShape)
                    .border(1.dp, colors.line, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAdd,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.plus,
                    contentDescription = "Añadir a ${group.label}",
                    tint = colors.ink2,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            group.items.forEachIndexed { index, item ->
                PatrimonioItemRow(
                    item = item,
                    tone = tone,
                    showDivider = index != group.items.lastIndex,
                    onEdit = { onEditItem(item.id) },
                )
            }
        }
    }
}

@Composable
private fun PatrimonioItemRow(
    item: PatrimonioItemUi,
    tone: Color,
    showDivider: Boolean,
    onEdit: () -> Unit,
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
            // A tap and a long press both open the editor.
            .combinedClickable(onClickLabel = "Editar", onLongClick = onEdit, onClick = onEdit)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(6.dp).background(tone, CircleShape))
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
            item.subtitle?.let {
                Text(
                    text = it,
                    color = colors.muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = formatItemAmount(item.amount, item.currency),
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (item.currency != Currency.EUR) {
                Text(
                    text = item.currency.code,
                    color = colors.muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.05.sp,
                    modifier = Modifier
                        .background(colors.bg2, RoundedCornerShape(999.dp))
                        .border(1.dp, colors.line, RoundedCornerShape(999.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Marks an account followed by a goal ([AppIcons.spark], the goals icon) or held by a group. */
@Composable
private fun UsageIcon(icon: ImageVector, description: String) {
    Icon(icon, contentDescription = description, tint = LocalAppColors.current.muted, modifier = Modifier.size(13.dp))
}

/**
 * Filters the asset list: an assignment menu (goals / groups / unassigned) and one chip per asset
 * type, combinable. While a filter is active, a line under the chips shows what it leaves; the header
 * totals and the composition bar stay global.
 */
@Composable
private fun AccountFilterBar(
    state: PatrimonioUiState,
    onAssignmentChange: (AccountAssignment) -> Unit,
    onTypeToggle: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val assignment = state.filter.assignment
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                FilterChip(
                    label = if (assignment == AccountAssignment.ALL) "Asignación" else assignmentLabel(assignment),
                    selected = assignment != AccountAssignment.ALL,
                    onClick = { menuOpen = true },
                    trailingIcon = AppIcons.chevronD,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    AccountAssignment.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(assignmentLabel(option)) },
                            onClick = {
                                menuOpen = false
                                onAssignmentChange(option)
                            },
                            trailingIcon = {
                                if (option == assignment) {
                                    Icon(imageVector = AppIcons.check, contentDescription = null, tint = colors.brand)
                                }
                            },
                        )
                    }
                }
            }
            state.typeFilterOptions.forEach { option ->
                FilterChip(label = option.label, selected = option.selected, onClick = { onTypeToggle(option.group) })
            }
        }
        state.filterSummary?.let { summary ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val accounts = if (summary.count == 1) "1 cuenta" else "${summary.count} cuentas"
                Text(
                    text = "Filtrado: ${formatMoneyEs(summary.total)} · $accounts",
                    color = colors.ink2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Quitar filtros",
                    color = colors.brand,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClear,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, trailingIcon: ImageVector? = null) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .background(if (selected) colors.ink else colors.surface, shape)
            .border(1.dp, if (selected) colors.ink else colors.line, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = if (selected) colors.bg else colors.ink2,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
        trailingIcon?.let { icon ->
            val tint = if (selected) colors.bg else colors.muted
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        }
    }
}

private fun assignmentLabel(assignment: AccountAssignment): String = when (assignment) {
    AccountAssignment.ALL -> "Todas"
    AccountAssignment.IN_GOALS -> "En metas"
    AccountAssignment.IN_GROUPS -> "En grupos"
    AccountAssignment.UNASSIGNED -> "Sin asignar"
}

@Composable
private fun AddPatrimonioCta(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.plus, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = label, color = colors.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Metas de ahorro section (spec: `savings-goals-ui` — Goals List, shared-balance notices,
 * Empty State and Create CTA). Renders below the Activos/Pasivos block regardless of [PatrimonioView]
 * — goals are an independent concept from the asset/liability toggle. [onNewGoal]/[onGoalTap] push
 * `NewGoal`/`GoalAllocate(goalId)` (design.md Data Flow).
 */
@Composable
private fun MetasSection(
    state: SavingsGoalsUiState,
    onNewGoal: () -> Unit,
    onGoalTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current

    Column(modifier = modifier) {
        SectionRow(
            title = "Metas de ahorro",
            trailing = { Text("${state.goals.size}", color = colors.muted, fontSize = 12.sp) },
            showDivider = false,
        )

        state.sharedBalanceNotices.forEach { notice ->
            NoticeBanner(message = sharedBalanceMessage(notice), modifier = Modifier.padding(bottom = 10.dp))
        }

        if (state.isEmpty) {
            SavingsGoalsEmptyState(onNewGoal = onNewGoal)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.goals.forEach { goal ->
                    SavingsGoalRow(goal = goal, onClick = { onGoalTap(goal.id) })
                }
            }
            AddPatrimonioCta(label = "Nueva meta", onClick = onNewGoal, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** «Viaje» y «Coche» siguen el saldo de «Ahorro»: cada una lo cuenta entero. */
private fun sharedBalanceMessage(notice: SharedBalanceNoticeUi): String {
    val names = notice.goalNames.map { "«$it»" }
    val goals = names.dropLast(1).joinToString(", ") + " y " + names.last()
    return "$goals siguen el saldo de ${notice.targetLabel}: cada una lo cuenta entero."
}

@Composable
private fun NoticeBanner(message: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.alertSoft, RoundedCornerShape(14.dp))
            .border(1.dp, colors.alert, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AppIcons.info, contentDescription = null, tint = colors.alert, modifier = Modifier.size(16.dp))
        Text(
            text = message,
            color = colors.alert,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun SavingsGoalRow(goal: SavingsGoalRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = goal.name,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (goal.targetReached) {
                Icon(AppIcons.check, contentDescription = null, tint = colors.income, modifier = Modifier.size(13.dp))
                Text(
                    text = "Meta alcanzada",
                    color = colors.income,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatItemAmount(goal.progress, goal.target.currency),
                color = colors.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "de ${formatItemAmount(goal.target.amount, goal.target.currency)}",
                color = colors.muted,
                fontSize = 12.sp,
            )
        }
        SavingsGoalProgressBar(pct = goal.progressPct, modifier = Modifier.padding(top = 8.dp))
        trackedBalanceCaption(goal)?.let { caption ->
            Text(
                text = caption,
                color = colors.muted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** [pct] MAY exceed 100 (overfunding) — the fill width is visually clamped to the bar's bounds, but
 *  the numeric `%` shown alongside it (in [SavingsGoalRow]'s amount row) is never clamped. */
@Composable
private fun SavingsGoalProgressBar(pct: Int, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val fraction = (pct / 100f).coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.bg2)
            .border(1.dp, colors.line2, RoundedCornerShape(999.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                .fillMaxHeight()
                .background(if (pct >= 100) colors.income else colors.brand),
        )
    }
}

@Composable
private fun SavingsGoalsEmptyState(onNewGoal: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        EmptyState(
            icon = AppIcons.spark,
            title = "Aún no tienes metas",
            message = "Crea una meta de ahorro para hacer seguimiento de tu progreso.",
        )
        AddPatrimonioCta(label = "Nueva meta", onClick = onNewGoal)
    }
}

/** Icon/tone of [GroupShareUi.groupId]/[PatrimonioGroupUi.groupId], a type id ([typeIcon]/[typeTone]). */
private fun groupIcon(groupId: String): ImageVector = typeIcon(groupId)

@Composable
private fun groupTone(groupId: String): Color = typeTone(groupId)

/** `null` when [previous] is zero (matches [com.denebapps.patrimonio.ui.screens.home.HomeViewModel]'s
 *  `computeDelta` zero-guard convention). */
private fun deltaPercentage(delta: Money, previous: Money): Double {
    if (previous.minorUnits == 0L) return 0.0
    return kotlin.math.abs(delta.minorUnits.toDouble()) / kotlin.math.abs(previous.minorUnits.toDouble()) * 100.0
}

/** Groups an integer's digits with `.` thousands separators (es-ES style), preserving the sign. */
private fun formatGroupedInt(value: Long): String {
    val negative = value < 0
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (negative) "-$grouped" else grouped
}

private fun formatMoneyEs(money: Money): String {
    val intPart = formatGroupedInt(money.minorUnits / EUR_MINOR_SCALE)
    val decPart = (kotlin.math.abs(money.minorUnits) % EUR_MINOR_SCALE).toString().padStart(2, '0')
    return "$intPart,$decPart €"
}

/** Formats an item's amount in its OWN [currency] (not EUR-converted) — e.g. USD/GBP 2 decimals,
 *  JPY 0 decimals — with a trailing currency symbol, matching `PatrimonioItemRow` in the JSX. */
private fun formatItemAmount(money: Money, currency: Currency): String {
    val decimals = currency.decimals
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val abs = kotlin.math.abs(money.minorUnits)
    val intPart = if (decimals == 0) abs else abs / factor
    val grouped = intPart.toString().reversed().chunked(3).joinToString(".").reversed()
    val symbol = currencySymbol(currency)
    return if (decimals == 0) {
        "$grouped $symbol"
    } else {
        val fracPart = (abs % factor).toString().padStart(decimals, '0')
        "$grouped,$fracPart $symbol"
    }
}

private fun currencySymbol(currency: Currency): String = when (currency) {
    Currency.USD -> "$"
    Currency.JPY -> "¥"
    Currency.GBP -> "£"
    Currency.EUR -> "€"
}

/** One-decimal, comma-separated percentage string (es-ES style, e.g. `"50,0"`). */
private fun formatPct(pct: Double): String {
    val rounded = kotlin.math.round(pct * 10) / 10.0
    val parts = rounded.toString().split(".")
    val decDigit = parts.getOrElse(1) { "0" }.take(1).ifEmpty { "0" }
    return "${parts[0]},$decDigit"
}
