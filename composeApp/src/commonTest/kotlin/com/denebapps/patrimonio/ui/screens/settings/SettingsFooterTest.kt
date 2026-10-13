package com.denebapps.patrimonio.ui.screens.settings

import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsFooterTest {
    private val savedAt = Instant.fromEpochMilliseconds(1_760_000_000_000)

    @Test
    fun `without a cloud backup the footer says the data lives only on the device`() {
        val expected = "Tus datos viven solo en este dispositivo. Exporta una copia de vez en cuando."

        assertEquals(expected, settingsFooterText(CloudBackupState.SignedOut))
    }

    @Test
    fun `an active cloud backup is named, with its encryption`() {
        val text = settingsFooterText(CloudBackupState.Active(lastBackupAt = savedAt))

        assertEquals(
            "Tus datos se guardan en este dispositivo y, cifrados con tu frase, en tu copia en la nube.",
            text,
        )
    }

    @Test
    fun `an active backup that is uploading or failing keeps the cloud wording`() {
        val uploading = settingsFooterText(CloudBackupState.Active(lastBackupAt = savedAt, backingUp = true))
        val failing = settingsFooterText(
            CloudBackupState.Active(lastBackupAt = savedAt, failure = CloudBackupError.NETWORK),
        )

        assertTrue("copia en la nube" in uploading)
        assertTrue("copia en la nube" in failing)
    }

    @Test
    fun `states where nothing is uploaded yet do not claim a cloud copy`() {
        val states = listOf(
            CloudBackupState.Checking,
            CloudBackupState.NeedsPassphrase(unlock = false),
            CloudBackupState.NeedsPassphrase(unlock = true),
            CloudBackupState.Conflict(cloudSavedAt = savedAt),
            CloudBackupState.Resolving,
            CloudBackupState.CheckFailed(CloudBackupError.NETWORK),
        )

        states.forEach { state ->
            assertFalse("en la nube" in settingsFooterText(state), "$state must not claim a cloud copy")
        }
    }
}
