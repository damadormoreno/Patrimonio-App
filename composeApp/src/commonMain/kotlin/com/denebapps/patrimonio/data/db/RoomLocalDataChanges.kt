package com.denebapps.patrimonio.data.db

import com.denebapps.patrimonio.data.cloud.LocalDataChanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room's invalidation tracker over the tables a backup holds ([clearFinancialTables]'s list). */
class RoomLocalDataChanges(private val database: AppDatabase) : LocalDataChanges {
    override fun observe(): Flow<Unit> = database.invalidationTracker.createFlow(*BACKED_UP_TABLES).map { }

    private companion object {
        val BACKED_UP_TABLES = arrayOf(
            "assets",
            "liabilities",
            "account_groups",
            "account_group_members",
            "net_worth_snapshots",
            "savings_goals",
            "savings_goal_assets",
            "savings_goal_allocation_events",
            "savings_goal_link_events",
            "subscriptions",
        )
    }
}
