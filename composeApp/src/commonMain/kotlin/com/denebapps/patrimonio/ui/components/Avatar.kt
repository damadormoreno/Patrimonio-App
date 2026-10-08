package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * Circular avatar showing a [photo], or else either [initials] or an [icon], on a
 * [AppColors.brandSoft] background with [AppColors.brand] foreground.
 * Ported from `perfil.jsx`'s avatar pattern.
 */
@Composable
fun Avatar(
    modifier: Modifier = Modifier,
    initials: String? = null,
    icon: ImageVector? = null,
    size: Dp = 64.dp,
    photo: ImageBitmap? = null,
) {
    val colors = LocalAppColors.current

    Box(
        modifier = modifier
            .size(size)
            .background(color = colors.brandSoft, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(size * 0.44f),
            )
        } else if (initials != null) {
            Text(
                text = initials,
                color = colors.brand,
                fontSize = androidx.compose.ui.unit.TextUnit(
                    size.value * 0.34f,
                    androidx.compose.ui.unit.TextUnitType.Sp,
                ),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
