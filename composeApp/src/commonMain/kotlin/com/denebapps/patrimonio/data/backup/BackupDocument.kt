package com.denebapps.patrimonio.data.backup

import kotlinx.serialization.Serializable

/**
 * On-disk backup format, version [BackupCodec.VERSION]. Deliberately decoupled from the Room
 * entities so a schema migration never silently changes what an exported file looks like: a
 * format change bumps [version] and [BackupCodec] decides what it can still read. Version 2 added
 * the savings-goal group link (`linkedGroupId`, `fromGroupId`/`toGroupId`); every such field
 * defaults to null so a version 1 file still decodes. Versions 3 to 6 also carried a `subscriptions`
 * section; version 7 removed it and older files keep decoding because unknown keys are ignored.
 * Version 4 turned savings goal ids into UUID strings and added
 * [SavingsGoalBackup.createdAtEpochMs]; version 5 replaced the single `linkedAssetId` with
 * [SavingsGoalBackup.linkedAssetIds]; version 6 added the user's [accountTypes] and each account's
 * `emoji`, both empty in older files; version 8 added the investment [portfolioHoldings], empty in older files;
 * version 9 added each holding's optional `broker`, null in older files; version 10 added each holding's optional
 * fundamentals (`ongoingChargePercentScaled`, `riskLevel`, `category`, `managementCompany`), null in older files.
 * [BackupCodec] upgrades older files on read.
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
    val accountTypes: List<AccountTypeBackup> = emptyList(),
    val portfolioHoldings: List<PortfolioHoldingBackup> = emptyList(),
)

/** A custom account type; [kind] and [color] are enum names. Accounts point at it from their `group`. */
@Serializable
data class AccountTypeBackup(
    val id: String,
    val kind: String,
    val name: String,
    val emoji: String,
    val color: String,
    val position: Int,
)

@Serializable
data class AssetBackup(
    val id: String,
    val group: String,
    val name: String,
    val subtitle: String? = null,
    val amountMinor: Long,
    val currency: String,
    val emoji: String? = null,
)

@Serializable
data class LiabilityBackup(
    val id: String,
    val group: String,
    val name: String,
    val subtitle: String? = null,
    val amountMinor: Long,
    val currency: String,
    val emoji: String? = null,
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
    val id: String,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val targetDateEpochDay: Long? = null,
    val linkedAssetIds: List<String> = emptyList(),
    val lifecycle: String,
    val linkedGroupId: String? = null,
    val createdAtEpochMs: Long,
)

@Serializable
data class SavingsGoalAllocationEventBackup(
    val id: Long,
    val goalId: String,
    val deltaMinor: Long,
    val timestampEpochMs: Long,
)

@Serializable
data class SavingsGoalLinkEventBackup(
    val id: Long,
    val goalId: String,
    val fromAssetId: String? = null,
    val toAssetId: String? = null,
    val kind: String,
    val timestampEpochMs: Long,
    val fromGroupId: String? = null,
    val toGroupId: String? = null,
)

/**
 * An investment holding. [type] and [kind] are enum names (`InstrumentType`, `QUOTED`/`MANUAL`), [currency] an ISO
 * code. A `QUOTED` holding carries [provider], [providerCode] (a `QuoteProvider` name and its instrument code),
 * [unitsScaled] (units x 1,000,000) and, once priced, [priceScaled] (price in major units x 1,000,000),
 * [priceDateEpochDay] and [priceFetchedAtEpochMs] together; a `MANUAL` one carries [valueMinor] (minor units of
 * [currency]) and [valuedAtEpochMs] instead. [broker] is the optional bank or broker, for either kind. The optional
 * fundamentals, also for either kind: [ongoingChargePercentScaled] (yearly ongoing charge in percent x 10,000),
 * [riskLevel] (SRRI, 1 to 7), [category] and [managementCompany]. The local fundamentals sync stamp is not exported.
 */
@Serializable
data class PortfolioHoldingBackup(
    val id: String,
    val name: String,
    val type: String,
    val isin: String? = null,
    val ticker: String? = null,
    val kind: String,
    val provider: String? = null,
    val providerCode: String? = null,
    val currency: String,
    val unitsScaled: Long? = null,
    val priceScaled: Long? = null,
    val priceDateEpochDay: Long? = null,
    val priceFetchedAtEpochMs: Long? = null,
    val valueMinor: Long? = null,
    val valuedAtEpochMs: Long? = null,
    val broker: String? = null,
    val ongoingChargePercentScaled: Long? = null,
    val riskLevel: Int? = null,
    val category: String? = null,
    val managementCompany: String? = null,
)
