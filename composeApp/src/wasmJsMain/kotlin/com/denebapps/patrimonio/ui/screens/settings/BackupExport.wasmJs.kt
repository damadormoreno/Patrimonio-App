package com.denebapps.patrimonio.ui.screens.settings

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download

/** Browsers have no "save as" dialog: the backup is downloaded straight away. */
@Composable
actual fun rememberBackupExport(backupViewModel: BackupViewModel): () -> Unit = {
    backupViewModel.onExportDestinationChosen { json ->
        FileKit.download(json.encodeToByteArray(), "${backupViewModel.suggestedFileName()}.json")
    }
}
