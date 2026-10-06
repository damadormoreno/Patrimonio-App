package com.denebapps.patrimonio.ui.screens.settings

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import com.denebapps.patrimonio.testing.FakeBackupRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeBackupRepository(exported = """{"format":"patrimonio-backup"}""")

    private fun viewModel(zone: TimeZone = TimeZone.UTC) =
        BackupViewModel(repository, fixedClock("2026-10-06T23:30:00Z"), { zone })

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `suggested file name uses the local date`() {
        assertEquals("patrimonio-2026-10-06", viewModel().suggestedFileName())
        assertEquals("patrimonio-2026-10-07", viewModel(TimeZone.of("Europe/Madrid")).suggestedFileName())
    }

    @Test
    fun `export writes the repository json and reports success`() = runTest(dispatcher) {
        val viewModel = viewModel()
        var written: String? = null

        viewModel.onExportDestinationChosen { written = it }
        assertEquals(BackupStatus.InProgress, viewModel.status.value)
        advanceUntilIdle()

        assertEquals(repository.exported, written)
        assertEquals(BackupStatus.Exported, viewModel.status.value)
    }

    @Test
    fun `export write failure is reported`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onExportDestinationChosen { error("disk full") }
        advanceUntilIdle()

        assertEquals(BackupStatus.Failed("No se pudo exportar la copia."), viewModel.status.value)
    }

    @Test
    fun `import passes the file content to the repository`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onImportFileChosen { "payload" }
        advanceUntilIdle()

        assertEquals(listOf("payload"), repository.imported)
        assertEquals(BackupStatus.Imported, viewModel.status.value)
    }

    @Test
    fun `invalid backup surfaces the codec reason`() = runTest(dispatcher) {
        repository.failure = InvalidBackupException("El archivo no es una copia de Patrimonio.")
        val viewModel = viewModel()

        viewModel.onImportFileChosen { "{}" }
        advanceUntilIdle()

        val status = assertIs<BackupStatus.Failed>(viewModel.status.value)
        assertTrue("no es una copia de Patrimonio" in status.message)
    }

    @Test
    fun `unexpected import failure says the data is untouched`() = runTest(dispatcher) {
        repository.failure = IllegalStateException("db closed")
        val viewModel = viewModel()

        viewModel.onImportFileChosen { "{}" }
        advanceUntilIdle()

        val status = assertIs<BackupStatus.Failed>(viewModel.status.value)
        assertTrue("no se han modificado" in status.message)
    }

    @Test
    fun `a second operation is ignored while one is in progress`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val viewModel = viewModel()

        viewModel.onImportFileChosen { "first" }
        advanceUntilIdle()
        viewModel.onImportFileChosen { "second" }
        viewModel.onResultConsumed()
        assertEquals(BackupStatus.InProgress, viewModel.status.value)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first"), repository.imported)
        assertEquals(BackupStatus.Imported, viewModel.status.value)
    }

    @Test
    fun `consuming a result returns to idle`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onImportFileChosen { "payload" }
        advanceUntilIdle()

        viewModel.onResultConsumed()

        assertEquals(BackupStatus.Idle, viewModel.status.value)
    }
}
