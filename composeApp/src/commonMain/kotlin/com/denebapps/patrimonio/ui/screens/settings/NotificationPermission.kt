package com.denebapps.patrimonio.ui.screens.settings

import androidx.compose.runtime.Composable

/**
 * Returns an action that asks the OS for permission to show notifications (only prompting when it
 * still can) and reports through [onResult] whether notifications can be shown afterwards.
 */
@Composable
expect fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit
