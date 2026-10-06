package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetWorthTest {
    private val rates = FxRates(emptyMap())

    @Test
    fun `net worth is assets minus liabilities`() {
        val assets = listOf(
            Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(300000), Currency.EUR)),
        )
        val liabs = listOf(
            Liability(
                "l1",
                Liability.LiabilityGroup.LOAN,
                "Prestamo",
                null,
                CurrencyAmount(Money(175000), Currency.EUR),
            ),
        )
        assertEquals(Money(125000), netWorth(assets, liabs, rates))
    }

    @Test
    fun `month delta with no earlier snapshot is null`() {
        assertNull(monthDelta(current = Money(302530), previous = null))
    }

    @Test
    fun `month delta computes the diff against the resolved previous snapshot`() {
        // "Most recent prior" resolution (including skip-month gaps) happens at the
        // repository/DAO layer (Slice 2, NetWorthDao.findMostRecentBefore); this pure function
        // only needs to diff whatever previous snapshot value it is given.
        assertEquals(Money(4030), monthDelta(current = Money(302530), previous = Money(298500)))
    }
}
