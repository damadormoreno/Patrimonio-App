package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * Small circular icon button used in screen headers. Ported from
 * `shared.jsx`'s `HeaderIconBtn({ icon, onClick, label })`. While [loading] a spinner replaces the icon and the button
 * ignores taps (it keeps its [label] for accessibility).
 */
@Composable
fun HeaderIconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val colors = LocalAppColors.current

    Box(
        modifier = modifier
            .size(38.dp)
            .background(color = colors.surface, shape = CircleShape)
            .border(width = 1.dp, color = colors.line, shape = CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !loading,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).semantics { contentDescription = label },
                strokeWidth = 2.dp,
                color = colors.ink2,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = colors.ink2,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
