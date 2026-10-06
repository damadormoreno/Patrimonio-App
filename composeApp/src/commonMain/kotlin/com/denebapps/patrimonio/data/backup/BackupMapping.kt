package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.entity.SubscriptionEntity

// Entity <-> backup DTO mapping. 1:1 today; this is where a future schema or format change gets
// absorbed so neither side leaks into the other.

internal fun AssetEntity.toBackup() = AssetBackup(id, group, name, subtitle, amountMinor, currency)

internal fun AssetBackup.toEntity() = AssetEntity(id, group, name, subtitle, amountMinor, currency)

internal fun LiabilityEntity.toBackup() = LiabilityBackup(id, group, name, subtitle, amountMinor, currency)

internal fun LiabilityBackup.toEntity() = LiabilityEntity(id, group, name, subtitle, amountMinor, currency)

internal fun AccountGroupEntity.toBackup() = AccountGroupBackup(id, name, showBalance, sortOrder)

internal fun AccountGroupBackup.toEntity() = AccountGroupEntity(id, name, showBalance, sortOrder)

internal fun AccountGroupMemberEntity.toBackup() = AccountGroupMemberBackup(groupId, assetId)

internal fun AccountGroupMemberBackup.toEntity() = AccountGroupMemberEntity(groupId, assetId)

internal fun NetWorthSnapshotEntity.toBackup() = NetWorthSnapshotBackup(yearMonth, assetsMinor, liabsMinor)

internal fun NetWorthSnapshotBackup.toEntity() = NetWorthSnapshotEntity(yearMonth, assetsMinor, liabsMinor)

internal fun SavingsGoalEntity.toBackup() =
    SavingsGoalBackup(id, name, targetMinor, currency, targetDateEpochDay, linkedAssetId, lifecycle, linkedGroupId)

internal fun SavingsGoalBackup.toEntity() =
    SavingsGoalEntity(id, name, targetMinor, currency, targetDateEpochDay, linkedAssetId, lifecycle, linkedGroupId)

internal fun SavingsGoalAllocationEventEntity.toBackup() =
    SavingsGoalAllocationEventBackup(id, goalId, deltaMinor, timestampEpochMs)

internal fun SavingsGoalAllocationEventBackup.toEntity() =
    SavingsGoalAllocationEventEntity(id, goalId, deltaMinor, timestampEpochMs)

internal fun SavingsGoalLinkEventEntity.toBackup() =
    SavingsGoalLinkEventBackup(id, goalId, fromAssetId, toAssetId, kind, timestampEpochMs, fromGroupId, toGroupId)

internal fun SavingsGoalLinkEventBackup.toEntity() =
    SavingsGoalLinkEventEntity(id, goalId, fromAssetId, toAssetId, kind, timestampEpochMs, fromGroupId, toGroupId)

internal fun SubscriptionEntity.toBackup() =
    SubscriptionBackup(id, name, amountMinor, currency, cycle, firstChargeEpochDay, paidFromAssetId, active)

internal fun SubscriptionBackup.toEntity() =
    SubscriptionEntity(id, name, amountMinor, currency, cycle, firstChargeEpochDay, paidFromAssetId, active)
