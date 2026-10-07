package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCodecTest {
    private val asset = AssetBackup("a1", "BANK", "Cuenta", null, 150_000, "EUR")
    private val usdAsset = AssetBackup("a2", "INVEST", "Broker", "IBKR", 9_900, "USD")
    private val goal =
        SavingsGoalBackup("goal-1", "Viaje", 300_000, "EUR", null, listOf("a1", "a2"), "OPEN", createdAtEpochMs = 1)
    private val groupGoal =
        SavingsGoalBackup("goal-2", "Colchón", 100_000, "EUR", null, emptyList(), "OPEN", "g1", createdAtEpochMs = 2)

    private val full = BackupDocument(
        exportedAt = "2026-10-06T10:00:00Z",
        assets = listOf(asset, usdAsset),
        liabilities = listOf(LiabilityBackup("l1", "MORTGAGE", "Hipoteca", null, 10_000_000, "EUR")),
        accountGroups = listOf(AccountGroupBackup("g1", "Día a día", showBalance = true, sortOrder = 0)),
        accountGroupMembers = listOf(AccountGroupMemberBackup("g1", "a1")),
        netWorthSnapshots = listOf(NetWorthSnapshotBackup("2026-09", 150_000, 10_000_000)),
        savingsGoals = listOf(goal, groupGoal),
        savingsGoalAllocationEvents = listOf(
            SavingsGoalAllocationEventBackup(1, "goal-1", 50_000, 1_000),
            SavingsGoalAllocationEventBackup(2, "goal-1", -20_000, 2_000),
        ),
        savingsGoalLinkEvents = listOf(
            SavingsGoalLinkEventBackup(1, "goal-1", null, "a1", "LINK", 1_000),
            SavingsGoalLinkEventBackup(2, "goal-2", null, null, "LINK", 1_000, fromGroupId = null, toGroupId = "g1"),
            SavingsGoalLinkEventBackup(
                3,
                "goal-2",
                null,
                null,
                "GROUP_DELETED",
                2_000,
                fromGroupId = "g1",
                toGroupId = null,
            ),
        ),
        subscriptions = listOf(
            SubscriptionBackup("s1", "Netflix", 1_299, "EUR", "MONTHLY", 20_484, paidFromAssetId = "a1"),
            SubscriptionBackup("s2", "iCloud", 9_900, "USD", "YEARLY", 20_200, active = false),
        ),
    )

    private val subscription = full.subscriptions.first()

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
        assertTrue(""""version": 5""" in json, json)
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
        assertEquals(listOf("a1"), document.savingsGoals.single().linkedAssetIds)
        assertNull(document.savingsGoals.single().linkedGroupId)
        assertNull(document.savingsGoalLinkEvents.single().fromGroupId)
        assertNull(document.savingsGoalLinkEvents.single().toGroupId)
    }

    @Test
    fun `numeric goal ids from older backups become UUIDs and their events follow`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 3,
              "exportedAt": "2026-10-06T10:00:00Z",
              "savingsGoals": [
                {"id": 12, "name": "Coche", "targetMinor": 900000, "currency": "EUR", "lifecycle": "OPEN"},
                {"id": 7, "name": "Viaje", "targetMinor": 300000, "currency": "EUR", "lifecycle": "OPEN"}
              ],
              "savingsGoalAllocationEvents": [
                {"id": 3, "goalId": 7, "deltaMinor": 50000, "timestampEpochMs": 1000},
                {"id": 4, "goalId": 12, "deltaMinor": 10000, "timestampEpochMs": 2000}
              ],
              "savingsGoalLinkEvents": [
                {"id": 5, "goalId": 7, "kind": "UNLINK", "timestampEpochMs": 1000}
              ]
            }
            """.trimIndent(),
        )

        val (coche, viaje) = document.savingsGoals
        assertTrue(UUID.matches(coche.id), coche.id)
        assertTrue(UUID.matches(viaje.id), viaje.id)
        assertNotEquals(coche.id, viaje.id)
        // The old id keeps the creation order, as in the database migration.
        assertEquals(12, coche.createdAtEpochMs)
        assertEquals(7, viaje.createdAtEpochMs)
        assertEquals(listOf(viaje.id, coche.id), document.savingsGoalAllocationEvents.map { it.goalId })
        assertEquals(viaje.id, document.savingsGoalLinkEvents.single().goalId)
    }

    @Test
    fun `an older backup event pointing at a missing goal is still rejected`() {
        val error = assertFailsWith<InvalidBackupException> {
            BackupCodec.decode(
                """
                {"format": "patrimonio-backup", "version": 3, "exportedAt": "x",
                 "savingsGoalAllocationEvents": [{"id": 1, "goalId": 99, "deltaMinor": 1, "timestampEpochMs": 1}]}
                """.trimIndent(),
            )
        }

        assertTrue("99" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a version 2 backup keeps the group link on goals and link events`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full))

        assertEquals("g1", decoded.savingsGoals.single { it.id == "goal-2" }.linkedGroupId)
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
            BackupCodec.decode("""{"format":"patrimonio-backup","version":6,"exportedAt":"x"}""")
        }

        assertTrue("v6" in error.message.orEmpty())
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
        reasonFor(full.copy(savingsGoals = listOf(goal.copy(linkedAssetIds = listOf("a1", "missing")))))
        assertTrue("grupo" in reasonFor(full.copy(savingsGoals = listOf(groupGoal.copy(linkedGroupId = "missing")))))
        reasonFor(full.copy(savingsGoalAllocationEvents = listOf(SavingsGoalAllocationEventBackup(9, "missing", 1, 1))))
    }

    @Test
    fun `a goal repeating a linked asset is rejected`() {
        val repeated = goal.copy(linkedAssetIds = listOf("a1", "a1"))

        assertTrue("repite" in reasonFor(full.copy(savingsGoals = listOf(repeated))))
    }

    @Test
    fun `a version 4 backup turns its single linked asset into a list`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 4,
              "exportedAt": "2026-10-06T10:00:00Z",
              "assets": [{"id": "a1", "group": "BANK", "name": "Cuenta", "amountMinor": 150000, "currency": "EUR"}],
              "savingsGoals": [
                {"id": "u1", "name": "Viaje", "targetMinor": 300000, "currency": "EUR",
                 "linkedAssetId": "a1", "lifecycle": "OPEN", "createdAtEpochMs": 1},
                {"id": "u2", "name": "Libre", "targetMinor": 100, "currency": "EUR",
                 "linkedAssetId": null, "lifecycle": "OPEN", "createdAtEpochMs": 2}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(listOf("a1"), emptyList()), document.savingsGoals.map { it.linkedAssetIds })
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
        reasonFor(full.copy(savingsGoalAllocationEvents = listOf(SavingsGoalAllocationEventBackup(1, "goal-1", 0, 1))))
        // Replayed in (timestamp, id) order: the withdrawal happens first and would go negative.
        val negative = listOf(
            SavingsGoalAllocationEventBackup(1, "goal-1", 50_000, 2_000),
            SavingsGoalAllocationEventBackup(2, "goal-1", -20_000, 1_000),
        )
        assertTrue("negativo" in reasonFor(full.copy(savingsGoalAllocationEvents = negative)))
    }

    @Test
    fun `a version 2 backup decodes with no subscriptions`() {
        val document = BackupCodec.decode("""{"format":"patrimonio-backup","version":2,"exportedAt":"x"}""")

        assertEquals(2, document.version)
        assertEquals(emptyList(), document.subscriptions)
    }

    @Test
    fun `invalid subscriptions are rejected`() {
        fun reasonForSubscription(changed: SubscriptionBackup) = reasonFor(full.copy(subscriptions = listOf(changed)))

        assertTrue("periodicidad" in reasonForSubscription(subscription.copy(cycle = "DAILY")))
        assertTrue("divisa" in reasonForSubscription(subscription.copy(currency = "CHF")))
        assertTrue("importe" in reasonForSubscription(subscription.copy(amountMinor = 0)))
        assertTrue("nombre" in reasonForSubscription(subscription.copy(name = "Netflix ")))
        assertTrue("activo" in reasonForSubscription(subscription.copy(paidFromAssetId = "missing")))
        assertTrue("duplicad" in reasonFor(full.copy(subscriptions = listOf(subscription, subscription))))
    }

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
