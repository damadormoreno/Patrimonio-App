package com.denebapps.patrimonio.domain.model

data class Asset(
    val id: String,
    val group: AssetGroup,
    val name: String,
    val subtitle: String?,
    val amount: CurrencyAmount,
) {
    /**
     * Closed set of asset categories (matches the design reference's `ASSET_GROUPS`). Room
     * stores this as the enum's [name] String in the `assets.group` column — no `Converters.kt`
     * entry needed, the mapping happens in the repository/mapper layer.
     */
    enum class AssetGroup { BANK, INVEST, REALESTATE, CRYPTO, CASH }
}
