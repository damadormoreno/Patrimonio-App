package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.TypeColor
import kotlinx.coroutines.flow.Flow

/** The account types the user created. The built-in ones live in code and are not stored. */
interface AccountTypeRepository {
    /** Both kinds, each in [CustomAccountType.position] order. */
    fun observeAll(): Flow<List<CustomAccountType>>

    /** @return the new type's id. */
    suspend fun create(kind: AccountKind, name: String, emoji: String, color: TypeColor): String

    /** Renames it or changes its emoji or colour; its kind never changes. */
    suspend fun update(id: String, name: String, emoji: String, color: TypeColor)

    /** Deletes it; its accounts move to the built-in "Otros" type of the same kind, in the same transaction. */
    suspend fun delete(id: String)
}

class AccountTypeNotFoundException(typeId: String) : NoSuchElementException("Account type '$typeId' was not found")
