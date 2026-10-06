package com.denebapps.patrimonio

import androidx.compose.ui.window.ComposeUIViewController
import com.denebapps.patrimonio.di.initKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { App() }
}
