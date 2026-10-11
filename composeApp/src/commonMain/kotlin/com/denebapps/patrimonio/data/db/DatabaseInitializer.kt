package com.denebapps.patrimonio.data.db

import com.denebapps.patrimonio.data.db.dao.FxRateDao
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import com.denebapps.patrimonio.domain.model.FxSeedRates

/**
 * Seeds the first-run data the first time the database is created, each part gated on its table being empty so it is
 * idempotent across launches: the FX fallback rates, and the portfolio "Mi cartera" (id from [newPortfolioId], a random
 * UUID by default), because there is always at least one portfolio. A database upgraded from version 10 already got its
 * "Mi cartera" from the migration.
 */
class DatabaseInitializer(
    private val appDatabase: AppDatabase,
    private val fxRateDao: FxRateDao,
    private val newPortfolioId: () -> String = ::randomPortfolioId,
) {
    suspend fun ensureSeeded() {
        appDatabase.writeTransaction {
            if (fxRateDao.count() == 0) {
                fxRateDao.upsertAll(SEED_FX_RATES)
            }
            appDatabase.ensureDefaultPortfolio(newPortfolioId)
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
