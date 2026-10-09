package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** Emojis offered for a type or an account; any other can be typed from the keyboard. */
val SUGGESTED_EMOJIS = listOf(
    "🏦", "💰", "💳", "🐷", "📈", "🪙", "💎", "🏠", "🏢", "🚗", "🏍️", "⛵",
    "✈️", "⌚", "🎨", "📚", "🎓", "🏥", "💼", "🎁", "🛒", "👪", "🧾", "🌱",
)

/**
 * Creates or edits a custom account type: name, emoji (suggested or typed) and colour. [onSave] gets the
 * trimmed values; it is enabled once there is a name and an emoji.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypeEditorDialog(
    title: String,
    onSave: (name: String, emoji: String, color: TypeColor) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialEmoji: String = SUGGESTED_EMOJIS.first(),
    initialColor: TypeColor = TypeColor.BLUE,
) {
    val colors = LocalAppColors.current
    var name by rememberSaveable { mutableStateOf(initialName) }
    var emoji by rememberSaveable { mutableStateOf(initialEmoji) }
    var colorName by rememberSaveable { mutableStateOf(initialColor.name) }
    val color = TypeColor.valueOf(colorName)
    val canSave = name.isNotBlank() && emoji.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TypeBadge(TypeLook("preview", name, color, emoji = emoji.ifBlank { null }), size = 44.dp)
                    TextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_TYPE_NAME) },
                        label = "Nombre",
                    )
                }
                EmojiPicker(selected = emoji, onSelect = { emoji = it })
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TypeColor.entries.forEach { option ->
                        val selected = option == color
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(option.toColor())
                                .border(if (selected) 3.dp else 0.dp, colors.ink, CircleShape)
                                .clickable { colorName = option.name },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    AppIcons.check,
                                    contentDescription = null,
                                    tint = colors.surface2,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), emoji.trim(), color) }, enabled = canSave) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * The suggested emojis plus a box to type any other. [selected] is highlighted; with [allowNone] the
 * first option clears it (an account then shows its type's icon).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (allowNone) {
                EmojiChip(text = "—", selected = selected.isBlank(), onClick = { onSelect("") }, label = "Sin emoji")
            }
            SUGGESTED_EMOJIS.forEach { option ->
                EmojiChip(text = option, selected = option == selected, onClick = { onSelect(option) })
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(12.dp))
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Otro:", color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            BasicTextField(
                value = selected.takeIf { it !in SUGGESTED_EMOJIS }.orEmpty(),
                onValueChange = { onSelect(it.trim().take(MAX_EMOJI_LENGTH)) },
                singleLine = true,
                textStyle = TextStyle(color = colors.ink, fontSize = 18.sp),
                cursorBrush = SolidColor(colors.ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmojiChip(text: String, selected: Boolean, onClick: () -> Unit, label: String? = null) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.surface2 else colors.bg2)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.ink else colors.line, RoundedCornerShape(10.dp))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 19.sp,
            fontWeight = if (label != null) FontWeight.Medium else null,
            color = colors.muted,
        )
    }
}

private const val MAX_TYPE_NAME = 30
