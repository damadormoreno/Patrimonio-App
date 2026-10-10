package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.quotes.CompositeQuoteSource
import com.denebapps.patrimonio.data.quotes.MorningstarApi
import com.denebapps.patrimonio.data.quotes.QuoteHttp
import com.denebapps.patrimonio.data.quotes.YahooFinanceApi
import com.denebapps.patrimonio.domain.repository.QuoteSource
import org.koin.dsl.module

/**
 * Market-data adapters behind [QuoteSource]. Reuses the shared Ktor `HttpClient` from [fxModule] and the `Clock`
 * from [dataModule].
 */
val quotesModule = module {
    single { QuoteHttp(get()) }
    single { MorningstarApi(get(), get()) }
    single { YahooFinanceApi(get()) }
    single<QuoteSource> { CompositeQuoteSource(get(), get()) }
}
