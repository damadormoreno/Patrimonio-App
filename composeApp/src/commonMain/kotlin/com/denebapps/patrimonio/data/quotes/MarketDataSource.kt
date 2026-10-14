package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.repository.FundamentalsSource
import com.denebapps.patrimonio.domain.repository.PriceHistorySource
import com.denebapps.patrimonio.domain.repository.QuoteSource

/** The three market-data ports in one, so a decorator can wrap [CompositeQuoteSource] and be wrapped in turn. */
interface MarketDataSource : QuoteSource, PriceHistorySource, FundamentalsSource
