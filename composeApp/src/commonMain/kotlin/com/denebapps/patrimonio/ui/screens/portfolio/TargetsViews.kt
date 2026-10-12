package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.GhostButton
import com.denebapps.patrimonio.ui.components.LinkButton
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private val BAR_HEIGHT = 12.dp
private val TRACK_HEIGHT = 6.dp
private val MARKER_WIDTH = 2.dp
private val PERCENT_FIELD_WIDTH = 104.dp

/** What the editor sheet of the targets can do, so it can be shown without a ViewModel. */
internal class TargetsEditorActions(
    val onTargetChange: (AssetClass, String) -> Unit,
    val onToleranceChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onClear: () -> Unit,
    val onDismiss: () -> Unit,
)

// ---- the section of the detail ----------------------------------------------------------------------------

/**
 * "Objetivo de reparto": a note to pick a portfolio, the invitation to define the targets, or the portfolio against
 * them: a row per class, the band, where to put money, and the calculator. [onEdit] opens the editor.
 */
@Composable
internal fun TargetsSection(section: TargetSectionUi, onEdit: () -> Unit, onCalculatorChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    SectionLabel(TARGETS_TITLE)
    when (section) {
        is TargetSectionUi.PickPortfolio -> DetailCard {
            Text(
                text = section.message,
                color = colors.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
        is TargetSectionUi.Empty -> DetailCard {
            Text(
                text = section.message,
                color = colors.ink2,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
            PrimaryButton(
                text = TARGETS_DEFINE_LABEL,
                onClick = onEdit,
                modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
            )
        }
        is TargetSectionUi.Defined -> DefinedTargets(section, onEdit, onCalculatorChange)
    }
}

@Composable
private fun DefinedTargets(
    section: TargetSectionUi.Defined,
    onEdit: () -> Unit,
    onCalculatorChange: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    DetailCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = section.statusText,
                    color = if (section.outOfBand) colors.alert else colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = section.toleranceText, color = colors.muted, fontSize = 12.sp)
            }
            LinkButton(text = TARGETS_EDIT_LABEL, onClick = onEdit)
        }
        section.rows.forEach { TargetRow(it) }
        section.suggestion?.let { SuggestionBox(it, Modifier.padding(top = 14.dp)) }
        section.onlySellingNotes.forEach { NoteText(it) }
        section.unclassifiedNote?.let { NoteText(it) }
        CalculatorBlock(section.calculator, onCalculatorChange)
    }
}

@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        color = LocalAppColors.current.muted2,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** A class against its target: its name and drift, the two shares and a bar with a mark at the target. */
@Composable
private fun TargetRow(row: TargetRowUi) {
    val colors = LocalAppColors.current
    val driftColor = if (row.outOfBand) colors.alert else colors.muted
    Column(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(assetClassColor(row.assetClass), RoundedCornerShape(2.dp)))
            Text(
                text = row.label,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(text = row.driftText, color = driftColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(text = row.detailText, color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        TargetBar(
            actual = row.actualFraction,
            target = row.targetFraction,
            fill = assetClassColor(row.assetClass),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The share the class has as a bar, and a vertical mark where its target is. */
@Composable
private fun TargetBar(actual: Float, target: Float, fill: Color, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val track = colors.line
    val marker = colors.ink
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .drawBehind {
                val trackHeight = TRACK_HEIGHT.toPx()
                val top = (size.height - trackHeight) / 2
                val radius = CornerRadius(trackHeight / 2)
                drawRoundRect(track, Offset(0f, top), Size(size.width, trackHeight), radius)
                drawRoundRect(fill, Offset(0f, top), Size(size.width * actual.coerceIn(0f, 1f), trackHeight), radius)
                val x = size.width * target.coerceIn(0f, 1f)
                drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = MARKER_WIDTH.toPx())
            },
    )
}

/** Where to put money to get back on target without selling, in the info colors of the theme. */
@Composable
private fun SuggestionBox(suggestion: SuggestionUi, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.infoSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = suggestion.headline,
            color = colors.info,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp,
        )
        suggestion.lines.forEach { ContributionLine(it, colors.info) }
    }
}

@Composable
private fun ContributionLine(line: ContributionLineUi, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).background(assetClassColor(line.assetClass), RoundedCornerShape(2.dp)))
        Text(text = line.text, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** "¿Cuánto vas a aportar?": an amount, and how it would be split over the classes. */
@Composable
private fun CalculatorBlock(calculator: CalculatorUi, onChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Column(Modifier.padding(top = 16.dp, bottom = 14.dp)) {
        FormSectionLabel(CALCULATOR_LABEL)
        NumberField(
            text = calculator.amountText,
            onTextChange = onChange,
            suffix = "€",
            label = CALCULATOR_LABEL,
            isError = calculator.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        calculator.error?.let {
            Text(text = it, color = colors.expense, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (calculator.shares.isNotEmpty()) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                calculator.shares.forEach { ContributionLine(it, colors.ink2) }
            }
        }
    }
}

// ---- the editor -------------------------------------------------------------------------------------------

/**
 * The sheet that edits the targets: a percentage per class (all of them, in order), the live sum, the tolerance, and
 * "Guardar", which needs a sum of exactly 100 %. "Quitar objetivo" removes the targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TargetsEditorSheet(editor: TargetsEditorUi, actions: TargetsEditorActions) {
    val colors = LocalAppColors.current
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(text = TARGETS_TITLE, color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Qué porcentaje quieres en cada clase de activo. Tienen que sumar 100 %.",
                color = colors.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            editor.fields.forEach { field ->
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).background(assetClassColor(field.assetClass), RoundedCornerShape(2.dp)))
                    Text(
                        text = field.label,
                        color = colors.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        text = field.text,
                        onTextChange = { actions.onTargetChange(field.assetClass, it) },
                        suffix = "%",
                        label = field.label,
                        modifier = Modifier.width(PERCENT_FIELD_WIDTH),
                    )
                }
            }
            Text(
                text = editor.sumText,
                color = if (editor.sumValid) colors.income else colors.expense,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Margen (± puntos)",
                    color = colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    text = editor.toleranceText,
                    onTextChange = actions.onToleranceChange,
                    suffix = "pts",
                    label = "Margen en puntos",
                    isError = editor.toleranceError != null,
                    modifier = Modifier.width(PERCENT_FIELD_WIDTH),
                )
            }
            editor.toleranceError?.let { ErrorText(it) }
            editor.error?.let { ErrorText(it) }
            PrimaryButton(
                text = "Guardar",
                onClick = actions.onSave,
                enabled = editor.canSave,
                modifier = Modifier.padding(top = 18.dp),
            )
            if (editor.canRemove) {
                GhostButton(
                    text = "Quitar objetivo",
                    onClick = actions.onClear,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            LinkButton(
                text = "Cancelar",
                onClick = actions.onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        color = LocalAppColors.current.expense,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** A small right-aligned decimal field with its [suffix] on the right; [label] is what a screen reader says. */
@Composable
private fun NumberField(
    text: String,
    onTextChange: (String) -> Unit,
    suffix: String,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(10.dp)
    val style =
        TextStyle(color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    Row(
        modifier = modifier
            .background(colors.surface2, shape)
            .border(1.dp, if (isError) colors.expense else colors.line, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = style,
            cursorBrush = SolidColor(colors.ink),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty()) Text(text = "0", style = style.copy(color = colors.muted2))
                    field()
                }
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(text = suffix, color = colors.muted2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
