package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.repository.AuthRepository
import com.denebapps.patrimonio.domain.repository.CloudBackup
import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupException
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.domain.repository.Reauthentication
import kotlinx.coroutines.flow.MutableStateFlow

/** Records the calls; [state] is set by the test. [deleteFailure] makes deleting the cloud copy fail. */
class FakeCloudBackup(private val auth: AuthRepository = FakeAuthRepository()) : CloudBackup {
    override val state: MutableStateFlow<CloudBackupState> = MutableStateFlow(CloudBackupState.SignedOut)
    val calls = mutableListOf<String>()
    var deleteFailure: CloudBackupError? = null

    override suspend fun run() {
        calls += "run"
    }

    override fun retry() {
        calls += "retry"
    }

    override fun useCloudCopy() {
        calls += "useCloudCopy"
    }

    override fun keepLocalData() {
        calls += "keepLocalData"
    }

    override fun backUpNow() {
        calls += "backUpNow"
    }

    override fun submitPassphrase(passphrase: String) {
        calls += "submitPassphrase:$passphrase"
    }

    override fun startOver(passphrase: String) {
        calls += "startOver:$passphrase"
    }

    override suspend fun changePassphrase(passphrase: String) {
        calls += "changePassphrase:$passphrase"
    }

    override suspend fun deleteAccount(reauthentication: Reauthentication) {
        auth.deleteAccount(reauthentication) {
            calls += "deleteCloudCopy"
            deleteFailure?.let { throw CloudBackupException(it) }
        }
    }
}
