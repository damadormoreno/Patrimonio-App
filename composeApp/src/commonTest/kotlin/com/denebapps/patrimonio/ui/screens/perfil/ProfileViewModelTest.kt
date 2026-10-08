package com.denebapps.patrimonio.ui.screens.perfil

import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.FakeProfilePhotoRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `initial state is empty with neutral initials`() {
        val viewModel = ProfileViewModel(FakePreferencesRepository(), FakeProfilePhotoRepository())

        assertEquals("", viewModel.state.value.firstName)
        assertEquals("", viewModel.state.value.lastName)
        assertNull(viewModel.state.value.initials)
    }

    @Test
    fun `state prefills names and initials from persisted preferences`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(firstName = "Ana", lastName = "Gil")
        val viewModel = ProfileViewModel(preferences, FakeProfilePhotoRepository())
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
        val viewModel = ProfileViewModel(preferences, FakeProfilePhotoRepository())
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
        val viewModel = ProfileViewModel(preferences, FakeProfilePhotoRepository())
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
        val viewModel = ProfileViewModel(preferences, FakeProfilePhotoRepository())
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.saveFirstName(" ")
        viewModel.saveLastName("")
        advanceUntilIdle()

        assertNull(viewModel.state.value.initials)
        job.cancel()
    }

    @Test
    fun `a picked image becomes the photo and can be removed`() = runTest(dispatcher) {
        val photos = FakeProfilePhotoRepository()
        val viewModel = ProfileViewModel(FakePreferencesRepository(), photos)
        val job = launch { viewModel.state.collect {} }

        viewModel.onPhotoPicked { byteArrayOf(1, 2, 3) }
        advanceUntilIdle()
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), photos.images.map { it.toList() })
        assertTrue(viewModel.state.value.hasPhoto)

        viewModel.onRemovePhoto()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.hasPhoto)
        job.cancel()
    }

    @Test
    fun `an image that cannot be used says so`() = runTest(dispatcher) {
        val photos = FakeProfilePhotoRepository().apply { failure = IllegalArgumentException("not an image") }
        val viewModel = ProfileViewModel(FakePreferencesRepository(), photos)
        val job = launch { viewModel.state.collect {} }

        viewModel.onPhotoPicked { byteArrayOf(9) }
        advanceUntilIdle()

        assertEquals("No se pudo usar esa imagen.", viewModel.state.value.photoError)
        assertFalse(viewModel.state.value.hasPhoto)
        job.cancel()
    }
}
