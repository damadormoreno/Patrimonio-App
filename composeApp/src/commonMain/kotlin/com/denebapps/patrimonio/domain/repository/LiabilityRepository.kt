package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.Liability
import kotlinx.coroutines.flow.Flow

interface LiabilityRepository {
    fun observeAll(): Flow<List<Liability>>

    suspend fun list(): List<Liability>

    suspend fun insert(liability: Liability)

    suspend fun update(liability: Liability)

    suspend fun deleteById(id: String)
}
