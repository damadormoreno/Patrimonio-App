package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.Asset
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun observeAll(): Flow<List<Asset>>

    suspend fun list(): List<Asset>

    suspend fun insert(asset: Asset)

    suspend fun update(asset: Asset)

    suspend fun deleteById(id: String)
}

class AssetNotFoundException(assetId: String) : NoSuchElementException("Asset '$assetId' was not found")

class LinkedAssetCurrencyChangeException(assetId: String) :
    IllegalStateException("Asset '$assetId' currency cannot change while linked to a savings goal")
