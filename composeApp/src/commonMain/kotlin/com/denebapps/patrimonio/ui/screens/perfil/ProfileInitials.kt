package com.denebapps.patrimonio.ui.screens.perfil

/**
 * Derives the 1–2 letter avatar initials from the profile names (first letter of each trimmed,
 * non-blank name, uppercased). Returns `null` when both are blank so callers render the neutral
 * avatar instead. Shared by the Settings profile card and the Perfil avatar (design.md D9).
 */
fun profileInitials(firstName: String, lastName: String): String? = listOf(firstName.trim(), lastName.trim())
    .filter(String::isNotEmpty)
    .joinToString("") { it.first().uppercaseChar().toString() }
    .ifEmpty { null }
