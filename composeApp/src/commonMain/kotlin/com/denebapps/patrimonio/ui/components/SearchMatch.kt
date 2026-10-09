package com.denebapps.patrimonio.ui.components

/** From this many options on, a picker shows its search field; shorter lists are read at a glance. */
const val SEARCH_THRESHOLD = 6

/**
 * Whether any of [texts] contains every word of [query], ignoring case and Spanish accents: "ing nar"
 * finds "ING Cuenta Naranja" and "espana" finds "España". A blank query matches everything.
 */
fun matchesSearch(query: String, vararg texts: String?): Boolean {
    val words = foldForSearch(query).split(' ').filter(String::isNotEmpty)
    if (words.isEmpty()) return true
    val haystack = texts.filterNotNull().joinToString(" ") { foldForSearch(it) }
    return words.all { it in haystack }
}

private val FOLDS = mapOf(
    'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a',
    'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
    'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o',
    'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
    'ñ' to 'n', 'ç' to 'c',
)

private fun foldForSearch(text: String): String = buildString(text.length) {
    text.lowercase().forEach { append(FOLDS[it] ?: it) }
}
