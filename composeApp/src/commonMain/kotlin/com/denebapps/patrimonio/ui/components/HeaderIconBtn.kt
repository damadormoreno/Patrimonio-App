package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * Small circular icon button used in screen headers. Ported from
 * `shared.jsx`'s `HeaderIconBtn({ icon, onClick, label })`.
 */
@Composable
fun HeaderIconBtn(icon: ImageVector, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current

    Box(
        modifier = modifier
            .size(38.dp)
            .background(color = colors.surface, shape = CircleShape)
            .border(width = 1.dp, color = colors.line, shape = CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.ink2,
            modifier = Modifier.size(19.dp),
        )
    }
}
