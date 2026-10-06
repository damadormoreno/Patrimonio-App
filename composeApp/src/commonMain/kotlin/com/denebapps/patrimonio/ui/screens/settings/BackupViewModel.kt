package com.denebapps.patrimonio.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.repository.BackupRepository
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

sealed interface BackupStatus {
    data object Idle : BackupStatus

    data object InProgress : BackupStatus

    data object Exported : BackupStatus

    data object Imported : BackupStatus

    /** [message] is user-facing. */
    data class Failed(val message: String) : BackupStatus
}

/**
 * Drives export/import from Settings. File I/O stays in the UI layer (the platform file
 * saver/picker hands back a file handle); this ViewModel only receives a `write`/`read` lambda,
 * so it stays platform-agnostic and testable with plain fakes.
 */
class BackupViewModel(
    private val backupRepository: BackupRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    /** e.g. `patrimonio-2026-10-06` — the saver dialog appends the extension. */
    fun suggestedFileName(): String = "patrimonio-${clock.todayIn(zoneProvider())}"

    fun onExportDestinationChosen(write: suspend (String) -> Unit) = launchOperation(
        onSuccess = BackupStatus.Exported,
        failureMessage = { "No se pudo exportar la copia." },
    ) {
        write(backupRepository.exportJson())
    }

    fun onImportFileChosen(read: suspend () -> String) = launchOperation(
        onSuccess = BackupStatus.Imported,
        failureMessage = { error ->
            if (error is InvalidBackupException) {
                "No se pudo importar: ${error.message}"
            } else {
                "No se pudo importar la copia. Tus datos no se han modificado."
            }
        },
    ) {
        backupRepository.importJson(read())
    }

    fun onResultConsumed() {
        if (_status.value != BackupStatus.InProgress) _status.value = BackupStatus.Idle
    }

    private fun launchOperation(
        onSuccess: BackupStatus,
        failureMessage: (Exception) -> String,
        block: suspend () -> Unit,
    ) {
        if (_status.value == BackupStatus.InProgress) return
        _status.value = BackupStatus.InProgress
        viewModelScope.launch {
            try {
                block()
                _status.value = onSuccess
            } catch (error: CancellationException) {
                _status.value = BackupStatus.Idle
                throw error
            } catch (error: Exception) {
                AppLogger.error("BackupViewModel", "Backup operation failed", error)
                _status.value = BackupStatus.Failed(failureMessage(error))
            }
        }
    }
}
