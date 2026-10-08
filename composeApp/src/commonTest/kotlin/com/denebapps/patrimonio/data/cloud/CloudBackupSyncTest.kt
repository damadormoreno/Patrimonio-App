package com.denebapps.patrimonio.data.cloud

import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupException
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import com.denebapps.patrimonio.testing.FakeAuthRepository
import com.denebapps.patrimonio.testing.FakeBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CloudBackupSyncTest {
    private val user = AccountUser("uid-1", "ana@example.com")
    private val auth = FakeAuthRepository(user)
    private val backup = FakeBackupRepository(exported = "{ \"assets\": [{ \"id\": \"a1\" }] }")
    private val remote = FakeCloudRemote()
    private val links = InMemoryCloudLinkStore()
    private val changes = FakeLocalDataChanges()
    private val clock = object : Clock {
        var now = Instant.parse("2026-10-08T09:00:00Z")

        override fun now() = now
    }
    private var revisions = 0

    // Few PBKDF2 iterations and no thread switch keep the tests fast and in virtual time.
    private val crypto = BackupCrypto(iterations = 1_000, dispatcher = Dispatchers.Unconfined)
    private val sync = CloudBackupSync(auth, backup, remote, links, changes, clock, crypto) { "rev-${++revisions}" }
    private var deviceKey: CloudKey? = null

    /** The account's data key, created once with [PASSPHRASE]. */
    private suspend fun key(): CloudKey = deviceKey ?: crypto.createKey("key-1", PASSPHRASE).also { deviceKey = it }

    /** Starts the backup; by default this device already holds the account's data key. */
    private suspend fun TestScope.start(deviceHasKey: Boolean = true) {
        if (deviceHasKey) links.saveKey("uid-1", key())
        backgroundScope.launch { sync.run() }
        runCurrent()
    }

    /** A copy in the cloud, encrypted with the account's key unless [encrypted] is false (before encryption). */
    private suspend fun cloudCopy(
        revision: String,
        records: Int? = 3,
        json: String = CLOUD_JSON,
        encrypted: Boolean = true,
    ): CloudBackupDocument {
        val data = if (encrypted) crypto.encrypt(key(), "uid-1", json) else json
        return CloudBackupDocument(revision, CLOUD_SAVED_AT, records, key().wrapped.takeIf { encrypted }, data)
    }

    private suspend fun uploadedJson(): String = crypto.decrypt(key(), "uid-1", remote.document?.data.orEmpty())

    @Test
    fun `signed out nothing is checked or uploaded`() = runTest {
        auth.signOut()
        start()

        assertEquals(CloudBackupState.SignedOut, sync.state.value)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `without a cloud copy this device uploads at once and then after each change`() = runTest {
        start()

        assertEquals(listOf("fetch:meta", "upload:rev-1"), remote.calls)
        assertEquals("{\"assets\":[{\"id\":\"a1\"}]}", uploadedJson())
        assertFalse("a1" in remote.document?.data.orEmpty())
        assertEquals("key-1", remote.document?.key?.keyId)
        assertEquals(1, remote.document?.records)
        assertEquals(CloudLink("uid-1", "rev-1", clock.now.toEpochMilliseconds()), links.link)
        assertEquals(CloudBackupState.Active(clock.now), sync.state.value)

        // The first emission is the data just uploaded; a later change uploads after the debounce.
        advanceTimeBy(10.seconds)
        assertEquals(2, remote.calls.size)
        clock.now = Instant.parse("2026-10-08T09:05:00Z")
        changes.emit()
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(2, remote.calls.size)
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(listOf("fetch:meta", "upload:rev-1", "fetch:meta", "upload:rev-2"), remote.calls)
        assertEquals(CloudBackupState.Active(Instant.parse("2026-10-08T09:05:00Z")), sync.state.value)
    }

    @Test
    fun `the copy this device wrote last resumes without asking and backs up once`() = runTest {
        remote.document = cloudCopy("rev-0")
        links.link = CloudLink("uid-1", "rev-0", 1_000)
        start()

        assertEquals(CloudBackupState.Active(Instant.fromEpochMilliseconds(1_000)), sync.state.value)
        assertEquals(listOf("fetch:meta"), remote.calls)

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(listOf("fetch:meta", "fetch:meta", "upload:rev-1"), remote.calls)
    }

    @Test
    fun `a copy written elsewhere waits for the user and can replace the local data`() = runTest {
        remote.document = cloudCopy("rev-other")
        links.link = CloudLink("uid-1", "rev-0", 1_000)
        start()
        advanceTimeBy(60.seconds)

        assertEquals(CloudBackupState.Conflict(Instant.parse("2026-10-01T08:00:00Z")), sync.state.value)
        assertEquals(listOf("fetch:meta"), remote.calls)

        sync.useCloudCopy()
        runCurrent()

        assertEquals(listOf("{\"cloud\":true}"), backup.imported)
        val cloudSavedAt = Instant.parse("2026-10-01T08:00:00Z")
        assertEquals(CloudLink("uid-1", "rev-other", cloudSavedAt.toEpochMilliseconds()), links.link)
        assertEquals(CloudBackupState.Active(cloudSavedAt), sync.state.value)
        advanceTimeBy(60.seconds)
        assertEquals(listOf("fetch:meta", "fetch:data"), remote.calls)
    }

    @Test
    fun `another account's copy is a conflict too and keeping the local data overwrites it`() = runTest {
        remote.document = cloudCopy("rev-0")
        links.link = CloudLink("uid-other", "rev-0", 1_000)
        start()
        assertTrue(sync.state.value is CloudBackupState.Conflict)

        sync.keepLocalData()
        runCurrent()

        assertEquals(listOf("fetch:meta", "upload:rev-1"), remote.calls)
        assertEquals("rev-1", remote.document?.revision)
        assertEquals(CloudBackupState.Active(clock.now), sync.state.value)
        assertTrue(backup.imported.isEmpty())
    }

    @Test
    fun `a change made on another device stops the uploads and asks again`() = runTest {
        start()
        remote.document = cloudCopy("rev-other")

        changes.emit()
        advanceTimeBy(6.seconds)
        runCurrent()

        assertTrue(sync.state.value is CloudBackupState.Conflict)
        assertEquals("rev-other", remote.document?.revision)
    }

    @Test
    fun `signing in again asks first even when the cloud still has this device's copy`() = runTest {
        start()
        assertEquals("rev-1", links.link?.revision)

        auth.signOut()
        runCurrent()
        assertNull(links.link)
        backup.exported = EMPTY_BACKUP
        auth.signIn("ana@example.com", "secreto")
        runCurrent()
        // Signing out also forgot the data key, so the passphrase comes first.
        assertEquals(CloudBackupState.NeedsPassphrase(unlock = true), sync.state.value)
        sync.submitPassphrase(PASSPHRASE)
        runCurrent()
        advanceTimeBy(60.seconds)

        assertTrue(sync.state.value is CloudBackupState.Conflict)
        assertEquals("rev-1", remote.document?.revision)
    }

    @Test
    fun `emptied data is not uploaded over a copy with data without asking`() = runTest {
        start()

        backup.exported = EMPTY_BACKUP
        changes.emit()
        advanceTimeBy(6.seconds)
        runCurrent()

        assertTrue(sync.state.value is CloudBackupState.Conflict)
        assertEquals("rev-1", remote.document?.revision)

        // Keeping the empty data is a choice; after that an empty copy is backed up as usual.
        sync.keepLocalData()
        runCurrent()
        assertEquals(0, remote.document?.records)
        changes.emit()
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals("rev-3", remote.document?.revision)
        assertTrue(sync.state.value is CloudBackupState.Active)
    }

    @Test
    fun `an empty device with an old copy without a record count asks too`() = runTest {
        remote.document = cloudCopy("rev-0", records = null)
        links.link = CloudLink("uid-1", "rev-0", 1_000)
        backup.exported = EMPTY_BACKUP
        start()

        advanceTimeBy(6.seconds)
        runCurrent()

        assertTrue(sync.state.value is CloudBackupState.Conflict)
        assertEquals("rev-0", remote.document?.revision)
    }

    @Test
    fun `the first copy waits for a new passphrase and is encrypted with it`() = runTest {
        start(deviceHasKey = false)
        assertEquals(CloudBackupState.NeedsPassphrase(unlock = false), sync.state.value)
        assertEquals(listOf("fetch:meta"), remote.calls)

        sync.startOver("ignorada") // only when unlocking
        sync.submitPassphrase("mi frase nueva")
        runCurrent()

        assertEquals(listOf("fetch:meta", "upload:rev-1"), remote.calls)
        val uploaded = remote.document!!
        val opened = crypto.unwrap(uploaded.key!!, "mi frase nueva")
        assertEquals(links.keys["uid-1"], opened)
        assertEquals("{\"assets\":[{\"id\":\"a1\"}]}", crypto.decrypt(opened, "uid-1", uploaded.data!!))
        assertFailsWith<WrongPassphraseException> { crypto.unwrap(uploaded.key!!, "otra") }
    }

    @Test
    fun `a new device opens the cloud copy with the passphrase and can restore it`() = runTest {
        remote.document = cloudCopy("rev-0")
        start(deviceHasKey = false)
        assertEquals(CloudBackupState.NeedsPassphrase(unlock = true), sync.state.value)

        sync.submitPassphrase("no es esta")
        runCurrent()
        assertEquals(CloudBackupState.NeedsPassphrase(unlock = true, wrongPassphrase = true), sync.state.value)
        assertTrue(links.keys.isEmpty())

        sync.submitPassphrase(PASSPHRASE)
        runCurrent()
        assertEquals(key(), links.keys["uid-1"])
        assertEquals(CloudBackupState.Conflict(CLOUD_SAVED_AT), sync.state.value)

        sync.useCloudCopy()
        runCurrent()
        assertEquals(listOf(CLOUD_JSON), backup.imported)
        assertEquals(CloudBackupState.Active(CLOUD_SAVED_AT), sync.state.value)
    }

    @Test
    fun `a forgotten passphrase starts over with this device's data`() = runTest {
        remote.document = cloudCopy("rev-0")
        start(deviceHasKey = false)

        sync.startOver("frase nueva")
        runCurrent()

        val uploaded = remote.document!!
        assertEquals("rev-1", uploaded.revision)
        assertTrue(uploaded.key!!.keyId != "key-1")
        val opened = crypto.unwrap(uploaded.key!!, "frase nueva")
        assertEquals("{\"assets\":[{\"id\":\"a1\"}]}", crypto.decrypt(opened, "uid-1", uploaded.data!!))
        assertEquals(CloudBackupState.Active(clock.now), sync.state.value)
        assertTrue(backup.imported.isEmpty())
    }

    @Test
    fun `a copy from before encryption asks for a passphrase and can still be restored`() = runTest {
        remote.document = cloudCopy("rev-0", encrypted = false)
        start(deviceHasKey = false)
        assertEquals(CloudBackupState.NeedsPassphrase(unlock = false), sync.state.value)

        sync.submitPassphrase("mi frase nueva")
        runCurrent()
        assertTrue(sync.state.value is CloudBackupState.Conflict)

        sync.useCloudCopy()
        runCurrent()
        assertEquals(listOf(CLOUD_JSON), backup.imported)

        // The next copy goes up encrypted.
        changes.emit()
        advanceTimeBy(6.seconds)
        runCurrent()
        assertTrue(sync.state.value is CloudBackupState.Active)
        assertNotNull(remote.document?.key)
        assertFalse("a1" in remote.document?.data.orEmpty())
    }

    @Test
    fun `changing the passphrase re-wraps the same key and uploads it`() = runTest {
        start()

        sync.changePassphrase("frase cambiada")
        advanceTimeBy(6.seconds)
        runCurrent()

        val uploaded = remote.document!!
        assertEquals("rev-2", uploaded.revision)
        assertEquals(key().secret, crypto.unwrap(uploaded.key!!, "frase cambiada").secret)
        assertFailsWith<WrongPassphraseException> { crypto.unwrap(uploaded.key!!, PASSPHRASE) }
        assertEquals("key-1", uploaded.key!!.keyId)
    }

    @Test
    fun `a failed check is retried by the user or after a while`() = runTest {
        remote.failure = CloudBackupError.NETWORK
        start()
        assertEquals(CloudBackupState.CheckFailed(CloudBackupError.NETWORK), sync.state.value)

        sync.retry()
        runCurrent()
        assertEquals(CloudBackupState.CheckFailed(CloudBackupError.NETWORK), sync.state.value)
        assertEquals(2, remote.calls.size)

        // The automatic retries wait longer each time: 30 s, then 60 s.
        remote.failure = null
        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals(2, remote.calls.size)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(CloudBackupState.Active(clock.now), sync.state.value)
    }

    @Test
    fun `a copy this app cannot read fails the restore and keeps the local data`() = runTest {
        remote.document = cloudCopy("rev-other")
        backup.failure = InvalidBackupException("versión nueva")
        start()

        sync.useCloudCopy()
        runCurrent()

        assertEquals(CloudBackupState.CheckFailed(CloudBackupError.INVALID_BACKUP), sync.state.value)
        assertNull(links.link)
    }

    @Test
    fun `a failed upload is reported and retried`() = runTest {
        start()
        remote.failure = CloudBackupError.NETWORK

        sync.backUpNow()
        runCurrent()
        val failed = sync.state.value as CloudBackupState.Active
        assertEquals(CloudBackupError.NETWORK, failed.failure)

        remote.failure = null
        advanceTimeBy(31.seconds)
        runCurrent()
        assertNull((sync.state.value as CloudBackupState.Active).failure)
        assertEquals("rev-2", remote.document?.revision)
    }

    @Test
    fun `signing out stops the backup`() = runTest {
        start()

        auth.signOut()
        runCurrent()
        changes.emit()
        advanceTimeBy(60.seconds)

        assertEquals(CloudBackupState.SignedOut, sync.state.value)
        assertEquals(listOf("fetch:meta", "upload:rev-1"), remote.calls)
    }

    @Test
    fun `deleting the account deletes the cloud copy first and keeps the account if that fails`() = runTest {
        start()
        remote.failure = CloudBackupError.NETWORK

        assertFailsWith<CloudBackupException> { sync.deleteAccount("secreto") }
        assertNotNull(remote.document)
        assertEquals(user, auth.currentUser)

        remote.failure = null
        sync.deleteAccount("secreto")
        runCurrent()

        assertNull(remote.document)
        assertNull(auth.currentUser)
        assertEquals(CloudBackupState.SignedOut, sync.state.value)
    }

    @Test
    fun `a wrong password deletes nothing`() = runTest {
        start()
        auth.failWith = AuthError.WRONG_CREDENTIALS

        assertFailsWith<AuthException> { sync.deleteAccount("mala") }

        assertNotNull(remote.document)
    }
}

private class FakeCloudRemote : CloudBackupRemote {
    var document: CloudBackupDocument? = null
    var failure: CloudBackupError? = null
    val calls = mutableListOf<String>()

    override suspend fun fetch(uid: String, idToken: String, withData: Boolean): CloudBackupDocument? {
        calls += if (withData) "fetch:data" else "fetch:meta"
        failure?.let { throw CloudBackupException(it) }
        return document?.let { if (withData) it else it.copy(data = null) }
    }

    override suspend fun upload(uid: String, idToken: String, document: CloudBackupDocument) {
        calls += "upload:${document.revision}"
        failure?.let { throw CloudBackupException(it) }
        this.document = document
    }

    override suspend fun delete(uid: String, idToken: String) {
        calls += "delete"
        failure?.let { throw CloudBackupException(it) }
        document = null
    }
}

private class InMemoryCloudLinkStore : CloudLinkStore {
    var link: CloudLink? = null
    val keys = mutableMapOf<String, CloudKey>()

    override suspend fun get() = link

    override suspend fun save(link: CloudLink) {
        this.link = link
    }

    override suspend fun key(uid: String) = keys[uid]

    override suspend fun saveKey(uid: String, key: CloudKey) {
        keys[uid] = key
    }

    override suspend fun clear() {
        link = null
        keys.clear()
    }
}

private const val PASSPHRASE = "frase de prueba"
private const val CLOUD_JSON = "{\"cloud\":true}"
private val CLOUD_SAVED_AT = Instant.parse("2026-10-01T08:00:00Z")

private const val EMPTY_BACKUP =
    "{\"assets\":[],\"liabilities\":[],\"accountGroups\":[],\"savingsGoals\":[],\"subscriptions\":[]}"

private class FakeLocalDataChanges : LocalDataChanges {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    fun emit() {
        changes.tryEmit(Unit)
    }

    override fun observe(): Flow<Unit> = flow {
        emit(Unit)
        emitAll(changes)
    }
}
