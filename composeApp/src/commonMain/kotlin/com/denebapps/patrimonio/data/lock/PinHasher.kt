package com.denebapps.patrimonio.data.lock

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

/**
 * PBKDF2-HMAC-SHA256 with a random salt. A 4-digit PIN cannot resist an offline search whatever the
 * hashing; the hash keeps the PIN out of the file, and the wrong-PIN waits are what protect it in the app.
 * [iterations] keeps a check around a tenth of a second on a phone.
 */
class PinHasher(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
    private val iterations: Int = ITERATIONS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun hash(pin: String): PinHash = withContext(dispatcher) {
        val salt = CryptographyRandom.nextBytes(SALT_BYTES)
        PinHash(Base64.encode(derive(pin, salt, iterations)), Base64.encode(salt), iterations)
    }

    suspend fun matches(pin: String, stored: PinHash): Boolean = withContext(dispatcher) {
        val actual = derive(pin, Base64.decode(stored.salt), stored.iterations)
        constantTimeEquals(actual, Base64.decode(stored.hash))
    }

    private suspend fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray = provider.get(PBKDF2)
        .secretDerivation(digest = SHA256, iterations = iterations, outputSize = 256.bits, salt = salt)
        .deriveSecretToByteArray(pin.encodeToByteArray())

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        const val ITERATIONS = 100_000
        private const val SALT_BYTES = 16
    }
}
