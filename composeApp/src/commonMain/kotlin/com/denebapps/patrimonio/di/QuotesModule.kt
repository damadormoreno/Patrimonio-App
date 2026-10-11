package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.quotes.CompositeQuoteSource
import com.denebapps.patrimonio.data.quotes.MorningstarApi
import com.denebapps.patrimonio.data.quotes.QuoteHttp
import com.denebapps.patrimonio.data.quotes.YahooFinanceApi
import com.denebapps.patrimonio.domain.repository.FundamentalsSource
import com.denebapps.patrimonio.domain.repository.PriceHistorySource
import com.denebapps.patrimonio.domain.repository.QuoteSource
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Market-data adapters behind [QuoteSource], [PriceHistorySource] and [FundamentalsSource]: one composite serves the
 * three ports (the price history and the fundamentals come from the same providers). Reuses the shared Ktor
 * `HttpClient` from [fxModule] and the `Clock` from [dataModule].
 */
val quotesModule = module {
    single { QuoteHttp(get()) }
    single { MorningstarApi(get(), get()) }
    single { YahooFinanceApi(get(), get()) }
    single { CompositeQuoteSource(get(), get()) } binds arrayOf(
        QuoteSource::class,
        PriceHistorySource::class,
        FundamentalsSource::class,
    )
}
