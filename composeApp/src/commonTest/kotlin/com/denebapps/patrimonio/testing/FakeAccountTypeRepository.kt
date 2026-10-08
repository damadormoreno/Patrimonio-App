package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.domain.repository.AccountTypeNotFoundException
import com.denebapps.patrimonio.domain.repository.AccountTypeRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [AccountTypeRepository]; ids are `type-1`, `type-2`… [deleted] records deletions. */
class FakeAccountTypeRepository(vararg initial: CustomAccountType) : AccountTypeRepository {
    val types = MutableStateFlow(initial.toList())
    val deleted = mutableListOf<String>()
    private var next = 0

    override fun observeAll() = types

    override suspend fun create(kind: AccountKind, name: String, emoji: String, color: TypeColor): String {
        val id = "type-${++next}"
        val position = types.value.count { it.kind == kind }
        types.value += CustomAccountType(id, kind, name.trim(), emoji.trim(), color, position)
        return id
    }

    override suspend fun update(id: String, name: String, emoji: String, color: TypeColor) {
        if (types.value.none { it.id == id }) throw AccountTypeNotFoundException(id)
        types.value = types.value.map {
            if (it.id == id) it.copy(name = name.trim(), emoji = emoji.trim(), color = color) else it
        }
    }

    override suspend fun delete(id: String) {
        if (types.value.none { it.id == id }) throw AccountTypeNotFoundException(id)
        deleted += id
        types.value = types.value.filter { it.id != id }
    }
}
