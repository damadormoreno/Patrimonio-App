package com.denebapps.patrimonio.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceSyncEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstrumentPriceDao {
    @Query("SELECT * FROM instrument_prices WHERE provider = :provider AND code = :code ORDER BY epochDay")
    fun observe(provider: String, code: String): Flow<List<InstrumentPriceEntity>>

    @Query("SELECT * FROM instrument_prices WHERE provider = :provider AND code = :code ORDER BY epochDay")
    suspend fun list(provider: String, code: String): List<InstrumentPriceEntity>

    @Query("SELECT MAX(epochDay) FROM instrument_prices WHERE provider = :provider AND code = :code")
    suspend fun lastEpochDay(provider: String, code: String): Long?

    @Query("SELECT COUNT(*) FROM instrument_prices")
    suspend fun count(): Int

    /** Replaces a day that is already stored, which is how an incremental sync revises its overlap. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<InstrumentPriceEntity>)

    @Query("SELECT * FROM instrument_price_sync WHERE provider = :provider AND code = :code")
    suspend fun findSync(provider: String, code: String): InstrumentPriceSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSync(state: InstrumentPriceSyncEntity)

    /** Drops the prices of every series that no quoted holding references. Part of [pruneUnreferenced]. */
    @Query(
        "DELETE FROM instrument_prices WHERE NOT EXISTS (SELECT 1 FROM portfolio_holdings h " +
            "WHERE h.kind = 'QUOTED' AND h.provider = instrument_prices.provider " +
            "AND h.providerCode = instrument_prices.code)",
    )
    suspend fun deleteUnreferencedPrices()

    /** Drops the sync state of every series that no quoted holding references. Part of [pruneUnreferenced]. */
    @Query(
        "DELETE FROM instrument_price_sync WHERE NOT EXISTS (SELECT 1 FROM portfolio_holdings h " +
            "WHERE h.kind = 'QUOTED' AND h.provider = instrument_price_sync.provider " +
            "AND h.providerCode = instrument_price_sync.code)",
    )
    suspend fun deleteUnreferencedSyncs()

    /** Only called by `clearFinancialTables`. */
    @Query("DELETE FROM instrument_prices")
    suspend fun deleteAllPrices()

    /** Only called by `clearFinancialTables`. */
    @Query("DELETE FROM instrument_price_sync")
    suspend fun deleteAllSyncs()
}

/** Drops the cached history of every instrument that no quoted holding uses. Run it inside a write transaction. */
suspend fun InstrumentPriceDao.pruneUnreferenced() {
    deleteUnreferencedPrices()
    deleteUnreferencedSyncs()
}
