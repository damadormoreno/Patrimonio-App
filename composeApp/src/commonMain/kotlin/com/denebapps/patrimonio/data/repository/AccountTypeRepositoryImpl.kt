package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.entity.AccountTypeEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.domain.repository.AccountTypeNotFoundException
import com.denebapps.patrimonio.domain.repository.AccountTypeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Custom account types in Room. Deleting one moves its accounts to the built-in "Otros" type in the same
 * transaction, so no account is ever left with a type that does not exist. Totals do not change, so the
 * month's snapshot needs no refresh.
 */
class AccountTypeRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val newId: () -> String = ::randomTypeId,
) : AccountTypeRepository {
    private val dao = appDatabase.accountTypeDao()

    override fun observeAll(): Flow<List<CustomAccountType>> = dao.observeAll().map { rows -> rows.map(::toDomain) }

    override suspend fun create(kind: AccountKind, name: String, emoji: String, color: TypeColor): String {
        val id = newId()
        appDatabase.writeTransaction {
            dao.insert(
                AccountTypeEntity(
                    id = id,
                    kind = kind.name,
                    name = name.trim(),
                    emoji = emoji.trim(),
                    color = color.name,
                    position = dao.maxPosition(kind.name) + 1,
                ),
            )
        }
        return id
    }

    override suspend fun update(id: String, name: String, emoji: String, color: TypeColor) {
        appDatabase.writeTransaction {
            if (dao.update(id, name.trim(), emoji.trim(), color.name) == 0) throw AccountTypeNotFoundException(id)
        }
    }

    override suspend fun delete(id: String) {
        appDatabase.writeTransaction {
            val type = dao.find(id) ?: throw AccountTypeNotFoundException(id)
            when (AccountKind.valueOf(type.kind)) {
                AccountKind.ASSET -> appDatabase.assetDao().changeType(id, Asset.AssetGroup.OTHER)
                AccountKind.LIABILITY -> appDatabase.liabilityDao().changeType(id, Liability.LiabilityGroup.OTHER)
            }
            dao.delete(id)
        }
    }
}

private fun toDomain(entity: AccountTypeEntity) = CustomAccountType(
    id = entity.id,
    kind = AccountKind.valueOf(entity.kind),
    name = entity.name,
    emoji = entity.emoji,
    color = TypeColor.entries.firstOrNull { it.name == entity.color } ?: TypeColor.GREY,
    position = entity.position,
)

@OptIn(ExperimentalUuidApi::class)
private fun randomTypeId(): String = Uuid.random().toString()
