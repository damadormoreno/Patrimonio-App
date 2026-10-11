package com.denebapps.patrimonio.domain.model

/** The name of the portfolio every install starts with, and the one that is left after clearing all data. */
const val DEFAULT_PORTFOLIO_NAME = "Mi cartera"

/** The longest portfolio name accepted, after trimming. */
const val MAX_PORTFOLIO_NAME_LENGTH = 40

/**
 * A named group of holdings ("Jubilación", "Hijos"). Each holding belongs to exactly one portfolio and there is always
 * at least one portfolio. [id] is a random UUID string; [sortOrder] orders the portfolios, ties broken by name. The
 * [name] is stored trimmed (see [normalizePortfolioName]); uniqueness, ignoring case, is a rule of the collection and
 * is kept by the repository.
 */
data class Portfolio(val id: String, val name: String, val sortOrder: Int) {
    init {
        require(id.isNotBlank()) { "portfolio id must not be blank" }
        require(normalizePortfolioName(name) == name) { "portfolio name must be trimmed, was '$name'" }
    }
}

/** What is wrong with a portfolio name. */
enum class PortfolioNameProblem { BLANK, TOO_LONG }

/** A portfolio name that cannot be used; the message is meant to be shown to the user. */
class InvalidPortfolioNameException(val problem: PortfolioNameProblem) :
    IllegalArgumentException(
        when (problem) {
            PortfolioNameProblem.BLANK -> "El nombre de la cartera no puede estar vacío."
            PortfolioNameProblem.TOO_LONG ->
                "El nombre de la cartera no puede tener más de $MAX_PORTFOLIO_NAME_LENGTH caracteres."
        },
    )

/**
 * The portfolio name as it is stored: trimmed, casing untouched.
 *
 * @throws InvalidPortfolioNameException when nothing is left after trimming or it is longer than
 *   [MAX_PORTFOLIO_NAME_LENGTH].
 */
fun normalizePortfolioName(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) throw InvalidPortfolioNameException(PortfolioNameProblem.BLANK)
    if (trimmed.length > MAX_PORTFOLIO_NAME_LENGTH) throw InvalidPortfolioNameException(PortfolioNameProblem.TOO_LONG)
    return trimmed
}
