package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A custom account type. [kind], [color]: enum names. Accounts point at it by id from their `group`. */
@Entity(tableName = "account_types")
data class AccountTypeEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val name: String,
    val emoji: String,
    val color: String,
    val position: Int,
)
