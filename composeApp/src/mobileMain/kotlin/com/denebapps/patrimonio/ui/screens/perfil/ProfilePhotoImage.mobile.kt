package com.denebapps.patrimonio.ui.screens.perfil

import androidx.compose.ui.graphics.ImageBitmap
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.util.toImageBitmap

actual suspend fun readProfilePhoto(path: String): ImageBitmap? = PlatformFile(path).toImageBitmap()
