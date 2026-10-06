package com.denebapps.patrimonio.domain.model

/**
 * A user-defined, overlapping group of [Asset]s (many-to-many membership).
 *
 * [memberAssetIds] `null` means the builtin "all accounts" group — membership is every asset
 * regardless of any explicit membership list. The builtin group is synthesized by the
 * domain/repository layer at read time via [allAccounts] and is NEVER a persisted row.
 */
data class AccountGroup(
    val id: String,
    val name: String,
    val showBalance: Boolean,
    val sortOrder: Int,
    val memberAssetIds: Set<String>? = null,
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
