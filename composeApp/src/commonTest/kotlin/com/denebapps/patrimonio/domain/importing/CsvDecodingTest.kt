package com.denebapps.patrimonio.domain.importing

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvDecodingTest {
    @Test
    fun `valid UTF-8 is decoded as is`() {
        assertEquals("Inversión – 5 €", decodeCsvBytes("Inversión – 5 €".encodeToByteArray()))
    }

    @Test
    fun `a byte order mark is dropped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a;b".encodeToByteArray()

        assertEquals("a;b", decodeCsvBytes(bytes))
    }

    @Test
    fun `bytes that are not UTF-8 are read as Windows-1252`() {
        // "Inversión" with the accent as the single byte 0xF3, the euro sign as 0x80 and a typographic dash as 0x96.
        val bytes = byteArrayOf(
            0x49, 0x6E, 0x76, 0x65, 0x72, 0x73, 0x69, 0xF3.toByte(), 0x6E, 0x20, 0x80.toByte(), 0x96.toByte(),
        )

        assertEquals("Inversión €–", decodeCsvBytes(bytes))
    }

    @Test
    fun `the whole Latin-1 range keeps its characters in Windows-1252`() {
        val bytes = byteArrayOf(0xE1.toByte(), 0xF1.toByte(), 0xFC.toByte(), 0xA0.toByte(), 0xFF.toByte())

        assertEquals("áñü ÿ", decodeCsvBytes(bytes))
    }

    @Test
    fun `empty bytes decode to an empty text`() {
        assertEquals("", decodeCsvBytes(ByteArray(0)))
    }
}
