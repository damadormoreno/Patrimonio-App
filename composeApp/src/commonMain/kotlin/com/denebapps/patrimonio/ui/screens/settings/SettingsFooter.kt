package com.denebapps.patrimonio.ui.screens.settings

import com.denebapps.patrimonio.domain.repository.CloudBackupState

private const val LOCAL_ONLY_FOOTER = "Tus datos viven solo en este dispositivo. Exporta una copia de vez en cuando."
private const val CLOUD_FOOTER =
    "Tus datos se guardan en este dispositivo y, cifrados con tu frase, en tu copia en la nube."

/**
 * The note at the foot of Ajustes about where the data lives. It mentions the cloud only while the copy is
 * [CloudBackupState.Active], i.e. when something really is uploaded; before that (signed out, choosing a
 * passphrase or a side of a conflict) the data is still only on the device.
 */
fun settingsFooterText(cloud: CloudBackupState): String =
    if (cloud is CloudBackupState.Active) CLOUD_FOOTER else LOCAL_ONLY_FOOTER
