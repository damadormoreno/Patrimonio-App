package com.denebapps.patrimonio.domain.model

/**
 * What a holding is made of, for the "split by asset class" view. A holding without a class is unclassified, which is
 * `null` in the model rather than a value of this enum. The names are stored (database, backup): never rename one.
 */
enum class AssetClass { EQUITY, FIXED_INCOME, MONEY_MARKET, MIXED, COMMODITIES, REAL_ESTATE, CRYPTO, OTHER }

/**
 * The class a product most likely has, from its provider [category] (Morningstar's Spanish category names, e.g.
 * `RV Global Emergente`, `RF Deuda Corporativa EUR`, `Mercado Monetario EUR`) and its [type]; `null` when neither says.
 * What the [type] implies decides first, then the category. Matching ignores case, accents and repeated blanks.
 */
fun inferAssetClass(category: String?, type: InstrumentType): AssetClass? =
    classImpliedByType(type) ?: category?.let(::classOfCategory)

/**
 * Types that are one asset class whatever the provider says. A new [InstrumentType] has to say here whether it is one
 * (the exhaustive `when` fails to compile until it does).
 */
private fun classImpliedByType(type: InstrumentType): AssetClass? = when (type) {
    InstrumentType.STOCK -> AssetClass.EQUITY
    InstrumentType.CRYPTO -> AssetClass.CRYPTO
    InstrumentType.FUND, InstrumentType.ETF, InstrumentType.OTHER -> null
}

/**
 * The rules, in order, because families overlap: gold miners are `RV Sector Oro…` and leveraged commodity trackers
 * are `Trading - … Materias Primas`, which must not fall into the equity or commodity family by their name alone.
 */
private fun classOfCategory(category: String): AssetClass? {
    val name = foldForMatching(category)
    return when {
        name.isEmpty() -> null
        name.startsWith("trading") -> AssetClass.OTHER
        "activos digitales" in name || "digital assets" in name || "crypto" in name || "cripto" in name ->
            AssetClass.CRYPTO
        "inmobiliario" in name || "real estate" in name -> AssetClass.REAL_ESTATE
        name.startsWith("materias primas") || "commodit" in name || "metales preciosos" in name ||
            "precious metals" in name || "oro y metales" in name -> AssetClass.COMMODITIES
        name.startsWith("mercado monetario") || name.startsWith("monetario") || "money market" in name ->
            AssetClass.MONEY_MARKET
        name.startsWith("mixtos") || name.startsWith("fecha objetivo") || name.startsWith("target date") ->
            AssetClass.MIXED
        name.startsWith("alternativo") || name == "otros" || name == "garantizados" ||
            name == "capital protegido" -> AssetClass.OTHER
        name.startsWith("rf ") || name.startsWith("bonos") || "deuda" in name -> AssetClass.FIXED_INCOME
        name.startsWith("rv ") || name.startsWith("natural resources") -> AssetClass.EQUITY
        else -> null
    }
}

private val ACCENTS = mapOf(
    'á' to 'a', 'à' to 'a', 'ä' to 'a', 'é' to 'e', 'è' to 'e', 'ë' to 'e', 'í' to 'i', 'ï' to 'i', 'ó' to 'o',
    'ò' to 'o', 'ö' to 'o', 'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'ñ' to 'n',
)

/** Lower case, without accents and with every run of blanks reduced to one space, trimmed. */
private fun foldForMatching(text: String): String = buildString {
    for (char in text.trim().lowercase()) {
        val folded = ACCENTS[char] ?: char
        if (folded.isWhitespace()) {
            if (isNotEmpty() && last() != ' ') append(' ')
        } else {
            append(folded)
        }
    }
}
