package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.AssetClass.COMMODITIES
import com.denebapps.patrimonio.domain.model.AssetClass.EQUITY
import com.denebapps.patrimonio.domain.model.AssetClass.FIXED_INCOME
import com.denebapps.patrimonio.domain.model.AssetClass.MONEY_MARKET
import com.denebapps.patrimonio.domain.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Amounts below are written in euros and stored as cents: `eur(9_000)` is 900 000 cents. */
private fun eur(euros: Long) = Money(euros * 100)

private fun cents(cents: Long) = Money(cents)

private fun status(
    values: Map<AssetClass, Money>,
    targets: Map<AssetClass, Int>,
    toleranceBp: Int = 500,
    unclassified: Money = Money.ZERO,
    unclassifiedCount: Int = 0,
) = rebalanceStatus(values, unclassified, unclassifiedCount, targets, toleranceBp)

private fun RebalanceStatus.of(assetClass: AssetClass) = classes.single { it.assetClass == assetClass }

private fun List<ContributionShare>.amountOf(assetClass: AssetClass) = single { it.assetClass == assetClass }.amount

class RebalanceTest {
    // ---- the headline example ------------------------------------------------------------------------------

    @Test
    fun `80-20 target with 9000 and 1000 euros drifts 10 points and needs 1250 euros in fixed income`() {
        // V = 9 000 + 1 000 = 10 000 EUR = 1 000 000 cents.
        // Weights: 900 000 * 10 000 / 1 000 000 = 9 000 bp; 100 000 * 10 000 / 1 000 000 = 1 000 bp.
        // Drift: 9 000 - 8 000 = +1 000 bp; 1 000 - 2 000 = -1 000 bp. Both are beyond 500.
        // C = max(ceil(900 000 * 10 000 / 8 000) = 1 125 000, ceil(100 000 * 10 000 / 2 000) = 500 000) - 1 000 000
        //   = 125 000 cents = 1 250 EUR.
        // Split: total after = 1 125 000. Equity 8 000 * 1 125 000 / 10 000 - 900 000 = 0;
        //        fixed income 2 000 * 1 125 000 / 10 000 - 100 000 = 125 000.
        val result = status(
            values = mapOf(EQUITY to eur(9_000), FIXED_INCOME to eur(1_000)),
            targets = mapOf(EQUITY to 8_000, FIXED_INCOME to 2_000),
        )

        assertEquals(eur(10_000), result.totalValue)
        assertEquals(listOf(EQUITY, FIXED_INCOME), result.classes.map { it.assetClass })
        assertEquals(9_000, result.of(EQUITY).weightBp)
        assertEquals(1_000, result.of(FIXED_INCOME).weightBp)
        assertEquals(8_000, result.of(EQUITY).targetBp)
        assertEquals(1_000, result.of(EQUITY).driftBp)
        assertEquals(-1_000, result.of(FIXED_INCOME).driftBp)
        assertTrue(result.of(EQUITY).outOfBand)
        assertTrue(result.of(FIXED_INCOME).outOfBand)
        assertTrue(result.needsRebalancing)
        assertEquals(eur(1_250), result.contribution)
        assertEquals(
            listOf(ContributionShare(EQUITY, Money.ZERO), ContributionShare(FIXED_INCOME, eur(1_250))),
            result.contributionSplit,
        )
        // After the contribution equity is 900 000 / 1 125 000 = 80 %.
        assertEquals(900_000, (result.of(EQUITY).value + result.contributionSplit.amountOf(EQUITY)).minorUnits)
        assertEquals(Money(1_125_000), result.totalValue + result.contribution)
    }

    // ---- the band ------------------------------------------------------------------------------------------

    @Test
    fun `a portfolio within the band does not need rebalancing but still reports the contribution`() {
        // 8 300 / 1 700 EUR: V = 1 000 000 cents, weights 8 300 / 1 700 bp, drift +300 / -300, inside 500.
        // C = max(ceil(830 000 * 10 000 / 8 000) = 1 037 500, ceil(170 000 * 10 000 / 2 000) = 850 000) - 1 000 000
        //   = 37 500 cents. Equity already holds 80 % of 1 037 500 (830 000), so fixed income gets all of it.
        val result = status(
            values = mapOf(EQUITY to eur(8_300), FIXED_INCOME to eur(1_700)),
            targets = mapOf(EQUITY to 8_000, FIXED_INCOME to 2_000),
        )

        assertFalse(result.needsRebalancing)
        assertFalse(result.of(EQUITY).outOfBand)
        assertFalse(result.of(FIXED_INCOME).outOfBand)
        assertEquals(300, result.of(EQUITY).driftBp)
        assertEquals(cents(37_500), result.contribution)
        assertEquals(cents(37_500), result.contributionSplit.amountOf(FIXED_INCOME))
    }

    @Test
    fun `a drift exactly at the tolerance is inside the band and one basis point more is outside`() {
        // 8 500 / 1 500 EUR: weights 8 500 / 1 500 bp, drift +500 / -500.
        val values = mapOf(EQUITY to eur(8_500), FIXED_INCOME to eur(1_500))
        val targets = mapOf(EQUITY to 8_000, FIXED_INCOME to 2_000)

        val atTolerance = status(values, targets, toleranceBp = 500)
        assertFalse(atTolerance.needsRebalancing)
        assertFalse(atTolerance.of(EQUITY).outOfBand)

        val beyond = status(values, targets, toleranceBp = 499)
        assertTrue(beyond.needsRebalancing)
        assertTrue(beyond.of(EQUITY).outOfBand)
        assertTrue(beyond.of(FIXED_INCOME).outOfBand)
    }

    @Test
    fun `weights are rounded half to even to basis points and the band uses the rounded drift`() {
        // 1 / 3 is 3 333.33 bp -> 3 333; 2 / 3 is 6 666.67 bp -> 6 667.
        val result = status(
            values = mapOf(EQUITY to cents(100), FIXED_INCOME to cents(200)),
            targets = mapOf(EQUITY to 5_000, FIXED_INCOME to 5_000),
        )
        assertEquals(3_333, result.of(EQUITY).weightBp)
        assertEquals(6_667, result.of(FIXED_INCOME).weightBp)

        // Exact halves: 1 / 32 = 312.5 bp -> 312 (even), 3 / 32 = 937.5 bp -> 938 (even); 28 / 32 = 8 750 bp exactly.
        val ties = status(
            values = mapOf(EQUITY to cents(28), FIXED_INCOME to cents(1), MONEY_MARKET to cents(3)),
            targets = mapOf(EQUITY to 8_000, FIXED_INCOME to 1_000, MONEY_MARKET to 1_000),
        )
        assertEquals(8_750, ties.of(EQUITY).weightBp)
        assertEquals(312, ties.of(FIXED_INCOME).weightBp)
        assertEquals(938, ties.of(MONEY_MARKET).weightBp)
    }

    // ---- the contribution split ----------------------------------------------------------------------------

    @Test
    fun `three classes with a cent remainder still split the contribution to the exact cent`() {
        // Values 10.01 / 6.00 / 4.00 EUR (1 001 / 600 / 400 cents), targets 50 / 30 / 20 %. V = 2 001 cents.
        // C = max(ceil(1 001 * 10 000 / 5 000) = 2 002, ceil(600 * 10 000 / 3 000) = 2 000,
        //         ceil(400 * 10 000 / 2 000) = 2 000) - 2 001 = 1 cent.
        // Numerators t * 2 002 - v * 10 000: equity 0, fixed income 6 000, money market 4 000 (over 10 000), so every
        // floor is 0 and the one missing cent goes to the largest remainder, fixed income.
        val result = status(
            values = mapOf(EQUITY to cents(1_001), FIXED_INCOME to cents(600), MONEY_MARKET to cents(400)),
            targets = mapOf(EQUITY to 5_000, FIXED_INCOME to 3_000, MONEY_MARKET to 2_000),
        )

        assertEquals(cents(1), result.contribution)
        assertEquals(
            listOf(
                ContributionShare(EQUITY, cents(0)),
                ContributionShare(FIXED_INCOME, cents(1)),
                ContributionShare(MONEY_MARKET, cents(0)),
            ),
            result.contributionSplit,
        )
        assertEquals(
            result.contribution,
            result.contributionSplit.fold(Money.ZERO) { sum, share -> sum + share.amount },
        )
    }

    @Test
    fun `a targeted class that is not held gets the money and the ceiling avoids staying overweight`() {
        // Equity 1 000 EUR only (100 000 cents), targets 60 / 40. Weights 10 000 / 0 bp, drift +4 000 / -4 000.
        // C = max(ceil(100 000 * 10 000 / 6 000) = ceil(166 666.67) = 166 667, ceil(0) = 0) - 100 000 = 66 667.
        // Numerators with T = 166 667: equity 6 000 * 166 667 - 100 000 * 10 000 = 2 000 (floor 0, remainder 2 000);
        // fixed income 4 000 * 166 667 = 666 668 000 (floor 66 666, remainder 8 000). Floors add to 66 666, one cent
        // short of C: the largest remainder (fixed income) takes it, giving 66 667.
        val result = status(
            values = mapOf(EQUITY to eur(1_000)),
            targets = mapOf(EQUITY to 6_000, FIXED_INCOME to 4_000),
        )

        assertEquals(listOf(EQUITY, FIXED_INCOME), result.classes.map { it.assetClass })
        assertEquals(Money.ZERO, result.of(FIXED_INCOME).value)
        assertEquals(0, result.of(FIXED_INCOME).weightBp)
        assertEquals(-4_000, result.of(FIXED_INCOME).driftBp)
        assertEquals(cents(66_667), result.contribution)
        assertEquals(cents(0), result.contributionSplit.amountOf(EQUITY))
        assertEquals(cents(66_667), result.contributionSplit.amountOf(FIXED_INCOME))
        // Equity ends at 100 000 / 166 667, which is 59.99996 %: never above its 60 % because C is rounded up.
        assertTrue(100_000L * 10_000 <= 6_000L * 166_667)
    }

    // ---- classes that contributing cannot fix --------------------------------------------------------------

    @Test
    fun `a held class with target zero is only fixed by selling and is left out of the contribution`() {
        // Equity 4 500, fixed income 1 000, commodities 500 EUR; targets 80 / 20 / 0. V = 6 000 EUR = 600 000 cents.
        // Weights: 450 000 -> 7 500 bp; 100 000 -> 1 666.67 -> 1 667; 50 000 -> 833.33 -> 833.
        // Drift: 7 500 - 8 000 = -500 (exactly at tolerance, inside); 1 667 - 2 000 = -333; 833 - 0 = +833 (outside).
        // The contribution looks only at the targeted classes (V_t = 550 000):
        // C = max(ceil(450 000 * 10 000 / 8 000) = 562 500, ceil(100 000 * 10 000 / 2 000) = 500 000) - 550 000
        //   = 12 500, all to fixed income (equity already holds 80 % of 562 500 = 450 000).
        val result = status(
            values = mapOf(EQUITY to eur(4_500), FIXED_INCOME to eur(1_000), COMMODITIES to eur(500)),
            targets = mapOf(EQUITY to 8_000, FIXED_INCOME to 2_000),
        )

        assertEquals(eur(6_000), result.totalValue)
        assertEquals(listOf(EQUITY, FIXED_INCOME, COMMODITIES), result.classes.map { it.assetClass })
        assertEquals(-500, result.of(EQUITY).driftBp)
        assertFalse(result.of(EQUITY).outOfBand)
        assertEquals(-333, result.of(FIXED_INCOME).driftBp)
        assertEquals(833, result.of(COMMODITIES).driftBp)
        assertEquals(0, result.of(COMMODITIES).targetBp)
        assertTrue(result.of(COMMODITIES).outOfBand)
        assertTrue(result.needsRebalancing)
        assertEquals(listOf(COMMODITIES), result.onlySelling)
        assertEquals(cents(12_500), result.contribution)
        assertEquals(listOf(EQUITY, FIXED_INCOME), result.contributionSplit.map { it.assetClass })
        assertEquals(cents(12_500), result.contributionSplit.amountOf(FIXED_INCOME))
    }

    @Test
    fun `when the only held class has target zero nothing can be contributed to fix it`() {
        // Everything is in commodities (target 0): drift +10 000 bp, nothing to add to, C = 0.
        val result = status(
            values = mapOf(COMMODITIES to eur(1_000)),
            targets = mapOf(EQUITY to 10_000),
        )

        assertTrue(result.needsRebalancing)
        assertEquals(listOf(COMMODITIES), result.onlySelling)
        assertEquals(Money.ZERO, result.contribution)
        assertEquals(listOf(ContributionShare(EQUITY, Money.ZERO)), result.contributionSplit)
    }

    @Test
    fun `unclassified holdings are reported but excluded from the total and the weights`() {
        // 500 EUR unclassified in 2 products do not enter V: weights stay 9 000 / 1 000.
        val result = status(
            values = mapOf(EQUITY to eur(9_000), FIXED_INCOME to eur(1_000)),
            targets = mapOf(EQUITY to 8_000, FIXED_INCOME to 2_000),
            unclassified = eur(500),
            unclassifiedCount = 2,
        )

        assertEquals(eur(10_000), result.totalValue)
        assertEquals(9_000, result.of(EQUITY).weightBp)
        assertEquals(eur(500), result.unclassifiedValue)
        assertEquals(2, result.unclassifiedCount)
    }

    @Test
    fun `nothing classified means no alert and no contribution`() {
        // V = 0: weights are undefined, so no class is out of band and C = 0, even though every drift is -target.
        val result = status(
            values = emptyMap(),
            targets = mapOf(EQUITY to 8_000, FIXED_INCOME to 2_000),
            unclassified = eur(300),
            unclassifiedCount = 1,
        )

        assertEquals(Money.ZERO, result.totalValue)
        assertFalse(result.needsRebalancing)
        assertTrue(result.classes.none { it.outOfBand })
        assertEquals(listOf(0, 0), result.classes.map { it.weightBp })
        assertEquals(listOf(-8_000, -2_000), result.classes.map { it.driftBp })
        assertEquals(Money.ZERO, result.contribution)
        assertTrue(result.contributionSplit.all { it.amount == Money.ZERO })
        assertTrue(result.onlySelling.isEmpty())
        assertEquals(1, result.unclassifiedCount)
    }

    @Test
    fun `classes with zero value and zero target are not listed`() {
        val result = status(
            values = mapOf(EQUITY to eur(1_000), AssetClass.CRYPTO to Money.ZERO),
            targets = mapOf(EQUITY to 10_000),
        )

        assertEquals(listOf(EQUITY), result.classes.map { it.assetClass })
        assertFalse(result.needsRebalancing)
        assertEquals(Money.ZERO, result.contribution)
    }

    // ---- splitContribution ---------------------------------------------------------------------------------

    private val skewed = status(
        values = mapOf(EQUITY to eur(9_000), FIXED_INCOME to eur(1_000)),
        targets = mapOf(EQUITY to 8_000, FIXED_INCOME to 2_000),
    )

    @Test
    fun `a smaller amount than the minimum goes to the most underweight class first`() {
        // 800 EUR = 80 000 cents. Fill ratios value / target: equity 900 000 / 8 000 = 112.5, fixed income
        // 100 000 / 2 000 = 50. Fixed income alone: level (80 000 + 100 000) / 2 000 = 90, below equity's 112.5, so
        // equity does not join and fixed income takes everything.
        val split = skewed.splitContribution(eur(800))

        assertEquals(listOf(EQUITY, FIXED_INCOME), split.map { it.assetClass })
        assertEquals(cents(0), split.amountOf(EQUITY))
        assertEquals(eur(800), split.amountOf(FIXED_INCOME))
    }

    @Test
    fun `an amount equal to the minimum reproduces the contribution split`() {
        assertEquals(skewed.contributionSplit, skewed.splitContribution(skewed.contribution))
    }

    @Test
    fun `a bigger amount than the minimum is split to land exactly on target`() {
        // 2 000 EUR = 200 000 cents. Total after = 1 000 000 + 200 000 = 1 200 000.
        // Equity: 8 000 * 1 200 000 / 10 000 - 900 000 = 60 000; fixed income: 2 000 * 1 200 000 / 10 000 - 100 000
        // = 140 000. They add up to 200 000 and leave 80 % / 20 %.
        val split = skewed.splitContribution(eur(2_000))

        assertEquals(cents(60_000), split.amountOf(EQUITY))
        assertEquals(cents(140_000), split.amountOf(FIXED_INCOME))
    }

    @Test
    fun `a zero amount splits to zeros`() {
        assertTrue(skewed.splitContribution(Money.ZERO).all { it.amount == Money.ZERO })
    }

    @Test
    fun `a negative amount is rejected`() {
        assertFailsWith<IllegalArgumentException> { skewed.splitContribution(cents(-1)) }
    }

    @Test
    fun `money into an empty portfolio is split by the targets and ties on cents go in enum order`() {
        // Nothing held; targets 40 / 30 / 30 and 5 cents: numerators 20 000 / 15 000 / 15 000 over 10 000 give floors
        // 2 / 1 / 1 (4 cents); the missing cent goes to the largest remainder, and the two equal remainders (5 000)
        // are broken by enum order, so fixed income wins over money market.
        val empty = status(
            values = emptyMap(),
            targets = mapOf(EQUITY to 4_000, FIXED_INCOME to 3_000, MONEY_MARKET to 3_000),
        )
        val split = empty.splitContribution(cents(5))

        assertEquals(cents(2), split.amountOf(EQUITY))
        assertEquals(cents(2), split.amountOf(FIXED_INCOME))
        assertEquals(cents(1), split.amountOf(MONEY_MARKET))
    }

    // ---- targets validation --------------------------------------------------------------------------------

    @Test
    fun `targets that add up to 100 percent are valid`() {
        assertNull(validateTargets(mapOf(EQUITY to 8_000, FIXED_INCOME to 2_000)))
        assertNull(validateTargets(mapOf(EQUITY to 10_000)))
        assertNull(validateTargets(mapOf(EQUITY to 10_000, FIXED_INCOME to 0)))
    }

    @Test
    fun `targets that do not add up to 100 percent are rejected with their total`() {
        assertEquals(TargetsError.WrongSum(9_000), validateTargets(mapOf(EQUITY to 8_000, FIXED_INCOME to 1_000)))
        assertEquals(TargetsError.WrongSum(10_100), validateTargets(mapOf(EQUITY to 8_000, FIXED_INCOME to 2_100)))
        assertEquals(TargetsError.WrongSum(0), validateTargets(emptyMap()))
    }

    @Test
    fun `a negative or too big target is rejected with its class`() {
        assertEquals(TargetsError.OutOfRange(EQUITY, 10_001), validateTargets(mapOf(EQUITY to 10_001)))
        assertEquals(
            TargetsError.OutOfRange(FIXED_INCOME, -1),
            validateTargets(mapOf(EQUITY to 10_000, FIXED_INCOME to -1)),
        )
        // The first offending class in enum order is the one reported.
        assertEquals(
            TargetsError.OutOfRange(EQUITY, -500),
            validateTargets(mapOf(EQUITY to -500, FIXED_INCOME to 10_500)),
        )
    }

    @Test
    fun `rebalance status refuses invalid targets and tolerances`() {
        assertFailsWith<IllegalArgumentException> {
            status(values = mapOf(EQUITY to eur(1)), targets = mapOf(EQUITY to 9_000))
        }
        assertFailsWith<IllegalArgumentException> {
            status(values = mapOf(EQUITY to eur(1)), targets = mapOf(EQUITY to 10_000), toleranceBp = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            status(values = mapOf(EQUITY to eur(1)), targets = mapOf(EQUITY to 10_000), toleranceBp = 10_001)
        }
        assertFailsWith<IllegalArgumentException> {
            status(values = mapOf(EQUITY to cents(-1)), targets = mapOf(EQUITY to 10_000))
        }
    }
}
