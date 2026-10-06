package com.denebapps.patrimonio.ui.screens.perfil

import com.denebapps.patrimonio.testing.FakePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `initial state is empty with neutral initials`() {
        val viewModel = ProfileViewModel(FakePreferencesRepository())

        assertEquals("", viewModel.state.value.firstName)
        assertEquals("", viewModel.state.value.lastName)
        assertNull(viewModel.state.value.initials)
    }

    @Test
    fun `state prefills names and initials from persisted preferences`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(firstName = "Ana", lastName = "Gil")
        val viewModel = ProfileViewModel(preferences)
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        assertEquals("Ana", viewModel.state.value.firstName)
        assertEquals("Gil", viewModel.state.value.lastName)
        assertEquals("AG", viewModel.state.value.initials)
        job.cancel()
    }

    @Test
    fun `saveFirstName persists the focus-loss draft`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(firstName = "Ana", lastName = "Gil")
        val viewModel = ProfileViewModel(preferences)
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.saveFirstName("Ana María")
        advanceUntilIdle()

        assertEquals("Ana María", preferences.observeFirstName().value)
        assertEquals("Ana María", viewModel.state.value.firstName)
        assertEquals("Gil", viewModel.state.value.lastName)
        job.cancel()
    }

    @Test
    fun `saveLastName persists independently of the first name`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(firstName = "Ana", lastName = "Gil")
        val viewModel = ProfileViewModel(preferences)
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.saveLastName("Gil Vega")
        advanceUntilIdle()

        assertEquals("Ana", preferences.observeFirstName().value)
        assertEquals("Gil Vega", preferences.observeLastName().value)
        assertEquals("AG", viewModel.state.value.initials)
        job.cancel()
    }

    @Test
    fun `clearing both names restores the neutral avatar state`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(firstName = "Ana", lastName = "Gil")
        val viewModel = ProfileViewModel(preferences)
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.saveFirstName(" ")
        viewModel.saveLastName("")
        advanceUntilIdle()

        assertNull(viewModel.state.value.initials)
        job.cancel()
    }
}
