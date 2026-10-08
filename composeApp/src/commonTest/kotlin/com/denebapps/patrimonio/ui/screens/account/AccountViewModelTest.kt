package com.denebapps.patrimonio.ui.screens.account

import com.denebapps.patrimonio.domain.repository.AccountProvider
import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.domain.repository.GoogleProfile
import com.denebapps.patrimonio.testing.FakeAuthRepository
import com.denebapps.patrimonio.testing.FakeCloudBackup
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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val preferences = FakePreferencesRepository()
    private val photos = FakeProfilePhotoRepository()

    private fun viewModel(auth: FakeAuthRepository, cloud: FakeCloudBackup = FakeCloudBackup(auth)) =
        AccountViewModel(auth, cloud, preferences, photos)

    @Test
    fun `Google sign-in fills an empty profile with the Google name and photo`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val vm = viewModel(auth)
        val job = launch { vm.state.collect {} }

        vm.onGoogleResult(GoogleIdTokenResult.Token("jwt"))
        advanceUntilIdle()

        assertEquals(listOf("google:jwt"), auth.calls)
        assertEquals(AccountProvider.GOOGLE, vm.state.value.user?.provider)
        assertEquals("Ana", preferences.observeFirstName().value)
        assertEquals("García", preferences.observeLastName().value)
        assertEquals(listOf("https://photo/ana"), photos.googleUrls)
        assertNull(vm.state.value.error)
        job.cancel()
    }

    @Test
    fun `Google sign-in keeps a name and a photo the user already has`() = runTest(dispatcher) {
        preferences.setFirstName("David")
        photos.setImage(byteArrayOf(1))
        val auth = FakeAuthRepository().apply { googleProfile = GoogleProfile("Ana", null, "https://photo/ana") }
        val vm = viewModel(auth)

        vm.onGoogleResult(GoogleIdTokenResult.Token("jwt"))
        advanceUntilIdle()

        assertEquals("David", preferences.observeFirstName().value)
        assertEquals("", preferences.observeLastName().value)
        assertEquals(emptyList(), photos.googleUrls)
    }

    @Test
    fun `a cancelled picker says nothing and the others say why`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val vm = viewModel(auth)
        val job = launch { vm.state.collect {} }

        vm.onGoogleResult(GoogleIdTokenResult.Cancelled)
        advanceUntilIdle()
        assertNull(vm.state.value.error)

        vm.onGoogleResult(GoogleIdTokenResult.NoAccount)
        advanceUntilIdle()
        assertEquals("No hay ninguna cuenta de Google en este móvil.", vm.state.value.error)

        vm.onGoogleResult(GoogleIdTokenResult.Failed)
        advanceUntilIdle()
        assertEquals("No se pudo iniciar sesión con Google. Inténtalo de nuevo.", vm.state.value.error)

        auth.failWith = AuthError.NOT_AVAILABLE
        vm.onGoogleResult(GoogleIdTokenResult.Token("jwt"))
        advanceUntilIdle()
        assertEquals("Ese método de acceso no está disponible ahora mismo.", vm.state.value.error)
        assertNull(vm.state.value.user)
        job.cancel()
    }

    @Test
    fun `a Google account is deleted after picking it again, and another one is refused`() = runTest(dispatcher) {
        val auth = FakeAuthRepository(AccountUser("uid-google", "ana@gmail.com", AccountProvider.GOOGLE))
        val cloud = FakeCloudBackup(auth)
        val vm = viewModel(auth, cloud)
        val job = launch { vm.state.collect {} }

        auth.failWith = AuthError.WRONG_CREDENTIALS
        vm.onDeleteAccountWithGoogle(GoogleIdTokenResult.Token("other"))
        advanceUntilIdle()
        assertEquals("Elige la misma cuenta de Google con la que iniciaste sesión.", vm.state.value.error)
        assertEquals("ana@gmail.com", vm.state.value.user?.email)

        auth.failWith = null
        vm.onDeleteAccountWithGoogle(GoogleIdTokenResult.Token("same"))
        advanceUntilIdle()
        assertEquals(listOf("delete:google:same"), auth.calls)
        assertEquals(listOf("deleteCloudCopy"), cloud.calls)
        assertNull(vm.state.value.user)
        job.cancel()
    }

    @Test
    fun `signing in needs a plausible email and a 6 character password`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val vm = viewModel(auth)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onEmailChange("ana@example")
        vm.onPasswordChange("secreto")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSubmit)

        vm.onEmailChange(" ana@example.com ")
        vm.onPasswordChange("12345")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSubmit)
        assertTrue(vm.state.value.canResetPassword)

        vm.onPasswordChange("123456")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf("signIn: ana@example.com "), auth.calls)
        assertEquals(AccountUser("uid-ana@example.com", "ana@example.com"), vm.state.value.user)
        assertEquals("", vm.state.value.password)
        assertFalse(vm.state.value.busy)
        job.cancel()
    }

    @Test
    fun `create account mode signs up and errors read in Spanish`() = runTest(dispatcher) {
        val auth = FakeAuthRepository().apply { failWith = AuthError.EMAIL_IN_USE }
        val vm = viewModel(auth)
        val job = launch { vm.state.collect {} }
        vm.onModeChange(AccountMode.SIGN_UP)
        vm.onEmailChange("ana@example.com")
        vm.onPasswordChange("secreto")
        advanceUntilIdle()

        vm.onSubmit()
        advanceUntilIdle()
        assertEquals("Ya hay una cuenta con ese correo. Inicia sesión.", vm.state.value.error)
        assertNull(vm.state.value.user)

        auth.failWith = null
        vm.onSubmit()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(listOf("signUp:ana@example.com"), auth.calls)
        job.cancel()
    }

    @Test
    fun `forgot password sends the reset email and says so`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val vm = viewModel(auth)
        val job = launch { vm.state.collect {} }
        vm.onEmailChange("ana@example.com")
        advanceUntilIdle()

        vm.onForgotPassword()
        advanceUntilIdle()

        assertEquals(listOf("reset:ana@example.com"), auth.calls)
        assertEquals(
            "Te hemos enviado un correo a ana@example.com para cambiar la contraseña.",
            vm.state.value.info,
        )
        job.cancel()
    }

    @Test
    fun `signing out and deleting the account go back to the signed out form`() = runTest(dispatcher) {
        val auth = FakeAuthRepository(AccountUser("uid-1", "ana@example.com"))
        val vm = viewModel(auth)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals("ana@example.com", vm.state.value.user?.email)

        auth.failWith = AuthError.WRONG_CREDENTIALS
        vm.onDeleteAccount("mala")
        advanceUntilIdle()
        assertEquals("Correo o contraseña incorrectos.", vm.state.value.error)
        assertEquals("ana@example.com", vm.state.value.user?.email)

        auth.failWith = null
        vm.onDeleteAccount("secreto")
        advanceUntilIdle()
        assertNull(vm.state.value.user)
        assertEquals("Cuenta y copia en la nube borradas. Tus datos siguen en este móvil.", vm.state.value.info)
        job.cancel()
    }

    @Test
    fun `the account is kept when its cloud copy cannot be deleted`() = runTest(dispatcher) {
        val auth = FakeAuthRepository(AccountUser("uid-1", "ana@example.com"))
        val cloud = FakeCloudBackup(auth).apply { deleteFailure = CloudBackupError.NETWORK }
        val vm = viewModel(auth, cloud)
        val job = launch { vm.state.collect {} }

        vm.onDeleteAccount("secreto")
        advanceUntilIdle()

        assertEquals(listOf("deleteCloudCopy"), cloud.calls)
        assertEquals(
            "No se pudo borrar la copia de la nube, así que la cuenta sigue activa. Inténtalo de nuevo.",
            vm.state.value.error,
        )
        assertEquals("ana@example.com", vm.state.value.user?.email)
        assertFalse(vm.state.value.busy)
        job.cancel()
    }

    @Test
    fun `the cloud backup state and choices go through to the cloud backup`() = runTest(dispatcher) {
        val auth = FakeAuthRepository(AccountUser("uid-1", "ana@example.com"))
        val cloud = FakeCloudBackup(auth)
        val vm = viewModel(auth, cloud)
        val job = launch { vm.state.collect {} }
        val conflict = CloudBackupState.Conflict(Instant.parse("2026-10-08T09:00:00Z"))

        cloud.state.value = conflict
        advanceUntilIdle()
        assertEquals(conflict, vm.state.value.cloud)

        vm.onUseCloudCopy()
        vm.onKeepLocalData()
        vm.onRetryCloud()
        vm.onBackUpNow()
        vm.onSubmitPassphrase("frase uno")
        vm.onStartOver("frase dos")
        vm.onChangePassphrase("frase tres")
        advanceUntilIdle()

        assertEquals(
            listOf(
                "useCloudCopy",
                "keepLocalData",
                "retry",
                "backUpNow",
                "submitPassphrase:frase uno",
                "startOver:frase dos",
                "changePassphrase:frase tres",
            ),
            cloud.calls,
        )
        assertEquals("Frase cambiada. La copia de la nube se abre ya con la nueva.", vm.state.value.info)
        job.cancel()
    }

    @Test
    fun `a new passphrase needs 8 characters typed twice`() {
        assertEquals("La frase debe tener al menos 8 caracteres.", passphraseProblem("corta", "corta"))
        assertEquals("Las dos frases no coinciden.", passphraseProblem("frase larga", "frase largo"))
        assertNull(passphraseProblem("frase larga", "frase larga"))
    }

    @Test
    fun `backup times read as day, month and local time`() {
        val instant = Instant.parse("2026-10-08T08:05:00Z")

        assertEquals("8 oct, 10:05", formatBackupTime(instant, TimeZone.of("Europe/Madrid")))
        assertEquals("8 oct, 08:05", formatBackupTime(instant, TimeZone.UTC))
    }
}
