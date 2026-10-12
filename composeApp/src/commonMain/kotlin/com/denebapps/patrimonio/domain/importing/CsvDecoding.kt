package com.denebapps.patrimonio.domain.importing

private const val BYTE_ORDER_MARK = '﻿'

/**
 * Windows-1252 characters of the bytes `0x80..0x9F`, the range where it differs from Latin-1. The five bytes it leaves
 * undefined keep their C1 control character, as Latin-1 has them.
 */
private const val WINDOWS_1252_C1 =
    "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F" +
        "\u0090‘’“”•–—˜™š›œ\u009DžŸ"

private const val FIRST_C1_BYTE = 0x80
private const val LAST_C1_BYTE = 0x9F

/**
 * The text of a CSV file: UTF-8 (without its byte order mark, if it has one), or, when the bytes are not valid UTF-8,
 * Windows-1252, which is what Excel on Windows writes (Latin-1 is a subset of it, apart from `0x80..0x9F`).
 */
fun decodeCsvBytes(bytes: ByteArray): String {
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        decodeWindows1252(bytes)
    }
    return text.removePrefix(BYTE_ORDER_MARK.toString())
}

private fun decodeWindows1252(bytes: ByteArray): String {
    val text = StringBuilder(bytes.size)
    for (byte in bytes) {
        val code = byte.toInt() and 0xFF
        text.append(if (code in FIRST_C1_BYTE..LAST_C1_BYTE) WINDOWS_1252_C1[code - FIRST_C1_BYTE] else code.toChar())
    }
    return text.toString()
}
