package com.denebapps.patrimonio.ui.components

import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.Asset.AssetGroup
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.Liability.LiabilityGroup
import com.denebapps.patrimonio.domain.model.TypeColor

/**
 * An account type as the screens show it, built-in or custom: its section [label], the shorter
 * [chipLabel] for the filter row, its [color] and, for a custom type, its [emoji] (built-in types draw
 * their icon, [typeIcon]).
 */
data class TypeLook(
    val id: String,
    val label: String,
    val color: TypeColor,
    val emoji: String? = null,
    val chipLabel: String = label,
) {
    val isCustom: Boolean get() = emoji != null
}

/** Labels from `design-reference/shared.jsx`'s `ASSET_GROUPS` and `LIAB_GROUPS`, plus the types added
 *  since. Built-in ids never repeat between assets and liabilities. */
private val BUILTIN_TYPES = listOf(
    TypeLook(AssetGroup.BANK, "Cuentas bancarias", TypeColor.BLUE, chipLabel = "Bancos"),
    TypeLook(AssetGroup.INVEST, "Inversión", TypeColor.GREEN),
    TypeLook(AssetGroup.REALESTATE, "Inmuebles", TypeColor.SAGE),
    TypeLook(AssetGroup.CRYPTO, "Cripto", TypeColor.GOLD),
    TypeLook(AssetGroup.CASH, "Efectivo", TypeColor.GREY),
    TypeLook(AssetGroup.PENSION, "Planes de pensiones", TypeColor.PURPLE, chipLabel = "Pensiones"),
    TypeLook(AssetGroup.VEHICLE, "Vehículos", TypeColor.TERRACOTTA),
    TypeLook(AssetGroup.OTHER, "Otros", TypeColor.CARAMEL),
    TypeLook(LiabilityGroup.MORTGAGE, "Hipotecas", TypeColor.TERRACOTTA),
    TypeLook(LiabilityGroup.LOAN, "Préstamos", TypeColor.PURPLE),
    TypeLook(LiabilityGroup.CARD, "Tarjetas", TypeColor.CARAMEL),
    TypeLook(LiabilityGroup.OTHER, "Otras deudas", TypeColor.GREY, chipLabel = "Otras"),
).associateBy { it.id }

/** How [typeId] is shown: a built-in type, one of [custom], or "Otros" for an id that is neither. */
fun typeLook(typeId: String, custom: Map<String, CustomAccountType> = emptyMap()): TypeLook = BUILTIN_TYPES[typeId]
    ?: custom[typeId]?.let { TypeLook(it.id, it.name, it.color, emoji = it.emoji) }
    ?: TypeLook(typeId, "Otros", TypeColor.GREY)

/** The types an account of [kind] can have: the built-in ones in their order, then the user's. */
fun typeOptions(kind: AccountKind, custom: List<CustomAccountType>): List<TypeLook> {
    val builtin = when (kind) {
        AccountKind.ASSET -> AssetGroup.entries
        AccountKind.LIABILITY -> LiabilityGroup.entries
    }
    return builtin.map { typeLook(it) } +
        custom.filter { it.kind == kind }.sortedBy { it.position }.map { typeLook(it.id, mapOf(it.id to it)) }
}

/** Section label of type [typeId], built-in only; see [typeLook] for custom ones. */
fun typeLabel(typeId: String): String = typeLook(typeId).label

/** Shorter than [typeLabel] so the filter chips fit in a row. */
fun typeChipLabel(typeId: String): String = typeLook(typeId).chipLabel

/** Long enough for any emoji, including those joined from several (families, flags). */
const val MAX_EMOJI_LENGTH = 16
