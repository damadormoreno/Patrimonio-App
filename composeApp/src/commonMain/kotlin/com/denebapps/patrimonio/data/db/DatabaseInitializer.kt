package com.denebapps.patrimonio.data.db

import com.denebapps.patrimonio.data.db.dao.FxRateDao
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import com.denebapps.patrimonio.domain.model.FxSeedRates

/**
 * Seeds the FX fallback rates the first time the database is created, gated on
 * `fxRateDao.count()==0` so it is idempotent across launches.
 */
class DatabaseInitializer(
    private val appDatabase: AppDatabase,
    private val fxRateDao: FxRateDao,
) {
    suspend fun ensureSeeded() {
        appDatabase.writeTransaction {
            if (fxRateDao.count() == 0) {
                fxRateDao.upsertAll(SEED_FX_RATES)
            }
        }
    }

    companion object {
        /** [FxSeedRates] with `fetchedAtEpochMs = 0` so the first staleness check always attempts
         *  a live fetch. */
        val SEED_FX_RATES = FxSeedRates.scaled.map { (currency, rateScaled) ->
            FxRateEntity(currency = currency.code, rateToEurScaled = rateScaled, fetchedAtEpochMs = 0)
        }
    }
}
