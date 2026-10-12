package com.denebapps.patrimonio.domain.repository

import kotlinx.coroutines.flow.Flow

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * The filters of the Cartera list, as keys the user chose: [assetClasses] (an `AssetClass` name, or the key of the
 * unclassified ones), [brokers] (the broker lower-cased, or empty for holdings without one) and [types] (an
 * `InstrumentType` name). Several keys of one dimension mean any of them; an empty set restricts nothing. Keys are
 * kept as strings so that a value that no longer exists can be remembered and ignored without breaking anything.
 */
data class CarteraFilterSelection(
    val assetClasses: Set<String> = emptySet(),
    val brokers: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = assetClasses.isEmpty() && brokers.isEmpty() && types.isEmpty()
}

interface PreferencesRepository {
    fun observeThemeMode(): Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    fun observeFirstName(): Flow<String>

    suspend fun setFirstName(value: String)

    fun observeLastName(): Flow<String>

    suspend fun setLastName(value: String)

    /**
     * The scope of the Cartera tab: the id of the portfolio the user last selected, or `null` for "Todas". The id may
     * name a portfolio that was deleted since; whoever reads it must check it still exists.
     */
    fun observeSelectedPortfolioId(): Flow<String?>

    /** Remembers the selected portfolio; `null` selects all of them. */
    suspend fun setSelectedPortfolioId(id: String?)

    /** The filters the user last left on the Cartera list; nothing selected until they choose some. */
    fun observeCarteraFilter(): Flow<CarteraFilterSelection>

    /** Remembers the Cartera filters; an empty selection forgets them. */
    suspend fun setCarteraFilter(filter: CarteraFilterSelection)
}
