package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/** Junction row for OVERLAPPING many-to-many [AccountGroupEntity] <-> [AssetEntity] membership. */
@Entity(
    tableName = "account_group_members",
    primaryKeys = ["groupId", "assetId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("assetId")],
)
data class AccountGroupMemberEntity(
    val groupId: String,
    val assetId: String,
)
