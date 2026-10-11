package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioHoldingDao {
    @Query("SELECT * FROM portfolio_holdings ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<PortfolioHoldingEntity>>

    @Query("SELECT * FROM portfolio_holdings ORDER BY name COLLATE NOCASE, id")
    suspend fun list(): List<PortfolioHoldingEntity>

    @Query("SELECT * FROM portfolio_holdings WHERE id = :id")
    suspend fun find(id: String): PortfolioHoldingEntity?

    /** Fails when the id already exists: holdings are never silently overwritten. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(holding: PortfolioHoldingEntity)

    /** Replaces the whole row; @return how many rows matched (0 or 1). */
    @Update
    suspend fun update(holding: PortfolioHoldingEntity): Int

    /** Targeted so a price refresh never overwrites units edited meanwhile. @return rows touched (0 or 1). */
    @Query("UPDATE portfolio_holdings SET unitsScaled = :unitsScaled WHERE id = :id AND kind = 'QUOTED'")
    suspend fun updateUnits(id: String, unitsScaled: Long): Int

    @Query(
        "UPDATE portfolio_holdings SET valueMinor = :valueMinor, currency = :currency, " +
            "valuedAtEpochMs = :valuedAtEpochMs WHERE id = :id AND kind = 'MANUAL'",
    )
    suspend fun updateManualValue(id: String, valueMinor: Long, currency: String, valuedAtEpochMs: Long): Int

    /** Targeted so editing the broker never touches the value or the price. @return rows touched (0 or 1). */
    @Query("UPDATE portfolio_holdings SET broker = :broker WHERE id = :id")
    suspend fun updateBroker(id: String, broker: String?): Int

    /** Targeted so a fundamentals sync never touches the value or the price. @return rows touched (0 or 1). */
    @Query(
        "UPDATE portfolio_holdings SET ongoingChargePercentScaled = :ongoingChargePercentScaled, " +
            "riskLevel = :riskLevel, category = :category, managementCompany = :managementCompany, " +
            "fundamentalsFetchedAtEpochMs = :fetchedAtEpochMs WHERE id = :id",
    )
    suspend fun updateFundamentals(
        id: String,
        ongoingChargePercentScaled: Long?,
        riskLevel: Int?,
        category: String?,
        managementCompany: String?,
        fetchedAtEpochMs: Long,
    ): Int

    /** Records that the provider was asked, keeping the stored fundamentals. @return rows touched (0 or 1). */
    @Query("UPDATE portfolio_holdings SET fundamentalsFetchedAtEpochMs = :fetchedAtEpochMs WHERE id = :id")
    suspend fun touchFundamentals(id: String, fetchedAtEpochMs: Long): Int

    @Query(
        "UPDATE portfolio_holdings SET priceScaled = :priceScaled, priceDateEpochDay = :priceDateEpochDay, " +
            "priceFetchedAtEpochMs = :fetchedAtEpochMs WHERE id = :id AND kind = 'QUOTED'",
    )
    suspend fun updatePrice(id: String, priceScaled: Long, priceDateEpochDay: Long, fetchedAtEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM portfolio_holdings WHERE portfolioId = :portfolioId")
    suspend fun countInPortfolio(portfolioId: String): Int

    /** Targeted so moving a holding never touches its value or price. @return rows touched (0 or 1). */
    @Query("UPDATE portfolio_holdings SET portfolioId = :portfolioId WHERE id = :id")
    suspend fun updatePortfolio(id: String, portfolioId: String): Int

    /** Puts every holding of [from] in [to]. @return how many holdings moved. */
    @Query("UPDATE portfolio_holdings SET portfolioId = :to WHERE portfolioId = :from")
    suspend fun moveAll(from: String, to: String): Int

    @Query("DELETE FROM portfolio_holdings WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Only called by `clearFinancialTables` and the backup import. */
    @Query("DELETE FROM portfolio_holdings")
    suspend fun deleteAll()
}
