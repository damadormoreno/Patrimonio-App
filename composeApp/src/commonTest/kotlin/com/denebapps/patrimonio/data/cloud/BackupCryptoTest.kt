package com.denebapps.patrimonio.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class BackupCryptoTest {
    private val crypto = BackupCrypto(iterations = 1_000, dispatcher = Dispatchers.Unconfined)

    @Test
    fun `data encrypted with the key opens only with the key, the account and an intact ciphertext`() = runTest {
        val key = crypto.createKey("key-1", "frase de prueba")

        val ciphertext = crypto.encrypt(key, "uid-1", "{\"saldo\":1234}")

        assertFalse("saldo" in ciphertext)
        assertEquals("{\"saldo\":1234}", crypto.decrypt(key, "uid-1", ciphertext))
        assertFails { crypto.decrypt(key, "uid-2", ciphertext) }
        assertFails { crypto.decrypt(crypto.createKey("key-1", "frase de prueba"), "uid-1", ciphertext) }
        val tampered = ciphertext.replaceRange(20, 21, if (ciphertext[20] == 'A') "B" else "A")
        assertFails { crypto.decrypt(key, "uid-1", tampered) }
    }

    @Test
    fun `the passphrase unwraps the data key and a wrong one is reported as such`() = runTest {
        val key = crypto.createKey("key-1", "frase de prueba")

        assertEquals(key, crypto.unwrap(key.wrapped, "frase de prueba"))
        assertFailsWith<WrongPassphraseException> { crypto.unwrap(key.wrapped, "frase de prueba ") }
        // The wrapped key is bound to its id.
        val moved = key.wrapped.copy(keyId = "key-2")
        assertFailsWith<WrongPassphraseException> { crypto.unwrap(moved, "frase de prueba") }
    }

    @Test
    fun `re-wrapping keeps the data key under a new passphrase and salt`() = runTest {
        val key = crypto.createKey("key-1", "frase de prueba")

        val rewrapped = crypto.rewrap(key, "frase nueva")

        assertEquals(key.secret, rewrapped.secret)
        assertNotEquals(key.wrapped.salt, rewrapped.wrapped.salt)
        assertEquals(key.secret, crypto.unwrap(rewrapped.wrapped, "frase nueva").secret)
        assertFailsWith<WrongPassphraseException> { crypto.unwrap(rewrapped.wrapped, "frase de prueba") }
    }

    @Test
    fun `each key and each encryption are random`() = runTest {
        val first = crypto.createKey("key-1", "frase")
        val second = crypto.createKey("key-1", "frase")

        assertNotEquals(first.secret, second.secret)
        assertNotEquals(crypto.encrypt(first, "uid-1", "x"), crypto.encrypt(first, "uid-1", "x"))
    }
}
