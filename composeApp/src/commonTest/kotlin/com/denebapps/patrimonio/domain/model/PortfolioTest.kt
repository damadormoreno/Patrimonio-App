package com.denebapps.patrimonio.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortfolioTest {
    @Test
    fun `the default portfolio is called Mi cartera`() {
        assertEquals("Mi cartera", DEFAULT_PORTFOLIO_NAME)
    }

    @Test
    fun `normalizing trims the text and keeps the casing the user typed`() {
        assertEquals("Jubilación", normalizePortfolioName("  Jubilación \n"))
        assertEquals("hijos", normalizePortfolioName("hijos"))
    }

    @Test
    fun `a blank name is rejected`() {
        listOf("", "   ", "\t\n").forEach { raw ->
            val error = assertFailsWith<InvalidPortfolioNameException> { normalizePortfolioName(raw) }
            assertEquals(PortfolioNameProblem.BLANK, error.problem)
        }
    }

    @Test
    fun `a name may be as long as the cap but not longer`() {
        val atCap = "C".repeat(MAX_PORTFOLIO_NAME_LENGTH)

        assertEquals(40, MAX_PORTFOLIO_NAME_LENGTH)
        assertEquals(atCap, normalizePortfolioName("  $atCap  "))
        val error = assertFailsWith<InvalidPortfolioNameException> { normalizePortfolioName(atCap + "x") }
        assertEquals(PortfolioNameProblem.TOO_LONG, error.problem)
    }

    @Test
    fun `the problems are explained in Spanish`() {
        val blank = assertFailsWith<InvalidPortfolioNameException> { normalizePortfolioName(" ") }
        val long = assertFailsWith<InvalidPortfolioNameException> { normalizePortfolioName("x".repeat(41)) }

        assertEquals("El nombre de la cartera no puede estar vacío.", blank.message)
        assertEquals("El nombre de la cartera no puede tener más de 40 caracteres.", long.message)
    }

    @Test
    fun `a portfolio keeps its id name and order`() {
        val portfolio = Portfolio(id = "pf-1", name = "Jubilación", sortOrder = 2)

        assertEquals("pf-1", portfolio.id)
        assertEquals("Jubilación", portfolio.name)
        assertEquals(2, portfolio.sortOrder)
    }

    @Test
    fun `a portfolio needs an id and a trimmed valid name`() {
        assertFailsWith<IllegalArgumentException> { Portfolio(id = " ", name = "Jubilación", sortOrder = 0) }
        assertFailsWith<InvalidPortfolioNameException> { Portfolio(id = "pf-1", name = "", sortOrder = 0) }
        assertFailsWith<IllegalArgumentException> { Portfolio(id = "pf-1", name = " Hijos", sortOrder = 0) }
        assertFailsWith<InvalidPortfolioNameException> {
            Portfolio(id = "pf-1", name = "x".repeat(41), sortOrder = 0)
        }
    }
}
