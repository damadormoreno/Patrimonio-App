package com.denebapps.patrimonio.ui.components.charts

// Short axis labels for a heatmap, made from the full names (which a legend then maps back). Pure and unit-tested.

private const val MAX_LABEL_LENGTH = 3
private const val BLANK_LABEL = "?"
private val WORD_SEPARATORS = Regex("[\\s\\-_/]+")

/** Words that are not worth an initial: "Plan de pensiones" is "PP". */
private val CONNECTORS = setOf("de", "del", "la", "las", "el", "los", "y", "e", "of", "the", "and", "for", "a")

/**
 * A short label per name, in the same order, each different from the others: the initials of its words (at most
 * three, skipping connecting words and symbols) in upper case, or the first three letters of a one word name. Labels
 * that come out equal get a number from the second one on ("VGS", "VGS2"), never one another name already has. A blank
 * name is "?".
 */
fun heatmapLabels(names: List<String>): List<String> {
    val bases = names.map(::baseLabel)
    val reserved = bases.toSet()
    val used = mutableSetOf<String>()
    return bases.map { base ->
        var label = base
        var number = 2
        while (label in used || (label != base && label in reserved)) label = base + number++
        used += label
        label
    }
}

private fun baseLabel(name: String): String {
    val words = name.split(WORD_SEPARATORS)
        .map { word -> word.dropWhile { !it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
    val significant = words.filter { it.lowercase() !in CONNECTORS }.ifEmpty { words }
    return when {
        significant.isEmpty() -> BLANK_LABEL
        significant.size == 1 -> significant.single().filter { it.isLetterOrDigit() }.take(MAX_LABEL_LENGTH).uppercase()
        else -> significant.take(MAX_LABEL_LENGTH).map { it.first() }.joinToString("").uppercase()
    }
}
