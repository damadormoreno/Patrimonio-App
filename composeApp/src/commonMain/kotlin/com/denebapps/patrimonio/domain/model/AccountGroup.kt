package com.denebapps.patrimonio.domain.model

/**
 * A user-defined, overlapping group of [Asset]s and portfolio holdings (many-to-many membership: an account or a
 * holding may be in several groups).
 *
 * [memberAssetIds] `null` means the builtin "all accounts" group — membership is every asset
 * regardless of any explicit membership list. The builtin group is synthesized by the
 * domain/repository layer at read time via [allAccounts] and is NEVER a persisted row.
 *
 * [memberHoldingIds] are the ids of the portfolio holdings in the group, next to its accounts. The builtin group
 * never has any: it stays "all accounts" only.
 */
data class AccountGroup(
    val id: String,
    val name: String,
    val showBalance: Boolean,
    val sortOrder: Int,
    val memberAssetIds: Set<String>? = null,
    val memberHoldingIds: Set<String> = emptySet(),
) {
    companion object {
        const val ALL_ACCOUNTS_ID = "__all__"

        fun allAccounts(): AccountGroup = AccountGroup(
            id = ALL_ACCOUNTS_ID,
            name = "Todas las cuentas",
            showBalance = true,
            sortOrder = Int.MIN_VALUE,
            memberAssetIds = null,
        )
    }
}

/** A group by id and name, as the warning that lists the groups a holding is in needs it. */
data class AccountGroupRef(val id: String, val name: String)
