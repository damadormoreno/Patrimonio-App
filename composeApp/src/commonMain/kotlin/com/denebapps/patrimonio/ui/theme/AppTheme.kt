package com.denebapps.patrimonio.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppColors = staticCompositionLocalOf { LightColors }
val LocalAppShapes = staticCompositionLocalOf { AppShapes() }

/**
 * Root theme composable. Exposes [AppColors] and [AppShapes] tokens via
 * [LocalAppColors] / [LocalAppShapes], and bridges into [MaterialTheme] with the
 * built typography scale so Material3 components inherit sane colors too.
 */
@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val shapes = AppShapes()
    val typography = buildAppTypography()
    val materialColorScheme = colors.toMaterialColorScheme(dark)

    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalAppShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = typography,
            content = content,
        )
    }
}

private fun AppColors.toMaterialColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        background = bg,
        surface = surface,
        surfaceVariant = surface2,
        onBackground = ink,
        onSurface = ink,
        onSurfaceVariant = ink2,
        primary = brand,
        onPrimary = brandInk,
        primaryContainer = brandSoft,
        error = expense,
        errorContainer = expenseSoft,
        outline = line,
        outlineVariant = line2,
    )
}
