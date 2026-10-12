package com.denebapps.patrimonio.domain.importing

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.MovementDraft
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportPlanTest {
    private val file = readMyInvestorFile(MyInvestorReaderTest.SAMPLE_CRLF.encodeToByteArray(), FILE_NAME).ok()

    @Test
    fun `every open lot becomes a euro purchase on its fiscal date for its cost, with no fee`() {
        val plan = planImport(file, Units(100_000_000), "holding-1").ok()

        assertEquals(5, plan.buys.size)
        assertTrue(plan.buys.all { it.type == MovementType.BUY })
        assertTrue(plan.buys.all { it.fee == Money.ZERO && it.feeEur == Money.ZERO })
        assertTrue(plan.buys.all { it.note == "MyInvestor" })
        assertTrue(plan.buys.all { it.amount?.currency == Currency.EUR && it.amountEur == it.amount?.amount })
        assertEquals(
            listOf(
                LocalDate(2023, 3, 15) to Money(50_000),
                LocalDate(2023, 6, 2) to Money(123_456),
                LocalDate(2023, 6, 2) to Money(10_000),
                LocalDate(2024, 11, 20) to Money(25_050),
                LocalDate(2025, 2, 9) to Money(7_500),
            ),
            plan.buys.map { it.date to it.amountEur },
        )
    }

    @Test
    fun `the purchases are ordered by date and, on the same day, as they come in the file`() {
        val plan = planImport(file, Units(100_000_000), "holding-1").ok()

        assertEquals(
            listOf(Money(50_000), Money(123_456), Money(10_000), Money(25_050), Money(7_500)),
            plan.buys.map { it.amountEur },
        )
    }

    @Test
    fun `the units of the purchases add up exactly to the total and follow the market value`() {
        val total = 123_456_789L
        val plan = planImport(file, Units(total), "holding-1").ok()

        assertEquals(total, plan.buys.sumOf { it.units!!.scaled })
        val marketValues = listOf(56_210L, 130_140L, 10_430L, 24_025L, 7_605L) // in the order of the purchases
        val sumValue = marketValues.sum()
        plan.buys.forEachIndexed { index, buy ->
            val gap = buy.units!!.scaled * sumValue - total * marketValues[index]
            assertTrue(gap > -sumValue && gap < sumValue, "purchase $index is off by $gap/$sumValue")
        }
    }

    @Test
    fun `lots on the same day with the same value share the units evenly, the earlier one getting the odd one`() {
        val twins = readMyInvestorFile(
            "${MyInvestorReaderTest.HEADER}\n02/06/2023;10;10;0\n02/06/2023;10;10;0\n".encodeToByteArray(),
            FILE_NAME,
        ).ok()

        val plan = planImport(twins, Units(5), "holding-1").ok()

        assertEquals(listOf(Units(3), Units(2)), plan.buys.map { it.units })
    }

    @Test
    fun `the plan carries the holding id and a summary of the file`() {
        val plan = planImport(file, Units(100_000_000), "holding-1").ok()

        assertEquals("holding-1", plan.holdingId)
        assertEquals(
            ImportSummary(
                lotCount = 5,
                totalCost = Money(216_006),
                totalMarketValue = Money(228_410),
                redeemedCount = 1,
                redeemedResult = Money(1_234),
            ),
            plan.summary,
        )
        assertEquals(Units(100_000_000), plan.totalUnits)
    }

    @Test
    fun `the summary adds up several redeemed lots, gains and losses`() {
        val text = "${MyInvestorReaderTest.HEADER}\n01/01/2024;10;11;1\n" +
            "01/01/2020;0;0;12,34\n01/01/2021;0;0;-2,30\n01/01/2022;0;0;0\n"

        val summary = readMyInvestorFile(text.encodeToByteArray(), FILE_NAME).ok().summary

        assertEquals(3, summary.redeemedCount)
        assertEquals(Money(1_004), summary.redeemedResult)
    }

    @Test
    fun `a draft is exactly what the movement repository accepts for a euro purchase`() {
        val plan = planImport(file, Units(100_000_000), "holding-1").ok()

        val first = plan.buys.first()
        assertEquals(
            MovementDraft(
                type = MovementType.BUY,
                date = LocalDate(2023, 3, 15),
                units = first.units,
                amount = CurrencyAmount(Money(50_000), Currency.EUR),
                fee = Money.ZERO,
                amountEur = Money(50_000),
                feeEur = Money.ZERO,
                note = "MyInvestor",
            ),
            first,
        )
    }

    @Test
    fun `a total too small for the lots gives no plan`() {
        assertEquals(UnitSplitError.TotalTooSmall, planImport(file, Units(3), "holding-1").err())
    }

    private companion object {
        const val FILE_NAME = MyInvestorReaderTest.FILE_NAME
    }
}
