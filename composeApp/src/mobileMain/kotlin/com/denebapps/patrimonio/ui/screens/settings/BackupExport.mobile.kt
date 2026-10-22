package com.denebapps.patrimonio.ui.screens.settings

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.writeString

/** FileKit hands back a platform file handle (SAF Uri / NSURL); the ViewModel only ever sees the write lambda. A null
 *  result means the user cancelled the dialog. */
@Composable
actual fun rememberBackupExport(backupViewModel: BackupViewModel): () -> Unit {
    val launcher = rememberFileSaverLauncher { file ->
        file?.let { destination -> backupViewModel.onExportDestinationChosen { json -> destination.writeString(json) } }
    }
    return { launcher.launch(suggestedName = backupViewModel.suggestedFileName(), extension = "json") }
}
