package com.denebapps.patrimonio.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Full color token set ported from `design-reference/tokens.css`.
 * One instance per theme mode ([LightColors] / [DarkColors] in Color.kt).
 */
@Immutable
data class AppColors(
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val muted2: Color,
    val line: Color,
    val line2: Color,
    val brand: Color,
    val brandInk: Color,
    val brandSoft: Color,
    val income: Color,
    val incomeSoft: Color,
    val expense: Color,
    val expenseSoft: Color,
    val alert: Color,
    val alertSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val catFood: Color,
    val catRest: Color,
    val catHome: Color,
    val catTrans: Color,
    val catFun: Color,
    val catSubs: Color,
    val catSalary: Color,
    val catOther: Color,
    /** Diverging pair for gain/loss charts. Not [income]/[expense]: those two are too close for
     *  colour-blind readers (checked with the dataviz palette validator); these pass in both modes. */
    val chartGain: Color,
    val chartLoss: Color,
)

/** Radii token set, ported from `tokens.css` `--r-*` variables. */
@Immutable
data class AppShapes(
    val xs: Dp = 6.dp,
    val sm: Dp = 10.dp,
    val md: Dp = 14.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 28.dp,
    val full: Dp = 999.dp,
)
