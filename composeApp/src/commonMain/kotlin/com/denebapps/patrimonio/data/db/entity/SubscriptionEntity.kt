package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** [cycle] holds the `BillingCycle` name and [currency] the ISO code, mapped in the repository.
 *  Deleting the paying asset just clears [paidFromAssetId] (the link is informational). */
@Entity(
    tableName = "subscriptions",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["paidFromAssetId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("paidFromAssetId")],
)
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val amountMinor: Long,
    val currency: String,
    val cycle: String,
    val firstChargeEpochDay: Long,
    val paidFromAssetId: String?,
    val active: Boolean,
)
