package com.denebapps.patrimonio.ui.screens.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Android 13+: no thumbnail in recents, but screenshots still work (unlike FLAG_SECURE). Older versions
 *  show the thumbnail. */
@Composable
actual fun rememberAppSwitcherCover(enabled: Boolean): Boolean {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!enabled)
        }
        onDispose { }
    }
    return false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
