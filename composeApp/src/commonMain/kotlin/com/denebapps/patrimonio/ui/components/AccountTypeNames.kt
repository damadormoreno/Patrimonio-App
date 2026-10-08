package com.denebapps.patrimonio.ui.components

import com.denebapps.patrimonio.domain.model.Asset.AssetGroup
import com.denebapps.patrimonio.domain.model.Liability.LiabilityGroup

/** A built-in type's section [label] and the shorter [chip] for the filter row. */
private data class BuiltinTypeName(val label: String, val chip: String = label)

/** Labels from `design-reference/shared.jsx`'s `ASSET_GROUPS` and `LIAB_GROUPS`, plus the types added since. */
private val BUILTIN_TYPE_NAMES = mapOf(
    AssetGroup.BANK to BuiltinTypeName("Cuentas bancarias", chip = "Bancos"),
    AssetGroup.INVEST to BuiltinTypeName("Inversión"),
    AssetGroup.REALESTATE to BuiltinTypeName("Inmuebles"),
    AssetGroup.CRYPTO to BuiltinTypeName("Cripto"),
    AssetGroup.CASH to BuiltinTypeName("Efectivo"),
    AssetGroup.PENSION to BuiltinTypeName("Planes de pensiones", chip = "Pensiones"),
    AssetGroup.VEHICLE to BuiltinTypeName("Vehículos"),
    AssetGroup.OTHER to BuiltinTypeName("Otros"),
    LiabilityGroup.MORTGAGE to BuiltinTypeName("Hipotecas"),
    LiabilityGroup.LOAN to BuiltinTypeName("Préstamos"),
    LiabilityGroup.CARD to BuiltinTypeName("Tarjetas"),
    LiabilityGroup.OTHER to BuiltinTypeName("Otras deudas", chip = "Otras"),
)

/** Section label of type [typeId]; a type with no built-in name reads as "Otros". */
fun typeLabel(typeId: String): String = BUILTIN_TYPE_NAMES[typeId]?.label ?: "Otros"

/** Shorter than [typeLabel] so the filter chips fit in a row. */
fun typeChipLabel(typeId: String): String = BUILTIN_TYPE_NAMES[typeId]?.chip ?: "Otros"
