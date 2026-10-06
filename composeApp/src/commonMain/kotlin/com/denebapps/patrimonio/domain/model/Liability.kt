package com.denebapps.patrimonio.domain.model

data class Liability(
    val id: String,
    val group: LiabilityGroup,
    val name: String,
    val subtitle: String?,
    val amount: CurrencyAmount,
) {
    /**
     * Closed set of liability categories (matches the design reference's `LIAB_GROUPS`). Room
     * stores this as the enum's [name] String in the `liabilities.group` column — no
     * `Converters.kt` entry needed, the mapping happens in the repository/mapper layer.
     */
    enum class LiabilityGroup { MORTGAGE, LOAN, CARD }
}
