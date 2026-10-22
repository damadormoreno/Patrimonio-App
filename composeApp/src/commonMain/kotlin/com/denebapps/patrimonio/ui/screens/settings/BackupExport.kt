package com.denebapps.patrimonio.ui.screens.settings

import androidx.compose.runtime.Composable

/** Starts exporting the backup: the phone asks where to save it, the browser downloads it. */
@Composable
expect fun rememberBackupExport(backupViewModel: BackupViewModel): () -> Unit
