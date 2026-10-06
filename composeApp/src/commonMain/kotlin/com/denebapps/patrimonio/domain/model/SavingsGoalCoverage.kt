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

    /**
     * Shared group-level coverage; like [SharedAsset] it carries no per-goal allocation or priority.
     * Both amounts are expressed in the evaluated goal's currency: [balance] is the sum of the group's
     * member assets and [reserved] the sum of the progress of every goal linked to the same group, each
     * converted at the current rates.
     *
     * Groups may overlap (an asset can belong to several groups), and a goal linked to an asset is
     * evaluated independently from a goal linked to a group containing that asset. The same money can
     * therefore be counted by more than one coverage, so coverage can double-count. This is an accepted,
     * documented limitation.
     */
    data class SharedGroup(
        val groupId: String,
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
