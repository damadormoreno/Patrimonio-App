package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * Modal bottom sheet container. Ported from `shared.jsx`'s `Sheet` pattern:
 * full-width scrim at `Color.Black.copy(alpha = 0.45f)`, panel with top-only
 * radius 28dp ([AppColors.surface] background), a centered 36×4dp drag
 * handle, panel height ~92-94% of the screen. Only rendered when [visible].
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    val colors = LocalAppColors.current
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.93f)
                .background(color = colors.surface, shape = sheetShape),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp, bottom = 6.dp)
                    .height(4.dp)
                    .width(36.dp)
                    .background(color = colors.line, shape = RoundedCornerShape(999.dp)),
            )
            content()
        }
    }
}
