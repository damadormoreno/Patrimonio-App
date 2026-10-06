package com.denebapps.patrimonio.domain.repository

import kotlinx.coroutines.flow.Flow

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Renewal reminders are opt-in. [leadDays] is how many days before each charge they fire (0 = same day). */
data class RenewalReminderSettings(val enabled: Boolean = false, val leadDays: Int = DEFAULT_REMINDER_LEAD_DAYS) {
    companion object {
        const val DEFAULT_REMINDER_LEAD_DAYS = 1
        val LEAD_DAY_OPTIONS = listOf(0, 1, 3, 7)
    }
}

interface PreferencesRepository {
    fun observeThemeMode(): Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    fun observeFirstName(): Flow<String>

    suspend fun setFirstName(value: String)

    fun observeLastName(): Flow<String>

    suspend fun setLastName(value: String)

    fun observeRenewalReminders(): Flow<RenewalReminderSettings>

    suspend fun setRenewalReminders(settings: RenewalReminderSettings)
}
