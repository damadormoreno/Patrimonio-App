package com.denebapps.patrimonio.ui.screens.savings

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoalCoverage
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageMappingTest {
    @Test
    fun `unavailable coverage maps to None`() {
        assertEquals(PatrimonioCoverageUi.None, coverageWarning(SavingsGoalCoverage.Unavailable))
    }

    @Test
    fun `fully covered shared asset maps to None`() {
        val coverage = SavingsGoalCoverage.SharedAsset(
            assetId = "a1",
            balance = CurrencyAmount(Money(100_00), Currency.EUR),
            reserved = CurrencyAmount(Money(70_00), Currency.EUR),
        )

        assertEquals(PatrimonioCoverageUi.None, coverageWarning(coverage))
    }

    @Test
    fun `undercovered shared asset maps to Warning`() {
        val coverage = SavingsGoalCoverage.SharedAsset(
            assetId = "a1",
            balance = CurrencyAmount(Money(100_00), Currency.EUR),
            reserved = CurrencyAmount(Money(120_00), Currency.EUR),
        )

        assertEquals(PatrimonioCoverageUi.Warning, coverageWarning(coverage))
    }

    @Test
    fun `reserved exactly equal to balance is not a warning`() {
        val coverage = SavingsGoalCoverage.SharedAsset(
            assetId = "a1",
            balance = CurrencyAmount(Money(100_00), Currency.EUR),
            reserved = CurrencyAmount(Money(100_00), Currency.EUR),
        )

        assertEquals(PatrimonioCoverageUi.None, coverageWarning(coverage))
    }

    @Test
    fun `undercovered shared group maps to GroupWarning`() {
        val coverage = SavingsGoalCoverage.SharedGroup(
            groupId = "g1",
            balance = CurrencyAmount(Money(100_00), Currency.EUR),
            reserved = CurrencyAmount(Money(120_00), Currency.EUR),
        )

        assertEquals(PatrimonioCoverageUi.GroupWarning(convertedAtCurrentRate = false), coverageWarning(coverage))
        assertEquals(
            PatrimonioCoverageUi.GroupWarning(convertedAtCurrentRate = true),
            coverageWarning(coverage, convertedAtCurrentRate = true),
        )
    }

    @Test
    fun `covered shared group maps to None`() {
        val coverage = SavingsGoalCoverage.SharedGroup(
            groupId = "g1",
            balance = CurrencyAmount(Money(100_00), Currency.EUR),
            reserved = CurrencyAmount(Money(100_00), Currency.EUR),
        )

        assertEquals(PatrimonioCoverageUi.None, coverageWarning(coverage))
    }
}
