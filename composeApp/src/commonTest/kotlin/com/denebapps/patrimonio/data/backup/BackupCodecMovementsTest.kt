package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Backup format version 13: the movements of the positions, and how they are checked on the way in. */
class BackupCodecMovementsTest {
    private val miCartera = PortfolioBackup("pf-1", "Mi cartera", 0)

    private fun quoted(id: String, type: String = "FUND", currency: String = "EUR", units: Long = 0) =
        PortfolioHoldingBackup(
            id = id,
            portfolioId = "pf-1",
            name = "Producto $id",
            type = type,
            kind = "QUOTED",
            provider = "MORNINGSTAR",
            providerCode = "code-$id",
            currency = currency,
            unitsScaled = units,
        )

    private val fundA = quoted("fa", units = 6_000_000)
    private val fundB = quoted("fb", units = 4_000_000)

    private fun buy(id: String, holding: String = "fa", day: Long = 20_000, units: Long = 10_000_000) = MovementBackup(
        id = id,
        holdingId = holding,
        type = "BUY",
        epochDay = day,
        unitsScaled = units,
        amountMinor = 100_000,
        currency = "EUR",
        feeMinor = 500,
        amountEurMinor = 100_000,
        feeEurMinor = 500,
        sequence = 1,
    )

    private val transferOut = MovementBackup(
        id = "m2",
        holdingId = "fa",
        type = "TRANSFER_OUT",
        epochDay = 20_010,
        unitsScaled = 4_000_000,
        transferId = "t1",
        sequence = 2,
    )
    private val transferIn = transferOut.copy(
        id = "m3",
        holdingId = "fb",
        type = "TRANSFER_IN",
        epochDay = 20_012,
        sequence = 3,
    )

    private val document = BackupDocument(
        exportedAt = "2026-10-06T10:00:00Z",
        portfolios = listOf(miCartera),
        portfolioHoldings = listOf(fundA, fundB),
        movements = listOf(buy("m1"), transferOut, transferIn),
    )

    private fun reasonFor(doc: BackupDocument): String =
        assertFailsWith<InvalidBackupException> { BackupCodec.decode(BackupCodec.encode(doc)) }.message.orEmpty()

    @Test
    fun `movements including a transfer survive a round trip`() {
        assertEquals(document, BackupCodec.decode(BackupCodec.encode(document)))
    }

    @Test
    fun `every kind of movement survives a round trip`() {
        val opening = MovementBackup(
            id = "o1",
            holdingId = "fb",
            type = "OPENING_BALANCE",
            epochDay = 19_000,
            unitsScaled = 1_000_000,
            sequence = 4,
            note = "Saldo",
        )
        val withCost =
            opening.copy(id = "o2", holdingId = "fa", amountMinor = 9_000, currency = "EUR", amountEurMinor = 9_000)
        val dividend = MovementBackup(
            id = "d1",
            holdingId = "fa",
            type = "DIVIDEND",
            epochDay = 20_100,
            amountMinor = 300,
            currency = "EUR",
            amountEurMinor = 300,
            sequence = 5,
        )
        val fee = dividend.copy(id = "f1", type = "FEE")
        val sale = buy("s1", day = 20_200, units = 1_000_000).copy(type = "SELL")
        val all = document.copy(movements = listOf(opening, withCost, dividend, fee, sale))

        assertEquals(all, BackupCodec.decode(BackupCodec.encode(all)))
    }

    @Test
    fun `the document is written with its movements`() {
        val json = BackupCodec.encode(document)

        assertTrue(""""version": ${BackupCodec.VERSION}""" in json, json)
        assertTrue(""""movements"""" in json, json)
    }

    @Test
    fun `a version 12 backup has no movements and its holdings keep their units`() {
        val v12 = """
            {"format": "patrimonio-backup", "version": 12, "exportedAt": "2026-10-06T10:00:00Z",
             "portfolios": [{"id": "pf-1", "name": "Mi cartera", "sortOrder": 0}],
             "portfolioHoldings": [
               {"id": "p1", "portfolioId": "pf-1", "name": "Fondo", "type": "FUND", "kind": "QUOTED",
                "provider": "MORNINGSTAR", "providerCode": "0P00012PP6", "currency": "EUR", "unitsScaled": 8100001}]}
        """.trimIndent()

        val decoded = BackupCodec.decode(v12)

        assertEquals(emptyList(), decoded.movements)
        assertEquals(8_100_001L, decoded.portfolioHoldings.single().unitsScaled)
    }

    @Test
    fun `a newer backup is refused with an update hint`() {
        val json = BackupCodec.encode(
            document,
        ).replace(""""version": ${BackupCodec.VERSION}""", """"version": ${BackupCodec.VERSION + 1}""")

        val reason = assertFailsWith<InvalidBackupException> { BackupCodec.decode(json) }.message.orEmpty()

        assertTrue("más nueva" in reason, reason)
    }

    @Test
    fun `a closed position keeps zero units in the file`() {
        val closed = document.copy(portfolioHoldings = listOf(fundA.copy(unitsScaled = 0), fundB))

        assertEquals(closed, BackupCodec.decode(BackupCodec.encode(closed)))
    }

    @Test
    fun `movements need unique ids and holdings and types that exist`() {
        assertTrue("duplicado" in reasonFor(document.copy(movements = listOf(buy("m1"), buy("m1", day = 20_001)))))
        assertTrue("sin id" in reasonFor(document.copy(movements = listOf(buy(" ")))))
        assertTrue("no existe" in reasonFor(document.copy(movements = listOf(buy("m1", holding = "ghost")))))
        assertTrue("tipo" in reasonFor(document.copy(movements = listOf(buy("m1").copy(type = "GIFT")))))
        assertTrue("fecha" in reasonFor(document.copy(movements = listOf(buy("m1", day = Long.MAX_VALUE)))))
    }

    @Test
    fun `movements belong to quoted holdings only`() {
        val painting = PortfolioHoldingBackup(
            id = "art",
            portfolioId = "pf-1",
            name = "Cuadro",
            type = "OTHER",
            kind = "MANUAL",
            currency = "EUR",
            valueMinor = 250_000,
            valuedAtEpochMs = 1,
        )

        val reason = reasonFor(
            document.copy(portfolioHoldings = listOf(fundA, painting), movements = listOf(buy("m1", holding = "art"))),
        )

        assertTrue("precio de mercado" in reason, reason)
    }

    @Test
    fun `a movement keeps the invariants of its type`() {
        val cases = listOf(
            buy("m1").copy(unitsScaled = null),
            buy("m1").copy(unitsScaled = 0),
            buy("m1").copy(amountMinor = null, currency = null),
            buy("m1").copy(amountEurMinor = null),
            buy("m1").copy(amountMinor = -1),
            buy("m1").copy(feeMinor = -1),
            buy("m1").copy(feeEurMinor = -1),
            buy("m1").copy(transferId = "t9"),
            buy("m1").copy(type = "DIVIDEND"),
            buy("m1").copy(type = "OPENING_BALANCE", amountEurMinor = null),
            buy("m1").copy(type = "OPENING_BALANCE", feeMinor = 5),
            transferOut.copy(transferId = null),
            transferOut.copy(amountMinor = 5, currency = "EUR", amountEurMinor = 5),
        )

        cases.forEach { movement ->
            val reason = reasonFor(document.copy(movements = listOf(buy("m0"), movement.copy(id = "m1"))))
            assertTrue("movimiento" in reason, "$movement -> $reason")
        }
    }

    @Test
    fun `an amount is in the currency of its holding`() {
        assertTrue("divisa" in reasonFor(document.copy(movements = listOf(buy("m1").copy(currency = "USD")))))
        assertTrue("divisa" in reasonFor(document.copy(movements = listOf(buy("m1").copy(currency = "CHF")))))
        assertTrue("divisa" in reasonFor(document.copy(movements = listOf(buy("m1").copy(currency = null)))))
    }

    @Test
    fun `a movement note is trimmed text of a sensible length`() {
        assertTrue("nota" in reasonFor(document.copy(movements = listOf(buy("m1").copy(note = " Plan ")))))
        assertTrue("nota" in reasonFor(document.copy(movements = listOf(buy("m1").copy(note = "  ")))))
        assertTrue("nota" in reasonFor(document.copy(movements = listOf(buy("m1").copy(note = "n".repeat(201))))))
        val atCap = document.copy(movements = listOf(buy("m1").copy(note = "n".repeat(200))))
        assertEquals(atCap, BackupCodec.decode(BackupCodec.encode(atCap)))
    }

    @Test
    fun `a history that sells more than it holds is refused`() {
        val oversell = buy("m2", day = 20_005, units = 11_000_000).copy(type = "SELL", sequence = 2)

        val reason = reasonFor(document.copy(movements = listOf(buy("m1"), oversell)))

        assertTrue("intentas dar salida" in reason, reason)
    }

    @Test
    fun `a dividend before the position started is refused`() {
        val dividend = MovementBackup(
            id = "d1",
            holdingId = "fa",
            type = "DIVIDEND",
            epochDay = 19_000,
            amountMinor = 300,
            currency = "EUR",
            amountEurMinor = 300,
        )

        val reason = reasonFor(document.copy(movements = listOf(buy("m1"), dividend)))

        assertTrue("anterior a la primera compra" in reason, reason)
    }

    @Test
    fun `a transfer needs both legs on two different funds`() {
        val onlyOut = reasonFor(document.copy(movements = listOf(buy("m1"), transferOut)))
        val sameFund = reasonFor(
            document.copy(movements = listOf(buy("m1"), transferOut, transferIn.copy(holdingId = "fa"))),
        )
        val backwards = reasonFor(
            document.copy(movements = listOf(buy("m1"), transferOut, transferIn.copy(epochDay = 20_009))),
        )

        assertTrue("traspaso" in onlyOut, onlyOut)
        assertTrue("traspaso" in sameFund, sameFund)
        assertTrue("traspaso" in backwards, backwards)
    }

    @Test
    fun `a transfer is only between funds`() {
        val etf = quoted("etf", type = "ETF", units = 4_000_000)
        val toEtf = transferIn.copy(holdingId = "etf")

        val reason = reasonFor(
            document.copy(portfolioHoldings = listOf(fundA, etf), movements = listOf(buy("m1"), transferOut, toEtf)),
        )

        assertTrue("fondos" in reason, reason)
    }
}
