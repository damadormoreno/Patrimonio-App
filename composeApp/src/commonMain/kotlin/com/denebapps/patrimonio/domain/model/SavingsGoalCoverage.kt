package com.denebapps.patrimonio.domain.model

sealed interface SavingsGoalCoverage {
    data object Unavailable : SavingsGoalCoverage

    /** Shared asset-level coverage; intentionally contains no per-goal allocation or priority. */
    data class SharedAsset(
        val assetId: String,
        val balance: CurrencyAmount,
        val reserved: CurrencyAmount,
    ) : SavingsGoalCoverage {
        init {
            require(balance.currency == reserved.currency) {
                "Coverage balance and reservation currencies must match"
            }
        }

        val undercovered: Boolean
            get() = reserved.amount > balance.amount
    }
}
