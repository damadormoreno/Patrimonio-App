package com.denebapps.patrimonio

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.denebapps.patrimonio.di.initKoin
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKoin()
    ComposeViewport(document.body!!) { App() }
}
