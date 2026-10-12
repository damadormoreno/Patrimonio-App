package com.denebapps.patrimonio.data.datastore

import com.denebapps.patrimonio.domain.repository.CarteraFilterSelection
import com.denebapps.patrimonio.domain.repository.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `no portfolio is selected until one is chosen`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())

        assertNull(repo.observeSelectedPortfolioId().first())
    }

    @Test
    fun `the selected portfolio is written and emitted reactively`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        val selections = repo.observeSelectedPortfolioId().produceIn(backgroundScope)
        assertNull(selections.receive())

        repo.setSelectedPortfolioId("pf-hijos")

        assertEquals("pf-hijos", selections.receive())
        assertEquals("pf-hijos", repo.observeSelectedPortfolioId().first())
    }

    @Test
    fun `selecting all portfolios clears the selection`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        repo.setSelectedPortfolioId("pf-hijos")

        repo.setSelectedPortfolioId(null)

        assertNull(repo.observeSelectedPortfolioId().first())
    }

    @Test
    fun `the selected portfolio does not disturb the other preferences`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        repo.setFirstName("Ana")
        repo.setThemeMode(ThemeMode.DARK)

        repo.setSelectedPortfolioId("pf-hijos")

        assertEquals("Ana", repo.observeFirstName().first())
        assertEquals(ThemeMode.DARK, repo.observeThemeMode().first())
    }

    @Test
    fun `no cartera filter is remembered until one is set`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())

        assertEquals(CarteraFilterSelection(), repo.observeCarteraFilter().first())
    }

    @Test
    fun `the cartera filter round-trips its three dimensions and emits reactively`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        val filters = repo.observeCarteraFilter().produceIn(backgroundScope)
        assertEquals(CarteraFilterSelection(), filters.receive())
        val chosen = CarteraFilterSelection(
            assetClasses = setOf("EQUITY", "UNCLASSIFIED"),
            brokers = setOf("myinvestor", ""),
            types = setOf("FUND"),
        )

        repo.setCarteraFilter(chosen)

        assertEquals(chosen, filters.receive())
        assertEquals(chosen, repo.observeCarteraFilter().first())
    }

    @Test
    fun `clearing the cartera filter forgets every dimension`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        repo.setCarteraFilter(CarteraFilterSelection(brokers = setOf("degiro"), types = setOf("ETF")))

        repo.setCarteraFilter(CarteraFilterSelection())

        assertEquals(CarteraFilterSelection(), repo.observeCarteraFilter().first())
    }

    @Test
    fun `the cartera filter does not disturb the selected portfolio`() = runTest {
        val repo = PreferencesRepositoryImpl(tempFilePath())
        repo.setSelectedPortfolioId("pf-hijos")

        repo.setCarteraFilter(CarteraFilterSelection(types = setOf("FUND")))

        assertEquals("pf-hijos", repo.observeSelectedPortfolioId().first())
    }
}
