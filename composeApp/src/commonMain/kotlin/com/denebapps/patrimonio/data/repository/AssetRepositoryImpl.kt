package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.AssetDao
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDataSource
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.repository.AssetNotFoundException
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.LinkedAssetCurrencyChangeException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/** Every write runs INSIDE the same DB transaction as [NetWorthSnapshotUpserter.refreshCurrentMonth]
 *  (Decision #7) — a failed snapshot recompute rolls back the mutation too. */
class AssetRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val assetDao: AssetDao,
    private val snapshotUpserter: NetWorthSnapshotUpserter,
    private val seedingGate: SeedingGate,
    private val savingsGoalDataSource: SavingsGoalDataSource = appDatabase.savingsGoalDao(),
    private val clock: Clock = Clock.System,
    private val snapshotRefresh: (suspend () -> Unit)? = null,
) : AssetRepository {
    override fun observeAll(): Flow<List<Asset>> = flow {
        seedingGate.await()
        emitAll(assetDao.observeAll().map { rows -> rows.map(::toDomain) })
    }

    override suspend fun list(): List<Asset> {
        seedingGate.await()
        return assetDao.list().map(::toDomain)
    }

    override suspend fun insert(asset: Asset) {
        seedingGate.await()
        appDatabase.writeTransaction {
            assetDao.insert(toEntity(asset))
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    override suspend fun update(asset: Asset) {
        seedingGate.await()
        appDatabase.writeTransaction {
            val entity = toEntity(asset)
            val updated = assetDao.updateGuarded(
                id = entity.id,
                group = entity.group,
                name = entity.name,
                subtitle = entity.subtitle,
                amountMinor = entity.amountMinor,
                currency = entity.currency,
            )
            if (updated == 0) {
                val existing = assetDao.find(entity.id) ?: throw AssetNotFoundException(entity.id)
                if (existing.currency != entity.currency) throw LinkedAssetCurrencyChangeException(entity.id)
                error("Guarded asset update did not modify '${entity.id}'")
            }
            refreshSnapshot()
        }
    }

    override suspend fun deleteById(id: String) {
        seedingGate.await()
        appDatabase.writeTransaction {
            assetDao.find(id) ?: throw AssetNotFoundException(id)
            val linkedGoals = savingsGoalDataSource.listGoalsLinkedToAsset(id)
            if (linkedGoals.isNotEmpty()) {
                val timestampEpochMs = clock.now().toEpochMilliseconds()
                linkedGoals.forEach { goal ->
                    savingsGoalDataSource.insertLinkEvent(
                        SavingsGoalLinkEventEntity(
                            goalId = goal.id,
                            fromAssetId = id,
                            toAssetId = null,
                            kind = SavingsGoalLinkEventKind.ASSET_DELETED.name,
                            timestampEpochMs = timestampEpochMs,
                        ),
                    )
                }
                check(savingsGoalDataSource.clearLinkedAsset(id) == linkedGoals.size) {
                    "Asset '$id' links changed during deletion"
                }
            }
            check(assetDao.deleteIfUnlinked(id) == 1) { "Asset '$id' remained linked during deletion" }
            refreshSnapshot()
        }
    }

    private suspend fun refreshSnapshot() {
        snapshotRefresh?.invoke() ?: snapshotUpserter.refreshCurrentMonth()
    }
}

private fun toDomain(entity: AssetEntity): Asset = Asset(
    id = entity.id,
    group = Asset.AssetGroup.valueOf(entity.group),
    name = entity.name,
    subtitle = entity.subtitle,
    amount = CurrencyAmount(Money(entity.amountMinor), Currency.valueOf(entity.currency)),
)

private fun toEntity(asset: Asset): AssetEntity = AssetEntity(
    id = asset.id,
    group = asset.group.name,
    name = asset.name,
    subtitle = asset.subtitle,
    amountMinor = asset.amount.amount.minorUnits,
    currency = asset.amount.currency.code,
)
