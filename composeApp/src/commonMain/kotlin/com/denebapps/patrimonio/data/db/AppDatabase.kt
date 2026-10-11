package com.denebapps.patrimonio.data.db

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.denebapps.patrimonio.data.db.dao.AccountGroupDao
import com.denebapps.patrimonio.data.db.dao.AccountTypeDao
import com.denebapps.patrimonio.data.db.dao.AssetDao
import com.denebapps.patrimonio.data.db.dao.FxHistoryDao
import com.denebapps.patrimonio.data.db.dao.FxRateDao
import com.denebapps.patrimonio.data.db.dao.InstrumentPriceDao
import com.denebapps.patrimonio.data.db.dao.LiabilityDao
import com.denebapps.patrimonio.data.db.dao.NetWorthDao
import com.denebapps.patrimonio.data.db.dao.PortfolioDao
import com.denebapps.patrimonio.data.db.dao.PortfolioHoldingDao
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDao
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AccountTypeEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.FxHistoryEntity
import com.denebapps.patrimonio.data.db.entity.FxHistorySyncEntity
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceSyncEntity
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity

@Database(
    entities = [
        AssetEntity::class,
        LiabilityEntity::class,
        AccountGroupEntity::class,
        AccountGroupMemberEntity::class,
        NetWorthSnapshotEntity::class,
        FxRateEntity::class,
        SavingsGoalEntity::class,
        SavingsGoalAllocationEventEntity::class,
        SavingsGoalLinkEventEntity::class,
        SavingsGoalAssetEntity::class,
        AccountTypeEntity::class,
        PortfolioEntity::class,
        PortfolioHoldingEntity::class,
        InstrumentPriceEntity::class,
        InstrumentPriceSyncEntity::class,
        FxHistoryEntity::class,
        FxHistorySyncEntity::class,
    ],
    version = 12,
    exportSchema = true,
    // 5 -> 6 only adds the custom account types table and the accounts' nullable emoji column. 6 -> 7 (drops
    // the subscriptions table) is the hand-written MIGRATION_6_7. 7 -> 8 only adds the portfolio holdings table.
    // 8 -> 9 only adds their nullable broker column. 9 -> 10 only adds the nullable fundamentals columns of the
    // holdings and the four re-downloadable history caches. 10 -> 11 (the portfolios, with a required foreign key on
    // the holdings) is the hand-written MIGRATION_10_11. 11 -> 12 only adds the nullable asset class column of the
    // holdings.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 11, to = 12),
    ],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao

    abstract fun liabilityDao(): LiabilityDao

    abstract fun accountGroupDao(): AccountGroupDao

    abstract fun netWorthDao(): NetWorthDao

    abstract fun fxRateDao(): FxRateDao

    abstract fun savingsGoalDao(): SavingsGoalDao

    abstract fun accountTypeDao(): AccountTypeDao

    abstract fun portfolioDao(): PortfolioDao

    abstract fun portfolioHoldingDao(): PortfolioHoldingDao

    abstract fun instrumentPriceDao(): InstrumentPriceDao

    abstract fun fxHistoryDao(): FxHistoryDao
}

// The Room KSP compiler generates the `actual` implementation of this object for every target
// (Android, iOS) — no `actual` is hand-written here.
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
