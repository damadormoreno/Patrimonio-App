package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Version 14 of the backup format: the rebalance tolerance of each portfolio and its allocation targets. */
class BackupCodecAllocationTargetsTest {
    private val miCartera = PortfolioBackup("pf-1", "Mi cartera", 0, rebalanceToleranceBp = 300)
    private val hijos = PortfolioBackup("pf-2", "Hijos", 1)

    private val miCarteraTargets = listOf(
        AllocationTargetBackup("pf-1", "EQUITY", 6_000),
        AllocationTargetBackup("pf-1", "FIXED_INCOME", 4_000),
    )

    private val document = BackupDocument(
        exportedAt = "2026-10-12T10:00:00Z",
        portfolios = listOf(miCartera, hijos),
        allocationTargets = miCarteraTargets + AllocationTargetBackup("pf-2", "EQUITY", 10_000),
    )

    private fun reasonFor(doc: BackupDocument): String =
        assertFailsWith<InvalidBackupException> { BackupCodec.decode(BackupCodec.encode(doc)) }.message.orEmpty()

    @Test
    fun `the targets and the tolerances survive a round trip`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(document))

        assertEquals(document, decoded)
        assertEquals(14, decoded.version)
        assertEquals(listOf(300, 500), decoded.portfolios.map { it.rebalanceToleranceBp })
    }

    @Test
    fun `a version 13 file without targets nor tolerances imports with the default tolerance and no targets`() {
        val v13 = """
            {"format":"patrimonio-backup","version":13,"exportedAt":"2026-10-06T10:00:00Z",
             "portfolios":[{"id":"pf-1","name":"Mi cartera","sortOrder":0}]}
        """.trimIndent()

        val decoded = BackupCodec.decode(v13)

        assertEquals(500, decoded.portfolios.single().rebalanceToleranceBp)
        assertTrue(decoded.allocationTargets.isEmpty())
    }

    @Test
    fun `a version 10 file gets its new portfolio with the default tolerance and no targets`() {
        val v10 = """{"format":"patrimonio-backup","version":10,"exportedAt":"2026-10-06T10:00:00Z"}"""

        val decoded = BackupCodec.decode(v10)

        assertEquals(500, decoded.portfolios.single().rebalanceToleranceBp)
        assertTrue(decoded.allocationTargets.isEmpty())
    }

    @Test
    fun `a file newer than this version is still refused`() {
        val v15 = """{"format":"patrimonio-backup","version":15,"exportedAt":"2026-10-06T10:00:00Z"}"""

        assertFailsWith<InvalidBackupException> { BackupCodec.decode(v15) }
    }

    @Test
    fun `targets of a portfolio that is not in the file are refused`() {
        val reason = reasonFor(
            document.copy(allocationTargets = listOf(AllocationTargetBackup("ghost", "EQUITY", 10_000))),
        )

        assertTrue("ghost" in reason, reason)
    }

    @Test
    fun `an unknown asset class is refused`() {
        val reason = reasonFor(
            document.copy(allocationTargets = listOf(AllocationTargetBackup("pf-1", "BONDS", 10_000))),
        )

        assertTrue("BONDS" in reason, reason)
    }

    @Test
    fun `the same class twice in a portfolio is refused`() {
        val reason = reasonFor(
            document.copy(
                allocationTargets = listOf(
                    AllocationTargetBackup("pf-1", "EQUITY", 5_000),
                    AllocationTargetBackup("pf-1", "EQUITY", 5_000),
                ),
            ),
        )

        assertTrue("duplicado" in reason, reason)
    }

    @Test
    fun `targets of a portfolio that do not add up to 100 percent are refused`() {
        val reason = reasonFor(
            document.copy(
                allocationTargets = listOf(
                    AllocationTargetBackup("pf-1", "EQUITY", 6_000),
                    AllocationTargetBackup("pf-1", "FIXED_INCOME", 3_000),
                ),
            ),
        )

        assertTrue("Mi cartera" in reason, reason)
        assertTrue("90" in reason, reason)
    }

    @Test
    fun `a target outside zero to 100 percent is refused`() {
        val over = reasonFor(
            document.copy(allocationTargets = listOf(AllocationTargetBackup("pf-1", "EQUITY", 12_000))),
        )
        val negative = reasonFor(
            document.copy(
                allocationTargets = listOf(
                    AllocationTargetBackup("pf-1", "EQUITY", 11_000),
                    AllocationTargetBackup("pf-1", "FIXED_INCOME", -1_000),
                ),
            ),
        )

        assertTrue("EQUITY" in over || "Renta variable" in over, over)
        assertTrue(negative.isNotEmpty(), negative)
    }

    @Test
    fun `a stored target of zero is refused because only classes above zero are kept`() {
        val reason = reasonFor(
            document.copy(
                allocationTargets = listOf(
                    AllocationTargetBackup("pf-1", "EQUITY", 10_000),
                    AllocationTargetBackup("pf-1", "CRYPTO", 0),
                ),
            ),
        )

        assertTrue("CRYPTO" in reason, reason)
    }

    @Test
    fun `a tolerance outside zero to 100 percent is refused`() {
        for (bad in listOf(-1, 10_001)) {
            val reason = reasonFor(
                document.copy(portfolios = listOf(miCartera.copy(rebalanceToleranceBp = bad), hijos)),
            )

            assertTrue("tolerancia" in reason, reason)
        }
    }

    @Test
    fun `the limits of the tolerance are accepted`() {
        val limits = document.copy(
            portfolios = listOf(miCartera.copy(rebalanceToleranceBp = 0), hijos.copy(rebalanceToleranceBp = 10_000)),
        )

        assertEquals(limits, BackupCodec.decode(BackupCodec.encode(limits)))
    }
}
