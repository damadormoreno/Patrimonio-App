package com.denebapps.patrimonio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shadow presets approximating `tokens.css`' `--sh-1`/`--sh-2`/`--sh-3` box-shadows.
 *
 * Compose has no direct box-shadow equivalent (multi-layer offset blur); `Modifier.shadow`
 * (elevation-based) is the idiomatic, cheap analog. Exact px offsets from the CSS values
 * cannot be reproduced 1:1 — elevation values below are tuned approximations of each
 * shadow's visual weight, tuned by eye rather than pixel-matched.
 */
object AppShadows {
    /** Approximates `--sh-1: 0 1px 2px rgba(28,24,20,0.04)` — subtle resting elevation. */
    fun sh1(shape: Shape = RectangleShape): Modifier = Modifier.shadow(
        elevation = 1.dp,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.04f),
        spotColor = Color.Black.copy(alpha = 0.04f),
    )

    /** Approximates `--sh-2: 0 6px 18px -8px rgba(28,24,20,0.16), 0 2px 4px rgba(28,24,20,0.04)`. */
    fun sh2(shape: Shape = RectangleShape): Modifier = Modifier.shadow(
        elevation = 6.dp,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.16f),
        spotColor = Color.Black.copy(alpha = 0.16f),
    )

    /** Approximates `--sh-3: 0 18px 40px -16px rgba(28,24,20,0.22), 0 4px 10px rgba(28,24,20,0.06)`. */
    fun sh3(shape: Shape = RectangleShape): Modifier = Modifier.shadow(
        elevation = 12.dp,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.22f),
        spotColor = Color.Black.copy(alpha = 0.22f),
    )

    fun sh1(radius: Dp): Modifier = sh1(RoundedCornerShape(radius))

    fun sh2(radius: Dp): Modifier = sh2(RoundedCornerShape(radius))

    fun sh3(radius: Dp): Modifier = sh3(RoundedCornerShape(radius))
}
