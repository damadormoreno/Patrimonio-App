package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

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
