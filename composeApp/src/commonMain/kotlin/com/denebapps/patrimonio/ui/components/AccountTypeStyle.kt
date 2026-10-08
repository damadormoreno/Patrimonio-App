package com.denebapps.patrimonio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.denebapps.patrimonio.domain.model.Asset.AssetGroup
import com.denebapps.patrimonio.domain.model.Liability.LiabilityGroup
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** A built-in type's icon and colour (names: [typeLabel]). Built-in ids never repeat between kinds. */
private val BUILTIN_TYPE_STYLES: Map<String, Pair<ImageVector, TypeColor>> by lazy {
    mapOf(
        AssetGroup.BANK to (AppIcons.bank to TypeColor.BLUE),
        AssetGroup.INVEST to (AppIcons.trend to TypeColor.GREEN),
        AssetGroup.REALESTATE to (AppIcons.building to TypeColor.SAGE),
        AssetGroup.CRYPTO to (AppIcons.coins to TypeColor.GOLD),
        AssetGroup.CASH to (AppIcons.wallet to TypeColor.GREY),
        AssetGroup.PENSION to (AppIcons.umbrella to TypeColor.PURPLE),
        AssetGroup.VEHICLE to (AppIcons.car to TypeColor.TERRACOTTA),
        AssetGroup.OTHER to (AppIcons.tag to TypeColor.CARAMEL),
        LiabilityGroup.MORTGAGE to (AppIcons.house to TypeColor.TERRACOTTA),
        LiabilityGroup.LOAN to (AppIcons.briefcase to TypeColor.PURPLE),
        LiabilityGroup.CARD to (AppIcons.card to TypeColor.CARAMEL),
        LiabilityGroup.OTHER to (AppIcons.receipt to TypeColor.GREY),
    )
}

/** Icon of type [typeId]; a type with no built-in icon gets the "Otros" tag. */
fun typeIcon(typeId: String): ImageVector = BUILTIN_TYPE_STYLES[typeId]?.first ?: AppIcons.tag

/** Colour of type [typeId]; grey for a type with no built-in colour. */
@Composable
fun typeTone(typeId: String): Color = (BUILTIN_TYPE_STYLES[typeId]?.second ?: TypeColor.GREY).toColor()

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
