package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.inferAssetClass
import com.denebapps.patrimonio.ui.theme.AppColors
import com.denebapps.patrimonio.ui.theme.LocalAppColors

// How the asset class of a holding is named, chosen and drawn.

internal const val UNCLASSIFIED_LABEL = "Sin clasificar"

private const val MORNINGSTAR_SOURCE = "según Morningstar"
private const val TYPE_SOURCE = "según el tipo"

/** Spanish name of an asset class; `null` is the holding that has none. */
fun assetClassLabel(assetClass: AssetClass?): String = when (assetClass) {
    AssetClass.EQUITY -> "Renta variable"
    AssetClass.FIXED_INCOME -> "Renta fija"
    AssetClass.MONEY_MARKET -> "Monetario"
    AssetClass.MIXED -> "Mixto"
    AssetClass.COMMODITIES -> "Oro y materias primas"
    AssetClass.REAL_ESTATE -> "Inmobiliario"
    AssetClass.CRYPTO -> "Cripto"
    AssetClass.OTHER -> "Otros"
    null -> UNCLASSIFIED_LABEL
}

/**
 * What the class picker of a form shows: the [explicit] class the user picked (`null`: left to the app), the class the
 * product's category or type [inferred] for it, and [inferredSource] saying which of the two it came from.
 * [effective] is the class the holding would have.
 */
data class AssetClassChoiceUi(
    val explicit: AssetClass? = null,
    val inferred: AssetClass? = null,
    val inferredSource: String = MORNINGSTAR_SOURCE,
) {
    val effective: AssetClass? get() = explicit ?: inferred

    /** Whether the app decides the class (nothing was picked). */
    val isAutomatic: Boolean get() = explicit == null

    /** The class as the picker field reads it: "Renta variable (según Morningstar)", "Cripto", "Sin clasificar". */
    val text: String
        get() = when {
            explicit != null -> assetClassLabel(explicit)
            inferred != null -> "${assetClassLabel(inferred)} ($inferredSource)"
            else -> UNCLASSIFIED_LABEL
        }

    /** The option that clears the pick: "Automática (según Morningstar)", or "Sin clasificar" with nothing to infer. */
    val automaticLabel: String
        get() = if (inferred != null) "Automática ($inferredSource)" else UNCLASSIFIED_LABEL
}

/**
 * The picker state for a product of [type] with the provider [category], where the user picked [explicit] (or nothing).
 * A type that is one class whatever the provider says (stocks, crypto) is "según el tipo", the rest "según Morningstar".
 */
fun assetClassChoice(explicit: AssetClass?, category: String?, type: InstrumentType): AssetClassChoiceUi =
    AssetClassChoiceUi(
        explicit = explicit,
        inferred = inferAssetClass(category, type),
        inferredSource = if (inferAssetClass(null, type) != null) TYPE_SOURCE else MORNINGSTAR_SOURCE,
    )

/** The color of a class in the bars and legends: one theme token each, so the same class reads the same everywhere. */
@Composable
internal fun assetClassColor(assetClass: AssetClass?): Color = LocalAppColors.current.assetClassColor(assetClass)

/** The token of [assetClass] in this theme; pure, so the palette can be checked without a composition. */
internal fun AppColors.assetClassColor(assetClass: AssetClass?): Color = when (assetClass) {
    AssetClass.EQUITY -> classEquity
    AssetClass.FIXED_INCOME -> classFixedIncome
    AssetClass.MONEY_MARKET -> classMoneyMarket
    AssetClass.MIXED -> classMixed
    AssetClass.COMMODITIES -> classCommodities
    AssetClass.REAL_ESTATE -> classRealEstate
    AssetClass.CRYPTO -> classCrypto
    AssetClass.OTHER -> classOther
    null -> classUnclassified
}
