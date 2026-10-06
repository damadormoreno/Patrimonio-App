package com.denebapps.patrimonio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.resources.Res
import com.denebapps.patrimonio.resources.instrument_serif_italic
import com.denebapps.patrimonio.resources.manrope_bold
import com.denebapps.patrimonio.resources.manrope_medium
import com.denebapps.patrimonio.resources.manrope_regular
import com.denebapps.patrimonio.resources.manrope_semibold
import org.jetbrains.compose.resources.Font

/** Manrope font family built from the 4 bundled static weights (400/500/600/700). */
@Composable
fun manropeFontFamily(): FontFamily = FontFamily(
    Font(Res.font.manrope_regular, weight = FontWeight.Normal),
    Font(Res.font.manrope_medium, weight = FontWeight.Medium),
    Font(Res.font.manrope_semibold, weight = FontWeight.SemiBold),
    Font(Res.font.manrope_bold, weight = FontWeight.Bold),
)

/** Display family — Instrument Serif Italic, used for hero/wordmark/OTP-style text. */
@Composable
fun instrumentSerifItalicFontFamily(): FontFamily =
    FontFamily(Font(Res.font.instrument_serif_italic, weight = FontWeight.Normal))

/** OpenType feature-settings string enabling tabular (fixed-width) digit figures. */
const val TABULAR_NUMS_FEATURE_SETTINGS = "tnum"

/**
 * Builds the app [Typography] scale from the bundled Manrope + Instrument Serif Italic
 * families. Must be called from a `@Composable` context (`Res.font` requirement).
 */
@Composable
fun buildAppTypography(): Typography {
    val sans = manropeFontFamily()
    val display = instrumentSerifItalicFontFamily()

    return Typography(
        displayLarge =
        TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Normal,
            fontSize = 44.sp,
            letterSpacing = (-0.02).sp,
        ),
        headlineLarge =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp,
            letterSpacing = (-0.02).sp,
        ),
        headlineMedium =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            letterSpacing = (-0.02).sp,
        ),
        titleLarge =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
        ),
        titleMedium =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        ),
        bodyLarge =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
        ),
        bodyMedium =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
        ),
        bodySmall =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        ),
        labelLarge =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        ),
        labelMedium =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 0.01.sp,
        ),
        labelSmall =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            letterSpacing = 0.01.sp,
        ),
    )
}

/**
 * Numeric text style for monetary/amount display — tabular figures so digit
 * columns align (`AmountText` and similar). Built on top of [sans], size/weight
 * customizable per call site.
 */
@Composable
fun numericTextStyle(
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
): TextStyle = TextStyle(
    fontFamily = manropeFontFamily(),
    fontWeight = fontWeight,
    fontSize = fontSize,
    fontFeatureSettings = TABULAR_NUMS_FEATURE_SETTINGS,
    letterSpacing = (-0.005).sp,
)
