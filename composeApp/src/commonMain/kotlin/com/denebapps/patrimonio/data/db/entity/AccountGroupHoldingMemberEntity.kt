package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * Junction row for the OVERLAPPING many-to-many [AccountGroupEntity] <-> [PortfolioHoldingEntity] membership: a
 * portfolio product (fund, ETF, stock, crypto...) that counts in a group. Kept apart from [AccountGroupMemberEntity]
 * so the accounts' foreign key to `assets` stays intact. Deleting the group or the holding deletes the row.
 */
@Entity(
    tableName = "account_group_holding_members",
    primaryKeys = ["groupId", "holdingId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PortfolioHoldingEntity::class,
            parentColumns = ["id"],
            childColumns = ["holdingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("holdingId")],
)
data class AccountGroupHoldingMemberEntity(
    val groupId: String,
    val holdingId: String,
)
