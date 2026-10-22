package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

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
