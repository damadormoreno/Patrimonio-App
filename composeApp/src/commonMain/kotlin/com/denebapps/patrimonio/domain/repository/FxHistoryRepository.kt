package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * Daily exchange rates to EUR over time, to value a non-EUR product's past in euros. Like the price history it is a
 * re-downloadable cache: never part of a backup. Unlike it, it belongs to no holding, so clearing the data keeps it.
 */
interface FxHistoryRepository {
    /** Every stored rate, ascending by date; re-emits after each sync. */
    fun observe(): Flow<FxHistory>

    /**
     * Makes sure the rates of [currencies] (EUR needs none) are stored from [from] to today: a full download for a
     * currency never synced, or when [from] is older than what is stored; otherwise only the days since a week before
     * the last stored one. A currency synced less than six hours ago is left alone, unless [force].
     *
     * @throws HistoryUnavailableException when a download fails; what is cached stays, and the other currencies are
     * still downloaded first.
     */
    suspend fun sync(currencies: Set<Currency>, from: LocalDate, force: Boolean)
}
