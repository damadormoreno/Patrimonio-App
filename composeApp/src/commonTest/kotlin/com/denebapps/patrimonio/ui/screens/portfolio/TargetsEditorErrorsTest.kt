package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.TargetsError
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.repository.InvalidAllocationTargetsException
import com.denebapps.patrimonio.domain.repository.InvalidRebalanceToleranceException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the editor tells the user when the repository refuses to save. */
class TargetsEditorErrorsTest {
    @Test
    fun `targets that do not add up say so`() {
        val failure = InvalidAllocationTargetsException(TargetsError.WrongSum(9_900))

        assertEquals("Los porcentajes deben sumar 100 %.", targetsSaveErrorMessage(failure))
    }

    @Test
    fun `a class out of range names the class`() {
        val failure = InvalidAllocationTargetsException(TargetsError.OutOfRange(AssetClass.COMMODITIES, 12_000))

        assertEquals("«Oro y materias primas» debe estar entre 0 y 100 %.", targetsSaveErrorMessage(failure))
    }

    @Test
    fun `a tolerance out of range says the allowed band`() {
        assertEquals(
            "El margen debe estar entre 0 y 100 puntos.",
            targetsSaveErrorMessage(InvalidRebalanceToleranceException(20_000)),
        )
    }

    @Test
    fun `a portfolio that is gone says so`() {
        assertEquals("Esta cartera ya no existe.", targetsSaveErrorMessage(PortfolioNotFoundException("pf")))
    }

    @Test
    fun `anything else is a generic failure`() {
        assertEquals("No se pudo guardar el objetivo.", targetsSaveErrorMessage(IllegalStateException("disk")))
    }
}
