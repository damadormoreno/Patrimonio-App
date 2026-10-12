package com.denebapps.patrimonio.ui.screens.importing

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.GhostButton
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.screens.portfolio.PortfolioPicker
import com.denebapps.patrimonio.ui.screens.portfolio.filterUnitsInput
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import org.koin.compose.viewmodel.koinViewModel

private const val HELP_TEXT =
    "En la web de MyInvestor, entra en cada fondo → Plusvalías y minusvalías → descarga el CSV. " +
        "No le cambies el nombre: lleva el ISIN."

/** What the import screen can do, so [ImportContent] can be shown without a ViewModel. */
internal class ImportCallbacks(
    val onBack: () -> Unit,
    val onPickFiles: () -> Unit,
    val onUnitsChange: (cardId: Int, text: String) -> Unit,
    val onPortfolioChange: (cardId: Int, portfolioId: String) -> Unit,
    val onRemove: (cardId: Int) -> Unit,
    val onImport: () -> Unit,
)

/**
 * Pushed screen (`ImportMyInvestor`): pick the "Plusvalías y minusvalías" CSV of one or several funds, check what
 * each would bring and import them. [onBack] returns to Cartera, both to leave and once the import is done.
 */
@Composable
fun ImportMyInvestorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportMyInvestorViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // No extension filter, as in the backup import: some Android document providers report a CSV with a type the
    // picker's extension mapping does not match and would grey it out. The content is checked when it is read.
    val picker = rememberFilePickerLauncher(type = FileKitType.File(), mode = FileKitMode.Multiple()) { files ->
        files?.let { picked ->
            viewModel.onFilesPicked(picked.map { file -> PickedFile(file.name) { file.readBytes() } })
        }
    }
    val callbacks = remember(viewModel, onBack, picker) {
        ImportCallbacks(
            onBack = onBack,
            onPickFiles = picker::launch,
            onUnitsChange = viewModel::onUnitsChange,
            onPortfolioChange = viewModel::onPortfolioChange,
            onRemove = viewModel::onRemove,
            onImport = viewModel::onImport,
        )
    }
    ImportContent(state = state, callbacks = callbacks, modifier = modifier)
}

@Composable
internal fun ImportContent(state: ImportUiState, callbacks: ImportCallbacks, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val locked = state.importing || state.finished

    Column(modifier.fillMaxSize().background(colors.bg)) {
        TopBar(onBack = callbacks.onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.finished) HelpCard()
            state.cards.forEach { card ->
                ImportCard(card = card, state = state, locked = locked, callbacks = callbacks)
            }
            if (!locked) {
                GhostButton(
                    text = if (state.cards.isEmpty()) "Elegir archivos" else "Añadir otro archivo",
                    onClick = callbacks.onPickFiles,
                    leading = AppIcons.plus,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        BottomAction(state = state, callbacks = callbacks)
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderIconBtn(icon = AppIcons.chevronL, onClick = onBack, label = "Volver a Cartera")
        Text(
            text = "Importar de MyInvestor",
            color = colors.ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HelpCard() {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.infoSoft, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = AppIcons.info,
            contentDescription = null,
            tint = colors.info,
            modifier = Modifier.size(18.dp),
        )
        Text(text = HELP_TEXT, color = colors.info, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun BottomAction(state: ImportUiState, callbacks: ImportCallbacks) {
    val colors = LocalAppColors.current
    Box(Modifier.fillMaxWidth().background(colors.bg).padding(horizontal = 20.dp, vertical = 12.dp)) {
        when {
            state.finished -> PrimaryButton(text = "Listo", onClick = callbacks.onBack)
            state.importing -> PrimaryButton(text = "Importando…", onClick = {}, enabled = false)
            else -> PrimaryButton(text = "Importar", onClick = callbacks.onImport, enabled = state.canImport)
        }
    }
}

@Composable
private fun ImportCard(card: ImportCardUi, state: ImportUiState, locked: Boolean, callbacks: ImportCallbacks) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardHeader(card = card, canRemove = !locked, onRemove = { callbacks.onRemove(card.id) })
        when (card.status) {
            ImportCardStatus.LOADING -> LoadingLine()
            ImportCardStatus.ERROR -> MessageBox(text = card.errorText.orEmpty(), kind = MessageKind.ERROR)
            ImportCardStatus.READY -> ReadyBody(card = card, state = state, locked = locked, callbacks = callbacks)
        }
        card.result?.let { ResultLine(it) }
    }
}

@Composable
private fun CardHeader(card: ImportCardUi, canRemove: Boolean, onRemove: () -> Unit) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                text = card.title ?: card.fileName,
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.title != null) {
                card.subtitle?.let {
                    Text(
                        text = "Cartera · $it",
                        color = colors.ink2,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = card.fileName,
                    color = colors.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (canRemove) {
            Icon(
                imageVector = AppIcons.close,
                contentDescription = "Quitar archivo",
                tint = colors.muted,
                modifier = Modifier
                    .size(34.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRemove,
                    )
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun LoadingLine() {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.ink2)
        Text(text = "Leyendo el archivo…", color = colors.muted, fontSize = 13.sp)
    }
}

@Composable
private fun ReadyBody(card: ImportCardUi, state: ImportUiState, locked: Boolean, callbacks: ImportCallbacks) {
    val colors = LocalAppColors.current
    val imported = card.result != null
    if (card.isNewFund && state.canPickPortfolio && !locked) {
        PortfolioPicker(
            options = state.portfolios,
            selectedId = card.portfolioId,
            onSelect = { callbacks.onPortfolioChange(card.id, it) },
        )
    }
    card.summaryText?.let { Text(text = it, color = colors.ink2, fontSize = 13.sp, lineHeight = 18.sp) }
    if (imported) return
    card.notice?.let { MessageBox(text = it, kind = MessageKind.INFO) }
    UnitsEntry(card = card, enabled = !locked, onChange = { callbacks.onUnitsChange(card.id, it) })
    card.warnings.forEach { MessageBox(text = it, kind = MessageKind.WARNING) }
}

@Composable
private fun UnitsEntry(card: ImportCardUi, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Column {
        FormSectionLabel("Participaciones actuales")
        UnitsInput(text = card.unitsText, onTextChange = onChange, enabled = enabled, isError = card.unitsError != null)
        card.unitsError?.let {
            Text(text = it, color = colors.expense, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        card.unitsNote?.let {
            Text(text = it, color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Right-aligned decimal field for the units of a fund, sized for a card. */
@Composable
private fun UnitsInput(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface2, shape)
            .border(1.dp, if (isError) colors.expense else colors.line, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { onTextChange(filterUnitsInput(it)) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(
                color = colors.ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.weight(1f),
        )
        Text(text = "part.", color = colors.muted2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private enum class MessageKind { INFO, WARNING, ERROR }

/** A short message in a tinted box: a notice (info), a warning (amber) or why a file cannot be imported (error). */
@Composable
private fun MessageBox(text: String, kind: MessageKind, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val (background, foreground) = when (kind) {
        MessageKind.INFO -> colors.infoSoft to colors.info
        MessageKind.WARNING -> colors.alertSoft to colors.alert
        MessageKind.ERROR -> colors.expenseSoft to colors.expense
    }
    Text(
        text = text,
        color = foreground,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ResultLine(result: ImportCardResultUi) {
    val colors = LocalAppColors.current
    val color: Color = if (result.success) colors.income else colors.expense
    val icon: ImageVector = if (result.success) AppIcons.check else AppIcons.close
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(
            text = result.text,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}
