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
}
