package com.denebapps.patrimonio.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val DEFAULT_FAB_SIZE_DP = 56
private const val DEFAULT_FAB_EDGE_INSET_DP = 16
private const val DEFAULT_CONTENT_GAP_DP = 16

/** Clearance for scroll content beneath a Scaffold FAB; system navigation insets are handled separately. */
fun fabContentBottomSpacingDp(
    fabSizeDp: Int = DEFAULT_FAB_SIZE_DP,
    fabEdgeInsetDp: Int = DEFAULT_FAB_EDGE_INSET_DP,
    contentGapDp: Int = DEFAULT_CONTENT_GAP_DP,
): Int = fabSizeDp + fabEdgeInsetDp + contentGapDp

val FabContentBottomSpacing: Dp = fabContentBottomSpacingDp().dp
