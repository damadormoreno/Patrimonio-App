package com.denebapps.patrimonio.data.db

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.denebapps.patrimonio.data.db.dao.AccountGroupDao
import com.denebapps.patrimonio.data.db.dao.AssetDao
import com.denebapps.patrimonio.data.db.dao.FxRateDao
import com.denebapps.patrimonio.data.db.dao.LiabilityDao
import com.denebapps.patrimonio.data.db.dao.NetWorthDao
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDao
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
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
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao

    abstract fun liabilityDao(): LiabilityDao

    abstract fun accountGroupDao(): AccountGroupDao

    abstract fun netWorthDao(): NetWorthDao

    abstract fun fxRateDao(): FxRateDao

    abstract fun savingsGoalDao(): SavingsGoalDao
}

// The Room KSP compiler generates the `actual` implementation of this object for every target
// (Android, iOS) — no `actual` is hand-written here.
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
