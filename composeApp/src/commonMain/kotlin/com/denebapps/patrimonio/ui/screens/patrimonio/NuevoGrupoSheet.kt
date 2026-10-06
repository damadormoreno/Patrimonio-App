package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel

/** Full-height create-account-group destination (spec: Create Account Group Form). Rendered as a
 *  `composable<NuevoGrupo>` entry, sharing [GruposViewModel]'s NuevoGrupo form state (design.md
 *  Decision 1 / File Changes). Pops the back stack once [GruposViewModel.navigateBack] emits
 *  (`AddPatrimonioSheet` precedent). Ports `design-reference/grupos.jsx`'s `NuevoGrupoSheet`.
 */
@Composable
fun NuevoGrupoSheet(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: GruposViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = colors.bg, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp, bottom = 6.dp)
                .height(4.dp)
                .width(36.dp)
                .background(color = colors.line, shape = RoundedCornerShape(999.dp)),
        )

        NuevoGrupoTopBar(canSave = state.canSaveNewGroup, onCancel = onNavigateBack, onSave = viewModel::onSaveNewGroup)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            TitleField(title = state.title, onTitleChange = viewModel::onTitleChange)

            CurrencyRow()

            ShowBalanceToggle(showBalance = state.showBalance, onToggle = viewModel::onShowBalanceToggle)

            Text(
                text = "QUÉ CUENTAS INCLUIR EN ESTE GRUPO",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp),
            ) {
                state.assetChecklist.forEach { item ->
                    AssetChecklistRow(item = item, onToggle = { viewModel.onAssetToggle(item.id) })
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val label = if (state.selectedCount == 1) "cuenta seleccionada" else "cuentas seleccionadas"
                Text(
                    text = "${state.selectedCount} $label",
                    color = colors.muted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (state.selectedCount > 0) formatMoneyEsGrupos(state.selectedTotal) else "",
                    color = colors.muted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun NuevoGrupoTopBar(canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    val colors = LocalAppColors.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(colors.surface, CircleShape)
                .border(1.dp, colors.line, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.close, contentDescription = "Volver", tint = colors.ink2, modifier = Modifier.size(16.dp))
        }
        Text(
            text = "Nuevo grupo de cuentas",
            color = colors.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).sp,
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(if (canSave) colors.ink else colors.surface, CircleShape)
                .border(1.dp, if (canSave) colors.ink else colors.line, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = canSave,
                    onClick = onSave,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.check,
                contentDescription = "Guardar grupo",
                tint = if (canSave) colors.bg else colors.muted2,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun TitleField(title: String, onTitleChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(14.dp))
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (title.isEmpty()) {
            Text(text = "Título · Ej: Fondo emergencia", color = colors.muted, fontSize = 15.sp)
        }
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun CurrencyRow() {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Moneda", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(text = "General (EUR · €)", color = colors.muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ShowBalanceToggle(showBalance: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Mostrar saldo de este grupo\nen la lista de grupos",
            color = colors.ink,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(28.dp)
                .background(if (showBalance) colors.income else colors.bg2, RoundedCornerShape(999.dp))
                .border(1.dp, if (showBalance) colors.income else colors.line, RoundedCornerShape(999.dp)),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = if (showBalance) 20.dp else 2.dp, top = 2.dp)
                    .size(22.dp)
                    .background(colors.surface2, CircleShape),
            )
        }
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun AssetChecklistRow(item: AssetChecklistItemUi, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(30.dp).background(assetGroupTone(item.group), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = assetGroupIcon(item.group),
                contentDescription = null,
                tint = colors.surface2,
                modifier = Modifier.size(15.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            item.subtitle?.let {
                Text(
                    text = it,
                    color = colors.muted,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 1.dp),
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    color = if (item.selected) colors.ink else Color.Transparent,
                    shape = CircleShape,
                )
                .border(1.5.dp, if (item.selected) colors.ink else colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (item.selected) {
                Icon(AppIcons.check, contentDescription = null, tint = colors.bg, modifier = Modifier.size(14.dp))
            }
        }
    }
}
