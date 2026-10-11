package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity

/**
 * One day of a product's price history, the cache behind the price charts. [provider] and [code] identify the
 * instrument like a holding's `provider`/`providerCode`, [epochDay] is the provider's UTC day, [priceScaled] the price
 * in major units x 1e6 and [currency] its ISO code. Not user data: re-downloadable, never in a backup.
 */
@Entity(tableName = "instrument_prices", primaryKeys = ["provider", "code", "epochDay"])
data class InstrumentPriceEntity(
    val provider: String,
    val code: String,
    val epochDay: Long,
    val priceScaled: Long,
    val currency: String,
)
