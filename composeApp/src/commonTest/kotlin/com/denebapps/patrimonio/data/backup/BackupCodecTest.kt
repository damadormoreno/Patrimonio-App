package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCodecTest {
    private val asset = AssetBackup("a1", "BANK", "Cuenta", null, 150_000, "EUR")
    private val usdAsset = AssetBackup("a2", "INVEST", "Broker", "IBKR", 9_900, "USD")
    private val goal = SavingsGoalBackup(1, "Viaje", 300_000, "EUR", null, "a1", "OPEN")
    private val groupGoal = SavingsGoalBackup(2, "Colchón", 100_000, "EUR", null, null, "OPEN", linkedGroupId = "g1")

    private val full = BackupDocument(
        exportedAt = "2026-10-06T10:00:00Z",
        assets = listOf(asset, usdAsset),
        liabilities = listOf(LiabilityBackup("l1", "MORTGAGE", "Hipoteca", null, 10_000_000, "EUR")),
        accountGroups = listOf(AccountGroupBackup("g1", "Día a día", showBalance = true, sortOrder = 0)),
        accountGroupMembers = listOf(AccountGroupMemberBackup("g1", "a1")),
        netWorthSnapshots = listOf(NetWorthSnapshotBackup("2026-09", 150_000, 10_000_000)),
        savingsGoals = listOf(goal, groupGoal),
        savingsGoalAllocationEvents = listOf(
            SavingsGoalAllocationEventBackup(1, 1, 50_000, 1_000),
            SavingsGoalAllocationEventBackup(2, 1, -20_000, 2_000),
        ),
        savingsGoalLinkEvents = listOf(
            SavingsGoalLinkEventBackup(1, 1, null, "a1", "LINK", 1_000),
            SavingsGoalLinkEventBackup(2, 2, null, null, "LINK", 1_000, fromGroupId = null, toGroupId = "g1"),
            SavingsGoalLinkEventBackup(3, 2, null, null, "GROUP_DELETED", 2_000, fromGroupId = "g1", toGroupId = null),
        ),
    )

    private fun reasonFor(document: BackupDocument): String =
        assertFailsWith<InvalidBackupException> { BackupCodec.decode(BackupCodec.encode(document)) }.message.orEmpty()

    @Test
    fun `encode then decode round-trips every table`() {
        assertEquals(full, BackupCodec.decode(BackupCodec.encode(full)))
    }

    @Test
    fun `encoded document carries format and version envelope`() {
        val json = BackupCodec.encode(BackupDocument(exportedAt = "2026-10-06T10:00:00Z"))

        assertTrue(""""format": "patrimonio-backup"""" in json, json)
        assertTrue(""""version": 2""" in json, json)
    }

    @Test
    fun `a version 1 backup without group fields still imports with null group links`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 1,
              "exportedAt": "2026-10-06T10:00:00Z",
              "assets": [{"id": "a1", "group": "BANK", "name": "Cuenta", "amountMinor": 150000, "currency": "EUR"}],
              "savingsGoals": [
                {"id": 1, "name": "Viaje", "targetMinor": 300000, "currency": "EUR",
                 "linkedAssetId": "a1", "lifecycle": "OPEN"}
              ],
              "savingsGoalLinkEvents": [
                {"id": 1, "goalId": 1, "toAssetId": "a1", "kind": "LINK", "timestampEpochMs": 1000}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, document.version)
        assertEquals("a1", document.savingsGoals.single().linkedAssetId)
        assertNull(document.savingsGoals.single().linkedGroupId)
        assertNull(document.savingsGoalLinkEvents.single().fromGroupId)
        assertNull(document.savingsGoalLinkEvents.single().toGroupId)
    }

    @Test
    fun `a version 2 backup keeps the group link on goals and link events`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full))

        assertEquals("g1", decoded.savingsGoals.single { it.id == 2L }.linkedGroupId)
        assertEquals("g1", decoded.savingsGoalLinkEvents.single { it.kind == "GROUP_DELETED" }.fromGroupId)
    }

    @Test
    fun `missing tables decode as empty`() {
        val document = BackupCodec.decode("""{"format":"patrimonio-backup","version":1,"exportedAt":"x"}""")

        assertEquals(emptyList(), document.assets)
        assertEquals(emptyList(), document.savingsGoals)
    }

    @Test
    fun `unknown fields are ignored`() {
        val document = BackupCodec.decode(
            """{"format":"patrimonio-backup","version":1,"exportedAt":"x","futureField":true}""",
        )

        assertEquals("x", document.exportedAt)
    }

    @Test
    fun `malformed json is rejected`() {
        assertFailsWith<InvalidBackupException> { BackupCodec.decode("{not json") }
        assertFailsWith<InvalidBackupException> { BackupCodec.decode("[1, 2]") }
    }

    @Test
    fun `foreign json is rejected before decoding the body`() {
        val error = assertFailsWith<InvalidBackupException> { BackupCodec.decode("""{"assets":[]}""") }

        assertEquals("El archivo no es una copia de Patrimonio.", error.message)
    }

    @Test
    fun `newer format version is rejected with an update hint`() {
        val error = assertFailsWith<InvalidBackupException> {
            BackupCodec.decode("""{"format":"patrimonio-backup","version":3,"exportedAt":"x"}""")
        }

        assertTrue("v3" in error.message.orEmpty())
    }

    @Test
    fun `wrongly typed fields are rejected`() {
        assertFailsWith<InvalidBackupException> {
            BackupCodec.decode("""{"format":"patrimonio-backup","version":1,"exportedAt":"x","assets":"nope"}""")
        }
    }

    @Test
    fun `unknown enum names and currencies are rejected`() {
        assertTrue("tipo" in reasonFor(full.copy(assets = listOf(asset.copy(group = "BOAT")))))
        assertTrue("divisa" in reasonFor(full.copy(assets = listOf(asset.copy(currency = "CHF")))))
        assertTrue("estado" in reasonFor(full.copy(savingsGoals = listOf(goal.copy(lifecycle = "PAUSED")))))
        val badKind = full.savingsGoalLinkEvents.map { it.copy(kind = "MOVE") }
        assertTrue("vínculo" in reasonFor(full.copy(savingsGoalLinkEvents = badKind)))
    }

    @Test
    fun `duplicate ids are rejected`() {
        assertTrue("duplicado" in reasonFor(full.copy(assets = listOf(asset, asset))))
        val repeatedMonth = full.netWorthSnapshots + full.netWorthSnapshots
        assertTrue("duplicado" in reasonFor(full.copy(netWorthSnapshots = repeatedMonth)))
    }

    @Test
    fun `dangling references are rejected`() {
        reasonFor(full.copy(accountGroupMembers = listOf(AccountGroupMemberBackup("g1", "missing"))))
        reasonFor(full.copy(accountGroupMembers = listOf(AccountGroupMemberBackup("missing", "a1"))))
        reasonFor(full.copy(savingsGoals = listOf(goal.copy(linkedAssetId = "missing"))))
        assertTrue("grupo" in reasonFor(full.copy(savingsGoals = listOf(groupGoal.copy(linkedGroupId = "missing")))))
        reasonFor(full.copy(savingsGoalAllocationEvents = listOf(SavingsGoalAllocationEventBackup(9, 99, 1, 1))))
    }

    @Test
    fun `a goal linked to both an asset and a group is rejected`() {
        val both = goal.copy(linkedGroupId = "g1")

        assertTrue("a la vez" in reasonFor(full.copy(savingsGoals = listOf(both))))
    }

    @Test
    fun `malformed snapshot month is rejected`() {
        reasonFor(full.copy(netWorthSnapshots = listOf(NetWorthSnapshotBackup("2026-13", 0, 0))))
        reasonFor(full.copy(netWorthSnapshots = listOf(NetWorthSnapshotBackup("sept", 0, 0))))
    }

    @Test
    fun `savings goal ledger rules mirror the domain`() {
        reasonFor(full.copy(savingsGoals = listOf(goal.copy(targetMinor = 0))))
        reasonFor(full.copy(savingsGoals = listOf(goal.copy(name = " Viaje"))))
        reasonFor(full.copy(savingsGoalAllocationEvents = listOf(SavingsGoalAllocationEventBackup(1, 1, 0, 1))))
        // Replayed in (timestamp, id) order: the withdrawal happens first and would go negative.
        val negative = listOf(
            SavingsGoalAllocationEventBackup(1, 1, 50_000, 2_000),
            SavingsGoalAllocationEventBackup(2, 1, -20_000, 1_000),
        )
        assertTrue("negativo" in reasonFor(full.copy(savingsGoalAllocationEvents = negative)))
    }
}
