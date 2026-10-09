package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Asset.AssetGroup
import com.denebapps.patrimonio.domain.model.Liability.LiabilityGroup
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private val BUILTIN_TYPE_ICONS: Map<String, ImageVector> by lazy {
    mapOf(
        AssetGroup.BANK to AppIcons.bank,
        AssetGroup.INVEST to AppIcons.trend,
        AssetGroup.REALESTATE to AppIcons.building,
        AssetGroup.CRYPTO to AppIcons.coins,
        AssetGroup.CASH to AppIcons.wallet,
        AssetGroup.PENSION to AppIcons.umbrella,
        AssetGroup.VEHICLE to AppIcons.car,
        AssetGroup.OTHER to AppIcons.tag,
        LiabilityGroup.MORTGAGE to AppIcons.house,
        LiabilityGroup.LOAN to AppIcons.briefcase,
        LiabilityGroup.CARD to AppIcons.card,
        LiabilityGroup.OTHER to AppIcons.receipt,
    )
}

/** Icon of the built-in type [typeId]; the "Otros" tag for any other. */
fun typeIcon(typeId: String): ImageVector = BUILTIN_TYPE_ICONS[typeId] ?: AppIcons.tag

/**
 * The square badge of an account type or account: [emoji] when given (the account's own, or a custom
 * type's), else the built-in type's icon, on the type's colour.
 */
@Composable
fun TypeBadge(
    type: TypeLook,
    modifier: Modifier = Modifier,
    emoji: String? = type.emoji,
    size: Dp = 32.dp,
    cornerRadius: Dp = 9.dp,
) {
    Box(
        modifier = modifier.size(size).background(type.color.toColor(), RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji != null) {
            Text(text = emoji, fontSize = (size.value * 0.5f).sp)
        } else {
            Icon(
                imageVector = typeIcon(type.id),
                contentDescription = null,
                tint = LocalAppColors.current.surface2,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** The theme's category colour for [TypeColor]. */
@Composable
fun TypeColor.toColor(): Color {
    val colors = LocalAppColors.current
    return when (this) {
        TypeColor.BLUE -> colors.catTrans
        TypeColor.GREEN -> colors.catSalary
        TypeColor.SAGE -> colors.catHome
        TypeColor.TERRACOTTA -> colors.catRest
        TypeColor.PURPLE -> colors.catSubs
        TypeColor.GOLD -> colors.catFun
        TypeColor.CARAMEL -> colors.catFood
        TypeColor.GREY -> colors.catOther
    }
}
