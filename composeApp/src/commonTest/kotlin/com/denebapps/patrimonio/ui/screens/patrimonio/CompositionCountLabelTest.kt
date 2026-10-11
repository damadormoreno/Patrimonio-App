package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.ui.components.PORTFOLIO_LOOK
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositionCountLabelTest {
    private fun share(groupId: String) = GroupShareUi(groupId, groupId, Money(100), sharePct = 50)

    private val portfolio = GroupShareUi(
        PORTFOLIO_LOOK.id,
        PORTFOLIO_LOOK.label,
        Money(100),
        sharePct = 50,
        type = PORTFOLIO_LOOK,
    )

    @Test
    fun `the Cartera share is not counted as an account group`() {
        assertEquals("2 grupos · Cartera", compositionCountLabel(listOf(share("BANK"), share("CASH"), portfolio)))
    }

    @Test
    fun `account groups alone are counted`() {
        assertEquals("2 grupos", compositionCountLabel(listOf(share("BANK"), share("CASH"))))
    }

    @Test
    fun `one account group reads in the singular`() {
        assertEquals("1 grupo", compositionCountLabel(listOf(share("BANK"))))
        assertEquals("1 grupo · Cartera", compositionCountLabel(listOf(portfolio, share("BANK"))))
    }

    @Test
    fun `only the Cartera share names just Cartera`() {
        assertEquals("Cartera", compositionCountLabel(listOf(portfolio)))
    }
}
