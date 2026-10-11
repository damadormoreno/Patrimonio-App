package com.denebapps.patrimonio.data.db

import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
import com.denebapps.patrimonio.domain.model.DEFAULT_PORTFOLIO_NAME
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A fresh random UUID for a portfolio. */
@OptIn(ExperimentalUuidApi::class)
internal fun randomPortfolioId(): String = Uuid.random().toString()

/**
 * Keeps the rule "there is always at least one portfolio": when there is none, inserts an empty "Mi cartera" with the id
 * [newId] returns. A database with portfolios is left alone. Callers MUST run this inside [writeTransaction] (fresh
 * install seeding, clear-all and the backup import all do).
 */
internal suspend fun AppDatabase.ensureDefaultPortfolio(newId: () -> String = ::randomPortfolioId) {
    if (portfolioDao().count() == 0) {
        portfolioDao().insert(PortfolioEntity(id = newId(), name = DEFAULT_PORTFOLIO_NAME, sortOrder = 0))
    }
}
