package com.denebapps.patrimonio.ui.screens.account

import androidx.compose.runtime.Composable

/** Not on the web yet: it needs Google Identity Services and the site as an authorised origin. */
@Composable
actual fun rememberGoogleIdTokenRequester(): (suspend () -> GoogleIdTokenResult)? = null
