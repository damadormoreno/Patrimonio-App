package com.denebapps.patrimonio.data.datastore

import com.denebapps.patrimonio.domain.repository.RenewalReminderSettings
import com.denebapps.patrimonio.domain.repository.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PreferencesRepositoryImplTest {
    private fun tempFilePath(): String = File.createTempFile("patrimonio_prefs", ".preferences_pb").absolutePath

    @Test
    fun `theme mode defaults to SYSTEM when nothing has been written yet`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())

        val mode = repo.observeThemeMode().first()

        assertEquals(ThemeMode.SYSTEM, mode)
    }

    @Test
    fun `writing a new theme mode is reflected on the next read`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())

        repo.setThemeMode(ThemeMode.DARK)
        val mode = repo.observeThemeMode().first()

        assertEquals(ThemeMode.DARK, mode)
    }

    @Test
    fun `profile names default to empty strings when nothing has been written`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())

        assertEquals("", repo.observeFirstName().first())
        assertEquals("", repo.observeLastName().first())
    }

    @Test
    fun `first name writes emit reactively without changing last name`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        val firstNames = repo.observeFirstName().produceIn(backgroundScope)
        val lastNames = repo.observeLastName().produceIn(backgroundScope)
        assertEquals("", firstNames.receive())
        assertEquals("", lastNames.receive())

        repo.setFirstName("Ana")

        assertEquals("Ana", firstNames.receive())
        assertEquals("", repo.observeLastName().first())
    }

    @Test
    fun `last name writes emit reactively without changing first name`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        repo.setFirstName("Ana")
        val lastNames = repo.observeLastName().produceIn(backgroundScope)
        assertEquals("", lastNames.receive())

        repo.setLastName("Gil")

        assertEquals("Gil", lastNames.receive())
        assertEquals("Ana", repo.observeFirstName().first())
    }

    @Test
    fun `renewal reminders default to off one day ahead and persist changes`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        assertEquals(RenewalReminderSettings(enabled = false, leadDays = 1), repo.observeRenewalReminders().first())

        repo.setRenewalReminders(RenewalReminderSettings(enabled = true, leadDays = 3))

        assertEquals(RenewalReminderSettings(enabled = true, leadDays = 3), repo.observeRenewalReminders().first())
    }
}
