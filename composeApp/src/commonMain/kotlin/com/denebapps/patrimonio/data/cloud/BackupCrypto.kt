package com.denebapps.patrimonio.data.cloud

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

/** The account's data key encrypted with a key derived from the passphrase; this is what the cloud stores. */
data class WrappedDataKey(val keyId: String, val salt: String, val iterations: Int, val wrapped: String)

/** The data key this device holds ([secret], Base64) and its [wrapped] form, uploaded with every copy. */
data class CloudKey(val secret: String, val wrapped: WrappedDataKey) {
    val keyId: String get() = wrapped.keyId

    override fun toString() = "CloudKey(keyId=$keyId)"
}

class WrongPassphraseException(cause: Throwable? = null) : Exception("Wrong passphrase", cause)

/**
 * End-to-end encryption of the cloud copy. A random 256-bit data key encrypts the backup with AES-GCM; the
 * data key is stored in the cloud wrapped (AES-GCM) with a key derived from the user's passphrase by
 * PBKDF2-HMAC-SHA256. Neither Firebase nor the project owner can read the copy without the passphrase.
 * Changing the passphrase only re-wraps the data key.
 *
 * Every ciphertext is bound to its context through the associated data, so a copy or a key cannot be moved
 * to another account or key id without failing to decrypt.
 */
class BackupCrypto(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
    private val iterations: Int = PBKDF2_ITERATIONS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val aesGcm by lazy { provider.get(AES.GCM) }

    suspend fun createKey(keyId: String, passphrase: String): CloudKey = work {
        val secret = CryptographyRandom.nextBytes(DATA_KEY_BYTES)
        CloudKey(Base64.encode(secret), wrap(keyId, secret, passphrase))
    }

    /** The same data key under a new passphrase (and a new salt). */
    suspend fun rewrap(key: CloudKey, passphrase: String): CloudKey = work {
        key.copy(wrapped = wrap(key.keyId, Base64.decode(key.secret), passphrase))
    }

    /** @throws WrongPassphraseException when [passphrase] does not open [wrapped]. */
    suspend fun unwrap(wrapped: WrappedDataKey, passphrase: String): CloudKey = work {
        val kek = deriveKey(passphrase, Base64.decode(wrapped.salt), wrapped.iterations)
        val secret = try {
            cipher(kek).decrypt(Base64.decode(wrapped.wrapped), keyContext(wrapped.keyId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WrongPassphraseException(e)
        }
        CloudKey(Base64.encode(secret), wrapped)
    }

    /** [plaintext] encrypted for [uid] with [key], as Base64 (IV, ciphertext and tag). */
    suspend fun encrypt(key: CloudKey, uid: String, plaintext: String): String = work {
        val ciphertext = cipher(Base64.decode(key.secret)).encrypt(plaintext.encodeToByteArray(), dataContext(uid, key))
        Base64.encode(ciphertext)
    }

    /** @throws Exception when [ciphertext] was not encrypted for [uid] with [key], or was tampered with. */
    suspend fun decrypt(key: CloudKey, uid: String, ciphertext: String): String = work {
        cipher(Base64.decode(key.secret)).decrypt(Base64.decode(ciphertext), dataContext(uid, key)).decodeToString()
    }

    private suspend fun wrap(keyId: String, secret: ByteArray, passphrase: String): WrappedDataKey {
        val salt = CryptographyRandom.nextBytes(SALT_BYTES)
        val kek = deriveKey(passphrase, salt, iterations)
        val wrapped = cipher(kek).encrypt(secret, keyContext(keyId))
        return WrappedDataKey(keyId, Base64.encode(salt), iterations, Base64.encode(wrapped))
    }

    private suspend fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray =
        provider.get(PBKDF2)
            .secretDerivation(digest = SHA256, iterations = iterations, outputSize = 256.bits, salt = salt)
            .deriveSecretToByteArray(passphrase.encodeToByteArray())

    private suspend fun cipher(rawKey: ByteArray) =
        aesGcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, rawKey).cipher()

    private fun keyContext(keyId: String) = "patrimonio/key/$keyId".encodeToByteArray()

    private fun dataContext(uid: String, key: CloudKey) = "patrimonio/backup/$uid/${key.keyId}".encodeToByteArray()

    /** PBKDF2 is slow on purpose: keep it, and the rest of the crypto, off the main thread. */
    private suspend fun <T> work(block: suspend () -> T): T = withContext(dispatcher) { block() }

    companion object {
        /** OWASP's recommendation for PBKDF2-HMAC-SHA256. */
        const val PBKDF2_ITERATIONS = 600_000
        private const val DATA_KEY_BYTES = 32
        private const val SALT_BYTES = 16
    }
}
