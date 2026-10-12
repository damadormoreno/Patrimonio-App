package com.denebapps.patrimonio.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitsBalanceTest {
    @Test
    fun `a balance can be zero but never negative`() {
        assertTrue(UnitsBalance(0).isZero)
        assertEquals(UnitsBalance.ZERO, UnitsBalance(0))
        assertFailsWith<IllegalArgumentException> { UnitsBalance(-1) }
    }

    @Test
    fun `zero converts to no units and a positive balance to the same units`() {
        assertNull(UnitsBalance.ZERO.toUnits())
        assertEquals(Units(2_500_000), UnitsBalance(2_500_000).toUnits())
        assertEquals(UnitsBalance(7), Units(7).toBalance())
    }

    @Test
    fun `balances add, subtract and compare`() {
        assertEquals(UnitsBalance(5), UnitsBalance(2) + UnitsBalance(3))
        assertEquals(UnitsBalance(1), UnitsBalance(3) - UnitsBalance(2))
        assertTrue(UnitsBalance(3) > UnitsBalance(2))
        assertFailsWith<IllegalArgumentException> { UnitsBalance(1) - UnitsBalance(2) }
    }

    @Test
    fun `adding past the range fails instead of wrapping around`() {
        assertFailsWith<ArithmeticException> { UnitsBalance(Long.MAX_VALUE) + UnitsBalance(1) }
    }
}
