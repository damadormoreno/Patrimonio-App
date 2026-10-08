package com.denebapps.patrimonio.domain.model

/** [group] is the liability's type: a built-in [LiabilityGroup] id or a [CustomAccountType] id. */
data class Liability(
    val id: String,
    val group: String,
    val name: String,
    val subtitle: String?,
    val amount: CurrencyAmount,
    val emoji: String? = null,
) {
    /** Built-in liability types (the design reference's `LIAB_GROUPS` plus a catch-all), stored as is in
     *  `liabilities.group`. Every built-in id is unique across assets and liabilities, hence `OTHER_DEBT`. */
    object LiabilityGroup {
        const val MORTGAGE = "MORTGAGE"
        const val LOAN = "LOAN"
        const val CARD = "CARD"
        const val OTHER = "OTHER_DEBT"

        val entries = listOf(MORTGAGE, LOAN, CARD, OTHER)
    }
}
