package com.denebapps.patrimonio.ui.screens.account

import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.testing.FakeAuthRepository
import com.denebapps.patrimonio.testing.FakeCloudBackup
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

    @Test
    fun `signing in needs a plausible email and a 6 character password`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val vm = AccountViewModel(auth, FakeCloudBackup(auth))
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
        val vm = AccountViewModel(auth, FakeCloudBackup(auth))
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
        val vm = AccountViewModel(auth, FakeCloudBackup(auth))
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
        val vm = AccountViewModel(auth, FakeCloudBackup(auth))
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
        val vm = AccountViewModel(auth, cloud)
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
        val vm = AccountViewModel(auth, cloud)
        val job = launch { vm.state.collect {} }
        val conflict = CloudBackupState.Conflict(Instant.parse("2026-10-08T09:00:00Z"))

        cloud.state.value = conflict
        advanceUntilIdle()
        assertEquals(conflict, vm.state.value.cloud)

        vm.onUseCloudCopy()
        vm.onKeepLocalData()
        vm.onRetryCloud()
        vm.onBackUpNow()

        assertEquals(listOf("useCloudCopy", "keepLocalData", "retry", "backUpNow"), cloud.calls)
        job.cancel()
    }

    @Test
    fun `backup times read as day, month and local time`() {
        val instant = Instant.parse("2026-10-08T08:05:00Z")

        assertEquals("8 oct, 10:05", formatBackupTime(instant, TimeZone.of("Europe/Madrid")))
        assertEquals("8 oct, 08:05", formatBackupTime(instant, TimeZone.UTC))
    }
}
