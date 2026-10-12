package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Version 15 of the backup format: the portfolio holdings that are members of account groups. */
class BackupCodecHoldingMembersTest {
    private val fund = PortfolioHoldingBackup(
        id = "p1",
        portfolioId = "pf-1",
        name = "Vanguard Global Stock",
        type = "FUND",
        kind = "QUOTED",
        provider = "MORNINGSTAR",
        providerCode = "0P00012PP6",
        currency = "EUR",
        unitsScaled = 8_100_001,
    )
    private val painting = PortfolioHoldingBackup(
        id = "p2",
        portfolioId = "pf-1",
        name = "Cuadro",
        type = "OTHER",
        kind = "MANUAL",
        currency = "EUR",
        valueMinor = 250_000,
        valuedAtEpochMs = 7,
    )

    private val document = BackupDocument(
        exportedAt = "2026-10-12T10:00:00Z",
        assets = listOf(AssetBackup("a1", "BANK", "Cuenta", null, 150_000, "EUR")),
        accountGroups = listOf(
            AccountGroupBackup("g1", "Casa", showBalance = true, sortOrder = 0),
            AccountGroupBackup("g2", "Viaje", showBalance = true, sortOrder = 1),
        ),
        accountGroupMembers = listOf(AccountGroupMemberBackup("g1", "a1")),
        portfolios = listOf(PortfolioBackup("pf-1", "Mi cartera", 0)),
        portfolioHoldings = listOf(fund, painting),
        accountGroupHoldingMembers = listOf(
            AccountGroupHoldingMemberBackup("g1", "p1"),
            AccountGroupHoldingMemberBackup("g1", "p2"),
            AccountGroupHoldingMemberBackup("g2", "p1"),
        ),
    )

    private fun reasonFor(doc: BackupDocument): String =
        assertFailsWith<InvalidBackupException> { BackupCodec.decode(BackupCodec.encode(doc)) }.message.orEmpty()

    @Test
    fun `the holding members survive a round trip next to the account members`() {
        val json = BackupCodec.encode(document)
        val decoded = BackupCodec.decode(json)

        assertEquals(document, decoded)
        assertEquals(BackupCodec.VERSION, decoded.version)
        assertTrue(""""accountGroupHoldingMembers"""" in json, json)
        assertEquals(listOf(AccountGroupMemberBackup("g1", "a1")), decoded.accountGroupMembers)
    }

    @Test
    fun `a version 14 file without holding members imports with none`() {
        val v14 = """
            {"format":"patrimonio-backup","version":14,"exportedAt":"2026-10-06T10:00:00Z",
             "assets":[{"id":"a1","group":"BANK","name":"Cuenta","amountMinor":150000,"currency":"EUR"}],
             "accountGroups":[{"id":"g1","name":"Casa","showBalance":true,"sortOrder":0}],
             "accountGroupMembers":[{"groupId":"g1","assetId":"a1"}],
             "portfolios":[{"id":"pf-1","name":"Mi cartera","sortOrder":0}],
             "portfolioHoldings":[
               {"id":"p2","portfolioId":"pf-1","name":"Cuadro","type":"OTHER","kind":"MANUAL","currency":"EUR",
                "valueMinor":250000,"valuedAtEpochMs":7}
             ]}
        """.trimIndent()

        val decoded = BackupCodec.decode(v14)

        assertTrue(decoded.accountGroupHoldingMembers.isEmpty())
        assertEquals(listOf(AccountGroupMemberBackup("g1", "a1")), decoded.accountGroupMembers)
        assertEquals(listOf("p2"), decoded.portfolioHoldings.map { it.id })
    }

    @Test
    fun `a holding member of a group that is not in the file is refused`() {
        val reason = reasonFor(
            document.copy(accountGroupHoldingMembers = listOf(AccountGroupHoldingMemberBackup("ghost", "p1"))),
        )

        assertTrue("ghost" in reason, reason)
    }

    @Test
    fun `a holding member of a holding that is not in the file is refused`() {
        val reason = reasonFor(
            document.copy(accountGroupHoldingMembers = listOf(AccountGroupHoldingMemberBackup("g1", "ghost"))),
        )

        assertTrue("ghost" in reason, reason)
    }

    @Test
    fun `an account id is not a holding id`() {
        val reason = reasonFor(
            document.copy(accountGroupHoldingMembers = listOf(AccountGroupHoldingMemberBackup("g1", "a1"))),
        )

        assertTrue("a1" in reason, reason)
    }

    @Test
    fun `the same holding twice in a group is refused`() {
        val reason = reasonFor(
            document.copy(
                accountGroupHoldingMembers = listOf(
                    AccountGroupHoldingMemberBackup("g1", "p1"),
                    AccountGroupHoldingMemberBackup("g1", "p1"),
                ),
            ),
        )

        assertTrue("duplicado" in reason, reason)
    }

    @Test
    fun `the same holding in two groups is allowed`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(document))

        val groupsOfFund = decoded.accountGroupHoldingMembers.filter { it.holdingId == "p1" }.map { it.groupId }
        assertEquals(setOf("g1", "g2"), groupsOfFund.toSet())
    }
}
