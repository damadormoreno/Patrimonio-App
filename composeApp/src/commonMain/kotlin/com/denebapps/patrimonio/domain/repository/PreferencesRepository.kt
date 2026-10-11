package com.denebapps.patrimonio.domain.repository

import kotlinx.coroutines.flow.Flow

enum class ThemeMode { LIGHT, DARK, SYSTEM }

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
}
