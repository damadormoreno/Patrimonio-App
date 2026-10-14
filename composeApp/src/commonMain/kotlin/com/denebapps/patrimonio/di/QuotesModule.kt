package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.datasources.DataSourceSettingsStore
import com.denebapps.patrimonio.data.datasources.DataStoreDataSourceSettingsStore
import com.denebapps.patrimonio.data.datastore.DATA_SOURCES_FILE_NAME
import com.denebapps.patrimonio.data.datastore.dataStoreFilePath
import com.denebapps.patrimonio.data.platform.PlatformContext
import com.denebapps.patrimonio.data.quotes.CoinGeckoApi
import com.denebapps.patrimonio.data.quotes.CompositeQuoteSource
import com.denebapps.patrimonio.data.quotes.EodhdApi
import com.denebapps.patrimonio.data.quotes.EodhdQuoteSource
import com.denebapps.patrimonio.data.quotes.InstrumentIdentityLookup
import com.denebapps.patrimonio.data.quotes.MarketDataSource
import com.denebapps.patrimonio.data.quotes.MorningstarApi
import com.denebapps.patrimonio.data.quotes.QuoteHttp
import com.denebapps.patrimonio.data.quotes.RoomInstrumentIdentityLookup
import com.denebapps.patrimonio.data.quotes.YahooFinanceApi
import com.denebapps.patrimonio.domain.repository.FundamentalsSource
import com.denebapps.patrimonio.domain.repository.PriceHistorySource
import com.denebapps.patrimonio.domain.repository.QuoteSource
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Market-data adapters behind [QuoteSource], [PriceHistorySource] and [FundamentalsSource]: one [CompositeQuoteSource]
 * (Morningstar, Yahoo, CoinGecko) serves the three ports (the price history and the fundamentals come from the same
 * providers), wrapped in [EodhdQuoteSource], which adds the user's own EODHD key as a fallback or primary source.
 * Without a key the decorator is a pass-through. The key lives in the device-only [DataSourceSettingsStore]. Reuses the
 * shared Ktor `HttpClient` from [fxModule], the `Clock` from [dataModule] and the holdings DAO from the database module.
 */
val quotesModule = module {
    single { QuoteHttp(get()) }
    single { MorningstarApi(get(), get()) }
    single { YahooFinanceApi(get(), get()) }
    single { CoinGeckoApi(get(), get()) }
    single { CompositeQuoteSource(get(), get(), get()) }
    single { EodhdApi(get(), get()) }
    single<DataSourceSettingsStore> {
        DataStoreDataSourceSettingsStore(dataStoreFilePath(get<PlatformContext>(), DATA_SOURCES_FILE_NAME))
    }
    single<InstrumentIdentityLookup> { RoomInstrumentIdentityLookup(get()) }
    single {
        EodhdQuoteSource(
            delegate = get<CompositeQuoteSource>(),
            eodhd = get(),
            settings = get(),
            identities = get(),
            clock = get(),
        )
    } binds arrayOf(
        MarketDataSource::class,
        QuoteSource::class,
        PriceHistorySource::class,
        FundamentalsSource::class,
    )
}
