package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** What "Gestionar cartera" and its dialogs can do, so they can be shown without a ViewModel. */
internal class ManagementActions(
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
    val dialog: PortfolioDialogActions,
)

private val ROW_MIN_HEIGHT = 52.dp

/**
 * "Gestionar cartera" at the end of the detail of a portfolio: rename it, or delete it (it asks where its products go).
 * The last portfolio cannot be deleted: that row is muted and says why instead of opening the dialog.
 */
@Composable
internal fun ManagementSection(management: PortfolioManagementUi, actions: ManagementActions) {
    val colors = LocalAppColors.current
    val canDelete = management.deleteBlockedReason == null
    SectionLabel("Gestionar cartera")
    DetailCard {
        ManagementRow(
            icon = AppIcons.pencil,
            title = "Renombrar",
            color = colors.ink,
            subtitle = null,
            onClick = actions.onRename,
            showDivider = true,
        )
        ManagementRow(
            icon = AppIcons.trash,
            title = "Eliminar",
            color = if (canDelete) colors.expense else colors.muted,
            subtitle = management.deleteBlockedReason,
            onClick = actions.onDelete.takeIf { canDelete },
            showDivider = false,
        )
    }
}

@Composable
private fun ManagementRow(
    icon: ImageVector,
    title: String,
    color: Color,
    subtitle: String?,
    onClick: (() -> Unit)?,
    showDivider: Boolean,
) {
    val colors = LocalAppColors.current
    val lineColor = colors.line2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .drawBehind {
                if (showDivider) {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .then(if (onClick != null) Modifier.clickable(onClickLabel = title, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(text = it, color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
