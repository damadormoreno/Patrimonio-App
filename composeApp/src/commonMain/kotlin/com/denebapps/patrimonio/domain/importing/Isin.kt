package com.denebapps.patrimonio.domain.importing

private const val ISIN_LENGTH = 12
private const val LETTER_DIGITS_OFFSET = 10

/**
 * Whether [isin] is an ISIN: two uppercase letters (the country), nine uppercase letters or digits and a check digit
 * that the Luhn algorithm confirms once the letters are spelled as numbers (A is 10, B is 11 and so on).
 */
fun isValidIsin(isin: String): Boolean {
    if (isin.length != ISIN_LENGTH) return false
    val shape = isin.take(2).all { it in 'A'..'Z' } &&
        isin.drop(2).all { it in 'A'..'Z' || it in '0'..'9' } &&
        isin.last() in '0'..'9'
    if (!shape) return false
    val digits = isin.flatMap { char ->
        if (char in '0'..'9') listOf(char - '0') else (char - 'A' + LETTER_DIGITS_OFFSET).toString().map { it - '0' }
    }
    // Luhn: from the check digit leftwards, every second digit is doubled (and its digits added).
    val sum = digits.asReversed().mapIndexed { index, digit ->
        if (index % 2 == 1) (digit * 2).let { it / 10 + it % 10 } else digit
    }.sum()
    return sum % 10 == 0
}

/**
 * The ISIN in a file name such as `Plusvalías y minusvalías fondo IE00BD0NCM55.csv`: the first word, made of letters
 * and digits alone, that is a valid ISIN (in any case). `null` when there is none.
 */
fun isinFromFileName(fileName: String): String? =
    fileName.split(Regex("[^\\p{L}\\p{N}]+")).firstNotNullOfOrNull { word ->
        word.uppercase().takeIf { it.length == ISIN_LENGTH && isValidIsin(it) }
    }
