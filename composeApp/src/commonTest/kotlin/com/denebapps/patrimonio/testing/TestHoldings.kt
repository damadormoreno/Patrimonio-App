package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PRICE_SCALE
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.UnitsBalance
import kotlinx.datetime.LocalDate

/** A holding worth the [minor] units of [currency] the user typed in. */
fun manualTestHolding(id: String, minor: Long, currency: Currency = Currency.EUR) = PortfolioHolding(
    id = id,
    portfolioId = TEST_PORTFOLIO_ID,
    name = "Holding $id",
    type = InstrumentType.OTHER,
    isin = null,
    ticker = null,
    valuation = HoldingValuation.Manual(CurrencyAmount(Money(minor), currency), valuedAtEpochMs = 0L),
)

/**
 * A quoted holding of [units] whole units at [price] whole [currency] units each. A `null` [price] is a holding
 * with no quote yet (no value), and zero [units] a closed position (worth nothing, quote or not).
 */
fun quotedTestHolding(id: String, units: Long, price: Long?, currency: Currency = Currency.EUR) = PortfolioHolding(
    id = id,
    portfolioId = TEST_PORTFOLIO_ID,
    name = "Fund $id",
    type = InstrumentType.FUND,
    isin = null,
    ticker = null,
    valuation = HoldingValuation.Quoted(
        ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P-$id"),
        currency = currency,
        units = UnitsBalance(units * UNITS_SCALE),
        lastQuote = price?.let { LastQuote(Price(it * PRICE_SCALE, currency), LocalDate(2026, 10, 9), 1L) },
    ),
)
