package com.denebapps.patrimonio.domain.importing

/**
 * The outcome of an import step that can fail in ways the user must be told about: a value ([Ok]) or a typed error
 * ([Err]). The errors are sealed types of the domain; the Spanish messages are the UI's business.
 */
sealed interface ImportResult<out T, out E> {
    data class Ok<out T>(val value: T) : ImportResult<T, Nothing>

    data class Err<out E>(val error: E) : ImportResult<Nothing, E>
}
