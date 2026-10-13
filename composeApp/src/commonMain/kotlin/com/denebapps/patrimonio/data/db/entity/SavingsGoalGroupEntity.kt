package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction row: [groupId] is one of the persisted account groups whose members the goal follows (the builtin "all
 * accounts" group is never a row, so never linkable). Both foreign keys cascade so the row never dangles, but
 * deleting a group goes through `AccountGroupRepositoryImpl.deleteGroup`, which records a `GROUP_DELETED` link event
 * first.
 */
@Entity(
    tableName = "savings_goal_groups",
    primaryKeys = ["goalId", "groupId"],
    foreignKeys = [
        ForeignKey(
            entity = SavingsGoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class SavingsGoalGroupEntity(
    val goalId: String,
    val groupId: String,
)
