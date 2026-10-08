package com.denebapps.patrimonio.domain.model

/**
 * [group] is the account's type: one of the built-in [AssetGroup] ids or the id of a [CustomAccountType]
 * the user created. [emoji], when set, stands for the account instead of its type's icon.
 */
data class Asset(
    val id: String,
    val group: String,
    val name: String,
    val subtitle: String?,
    val amount: CurrencyAmount,
    val emoji: String? = null,
) {
    /**
     * Built-in asset types (the design reference's `ASSET_GROUPS` plus pensions, vehicles and a catch-all).
     * Their ids are what Room stores in `assets.group`, as the enum names did before custom types existed.
     */
    object AssetGroup {
        const val BANK = "BANK"
        const val INVEST = "INVEST"
        const val REALESTATE = "REALESTATE"
        const val CRYPTO = "CRYPTO"
        const val CASH = "CASH"
        const val PENSION = "PENSION"
        const val VEHICLE = "VEHICLE"
        const val OTHER = "OTHER"

        /** In the order the pickers show them. */
        val entries = listOf(BANK, INVEST, REALESTATE, CRYPTO, CASH, PENSION, VEHICLE, OTHER)
    }
}
