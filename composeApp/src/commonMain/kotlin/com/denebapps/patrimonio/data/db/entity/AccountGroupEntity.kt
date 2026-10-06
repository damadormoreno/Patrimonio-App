package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The builtin "all accounts" group is NEVER a row here — it is synthesized at read time by the
 *  domain/repository layer. See [com.denebapps.patrimonio.domain.model.AccountGroup.allAccounts]. */
@Entity(tableName = "account_groups")
data class AccountGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val showBalance: Boolean,
    val sortOrder: Int,
)
