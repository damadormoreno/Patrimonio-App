package com.denebapps.patrimonio.data.backup

import kotlinx.serialization.Serializable

/**
 * On-disk backup format, version [BackupCodec.VERSION]. Deliberately decoupled from the Room
 * entities so a schema migration never silently changes what an exported file looks like: a
 * format change bumps [version] and [BackupCodec] decides what it can still read. Version 2 added
 * the savings-goal group link (`linkedGroupId`, `fromGroupId`/`toGroupId`); every such field
 * defaults to null so a version 1 file still decodes. Version 3 added [subscriptions], which defaults
 * to empty for older files.
 *
 * Enum-like fields hold the domain enum `name`s and currencies their ISO code; amounts are minor
 * units, dates epoch days, timestamps epoch milliseconds.
 */
@Serializable
data class BackupDocument(
    val format: String = BackupCodec.FORMAT,
    val version: Int = BackupCodec.VERSION,
    val exportedAt: String,
    val assets: List<AssetBackup> = emptyList(),
    val liabilities: List<LiabilityBackup> = emptyList(),
    val accountGroups: List<AccountGroupBackup> = emptyList(),
    val accountGroupMembers: List<AccountGroupMemberBackup> = emptyList(),
    val netWorthSnapshots: List<NetWorthSnapshotBackup> = emptyList(),
    val savingsGoals: List<SavingsGoalBackup> = emptyList(),
    val savingsGoalAllocationEvents: List<SavingsGoalAllocationEventBackup> = emptyList(),
    val savingsGoalLinkEvents: List<SavingsGoalLinkEventBackup> = emptyList(),
    val subscriptions: List<SubscriptionBackup> = emptyList(),
)

@Serializable
data class AssetBackup(
    val id: String,
    val group: String,
    val name: String,
    val subtitle: String? = null,
    val amountMinor: Long,
    val currency: String,
)

@Serializable
data class LiabilityBackup(
    val id: String,
    val group: String,
    val name: String,
    val subtitle: String? = null,
    val amountMinor: Long,
    val currency: String,
)

@Serializable
data class AccountGroupBackup(
    val id: String,
    val name: String,
    val showBalance: Boolean,
    val sortOrder: Int,
)

@Serializable
data class AccountGroupMemberBackup(
    val groupId: String,
    val assetId: String,
)

@Serializable
data class NetWorthSnapshotBackup(
    val yearMonth: String,
    val assetsMinor: Long,
    val liabsMinor: Long,
)

@Serializable
data class SavingsGoalBackup(
    val id: Long,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val targetDateEpochDay: Long? = null,
    val linkedAssetId: String? = null,
    val lifecycle: String,
    val linkedGroupId: String? = null,
)

@Serializable
data class SavingsGoalAllocationEventBackup(
    val id: Long,
    val goalId: Long,
    val deltaMinor: Long,
    val timestampEpochMs: Long,
)

@Serializable
data class SavingsGoalLinkEventBackup(
    val id: Long,
    val goalId: Long,
    val fromAssetId: String? = null,
    val toAssetId: String? = null,
    val kind: String,
    val timestampEpochMs: Long,
    val fromGroupId: String? = null,
    val toGroupId: String? = null,
)

@Serializable
data class SubscriptionBackup(
    val id: String,
    val name: String,
    val amountMinor: Long,
    val currency: String,
    val cycle: String,
    val firstChargeEpochDay: Long,
    val paidFromAssetId: String? = null,
    val active: Boolean = true,
)
