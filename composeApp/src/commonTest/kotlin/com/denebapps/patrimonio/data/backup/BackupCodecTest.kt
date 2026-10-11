package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private val watches = AccountTypeBackup("t1", "ASSET", "Relojes", "⌚", "GOLD", 0)
    private val watch = AssetBackup("a3", "t1", "Rolex", null, 900_000, "EUR", emoji = "⌚")

    private val miCartera = PortfolioBackup("pf-1", "Mi cartera", 0)
    private val hijos = PortfolioBackup("pf-2", "Hijos", 1)

    private val pricedFund = PortfolioHoldingBackup(
        id = "p1",
        portfolioId = "pf-1",
        name = "Vanguard Global Stock",
        type = "FUND",
        isin = "IE00B03HCZ61",
        kind = "QUOTED",
        provider = "MORNINGSTAR",
        providerCode = "0P00012PP6",
        currency = "EUR",
        unitsScaled = 8_100_001,
        priceScaled = 12_345_678,
        priceDateEpochDay = 20_000,
        priceFetchedAtEpochMs = 1_000,
    )
    private val unpricedEtf = PortfolioHoldingBackup(
        id = "p2",
        portfolioId = "pf-1",
        name = "iShares MSCI World",
        type = "ETF",
        ticker = "IWDA.AS",
        kind = "QUOTED",
        provider = "YAHOO",
        providerCode = "IWDA.AS",
        currency = "EUR",
        unitsScaled = 2_500_000,
    )
    private val painting = PortfolioHoldingBackup(
        id = "p3",
        portfolioId = "pf-1",
        name = "Cuadro",
        type = "OTHER",
        kind = "MANUAL",
        currency = "USD",
        valueMinor = 250_000,
        valuedAtEpochMs = 7,
    )

    private val brokerPainting = painting.copy(id = "p4", portfolioId = "pf-2", broker = "Openbank")

    private val fundamentalsFund = pricedFund.copy(
        id = "p5",
        ongoingChargePercentScaled = 1_800,
        riskLevel = 6,
        category = "RV Global",
        managementCompany = "Vanguard",
    )

    private val classifiedFund = pricedFund.copy(id = "p6", assetClass = "FIXED_INCOME")

    private val full = BackupDocument(
        exportedAt = "2026-10-06T10:00:00Z",
        accountTypes = listOf(watches),
        assets = listOf(asset, usdAsset, watch),
        liabilities = listOf(LiabilityBackup("l1", "MORTGAGE", "Hipoteca", null, 10_000_000, "EUR")),
        accountGroups = listOf(AccountGroupBackup("g1", "Día a día", showBalance = true, sortOrder = 0)),
        accountGroupMembers = listOf(AccountGroupMemberBackup("g1", "a1")),
        netWorthSnapshots = listOf(NetWorthSnapshotBackup("2026-09", 150_000, 10_000_000)),
        savingsGoals = listOf(goal, groupGoal),
        savingsGoalAllocationEvents = listOf(
            SavingsGoalAllocationEventBackup(1, "goal-1", 50_000, 1_000),
            SavingsGoalAllocationEventBackup(2, "goal-1", -20_000, 2_000),
        ),
        portfolios = listOf(miCartera, hijos),
        portfolioHoldings = listOf(pricedFund, unpricedEtf, painting, brokerPainting, fundamentalsFund, classifiedFund),
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
        assertTrue(""""version": 12""" in json, json)
        assertTrue(""""portfolioHoldings"""" in json, json)
        assertTrue(""""portfolios"""" in json, json)
        assertFalse("subscriptions" in json, json)
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
            BackupCodec.decode("""{"format":"patrimonio-backup","version":13,"exportedAt":"x"}""")
        }

        assertTrue("v13" in error.message.orEmpty())
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
    fun `custom types are kept and their accounts must use one of the right kind`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full))
        assertEquals(listOf(watches), decoded.accountTypes)
        assertEquals("⌚", decoded.assets.single { it.id == "a3" }.emoji)

        val liabilityType = watches.copy(kind = "LIABILITY")
        assertTrue("tipo desconocido" in reasonFor(full.copy(accountTypes = listOf(liabilityType))))
        assertTrue("tipo desconocido" in reasonFor(full.copy(accountTypes = emptyList())))
        assertTrue("activos ni de pasivos" in reasonFor(full.copy(accountTypes = listOf(watches.copy(kind = "X")))))
        assertTrue("color" in reasonFor(full.copy(accountTypes = listOf(watches.copy(color = "PINK")))))
        assertTrue("emoji" in reasonFor(full.copy(accountTypes = listOf(watches.copy(emoji = " ")))))
        assertTrue("duplicado" in reasonFor(full.copy(accountTypes = listOf(watches, watches))))
    }

    @Test
    fun `a version 5 backup has no custom types nor emojis and its built-in types still read`() {
        val json = BackupCodec.encode(BackupDocument(exportedAt = "2026-10-06T10:00:00Z", assets = listOf(asset)))
            .replace(""""version": 10""", """"version": 5""")
            .replace(Regex(""",\s*"emoji": null"""), "")
            .replace(Regex(""",\s*"accountTypes": \[\s*]"""), "")

        val decoded = BackupCodec.decode(json)

        assertEquals(listOf(asset), decoded.assets)
        assertEquals(emptyList(), decoded.accountTypes)
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
    fun `a version 6 backup with subscriptions still imports and the section is dropped`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 6,
              "exportedAt": "2026-10-06T10:00:00Z",
              "assets": [{"id": "a1", "group": "BANK", "name": "Cuenta", "amountMinor": 150000, "currency": "EUR"}],
              "subscriptions": [
                {"id": "s1", "name": "Netflix", "amountMinor": 1299, "currency": "EUR", "cycle": "MONTHLY",
                 "firstChargeEpochDay": 20484, "paidFromAssetId": "a1", "active": true},
                {"id": "s2", "name": "Broken", "amountMinor": 0, "currency": "CHF", "cycle": "DAILY",
                 "firstChargeEpochDay": 1, "paidFromAssetId": "missing"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(asset), document.assets)
        assertFalse("subscriptions" in BackupCodec.encode(document))
    }

    private fun holdingReason(holding: PortfolioHoldingBackup): String =
        reasonFor(full.copy(portfolioHoldings = listOf(holding)))

    /** The reason a file with exactly these [portfolios] (and no holding, which would need one) is refused. */
    private fun portfolioReason(vararg portfolios: PortfolioBackup): String =
        reasonFor(full.copy(portfolios = portfolios.toList(), portfolioHoldings = emptyList()))

    private fun roundTrip(holding: PortfolioHoldingBackup): List<PortfolioHoldingBackup> =
        BackupCodec.decode(BackupCodec.encode(full.copy(portfolioHoldings = listOf(holding)))).portfolioHoldings

    @Test
    fun `portfolio holdings of both variants survive a round trip`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full))

        assertEquals(
            listOf(pricedFund, unpricedEtf, painting, brokerPainting, fundamentalsFund, classifiedFund),
            decoded.portfolioHoldings,
        )
    }

    @Test
    fun `a crypto holding quoted by CoinGecko survives a round trip while unknown names are still refused`() {
        val bitcoin = unpricedEtf.copy(
            id = "p7",
            name = "Bitcoin",
            type = "CRYPTO",
            ticker = "BTC",
            provider = "COINGECKO",
            providerCode = "bitcoin",
            assetClass = "CRYPTO",
        )

        assertEquals(listOf(bitcoin), roundTrip(bitcoin))
        assertTrue("tipo" in holdingReason(bitcoin.copy(type = "NFT")))
        assertTrue("proveedor" in holdingReason(bitcoin.copy(provider = "BINANCE")))
    }

    @Test
    fun `a version 7 backup without holdings imports with an empty portfolio`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 7,
              "exportedAt": "2026-10-06T10:00:00Z",
              "assets": [{"id": "a1", "group": "BANK", "name": "Cuenta", "amountMinor": 150000, "currency": "EUR"}]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(asset), document.assets)
        assertEquals(emptyList(), document.portfolioHoldings)
        assertEquals(listOf("Mi cartera"), document.portfolios.map { it.name })
    }

    @Test
    fun `portfolio holdings need a unique id a name and known enum names and currency`() {
        assertTrue("duplicado" in reasonFor(full.copy(portfolioHoldings = listOf(pricedFund, pricedFund))))
        assertTrue("sin id" in holdingReason(pricedFund.copy(id = " ")))
        assertTrue("sin nombre" in holdingReason(pricedFund.copy(name = "")))
        assertTrue("tipo" in holdingReason(pricedFund.copy(type = "BOND")))
        assertTrue("divisa" in holdingReason(pricedFund.copy(currency = "CHF")))
        assertTrue("clase" in holdingReason(pricedFund.copy(kind = "OTHER")))
    }

    @Test
    fun `a quoted holding needs a known provider a code and positive units`() {
        assertTrue("proveedor" in holdingReason(pricedFund.copy(provider = "BING")))
        assertTrue("proveedor" in holdingReason(pricedFund.copy(provider = null)))
        assertTrue("código" in holdingReason(pricedFund.copy(providerCode = " ")))
        assertTrue("código" in holdingReason(pricedFund.copy(providerCode = null)))
        assertTrue("unidades" in holdingReason(pricedFund.copy(unitsScaled = 0)))
        assertTrue("unidades" in holdingReason(pricedFund.copy(unitsScaled = -1)))
        assertTrue("unidades" in holdingReason(pricedFund.copy(unitsScaled = null)))
    }

    @Test
    fun `a quoted holding has its price fields all set or all missing and never negative`() {
        assertTrue("precio" in holdingReason(pricedFund.copy(priceScaled = null)))
        assertTrue("precio" in holdingReason(pricedFund.copy(priceDateEpochDay = null)))
        assertTrue("precio" in holdingReason(pricedFund.copy(priceFetchedAtEpochMs = null)))
        assertTrue("precio" in holdingReason(unpricedEtf.copy(priceFetchedAtEpochMs = 5)))
        assertTrue("precio" in holdingReason(pricedFund.copy(priceScaled = -1)))
        val zeroPrice = pricedFund.copy(priceScaled = 0)
        assertEquals(listOf(zeroPrice), roundTrip(zeroPrice))
    }

    @Test
    fun `a manual holding needs a non negative value and a date and carries no quote data`() {
        assertTrue("valor manual" in holdingReason(painting.copy(valueMinor = null)))
        assertTrue("valor manual" in holdingReason(painting.copy(valueMinor = -1)))
        assertTrue("valor manual" in holdingReason(painting.copy(valuedAtEpochMs = null)))
        assertTrue("mezcla" in holdingReason(painting.copy(unitsScaled = 1)))
        assertTrue("mezcla" in holdingReason(painting.copy(provider = "YAHOO")))
        assertTrue("mezcla" in holdingReason(painting.copy(priceScaled = 1)))
        val zeroValue = painting.copy(valueMinor = 0)
        assertEquals(listOf(zeroValue), roundTrip(zeroValue))
    }

    @Test
    fun `a quoted holding carries no manual value`() {
        assertTrue("mezcla" in holdingReason(pricedFund.copy(valueMinor = 1)))
        assertTrue("mezcla" in holdingReason(pricedFund.copy(valuedAtEpochMs = 1)))
    }

    @Test
    fun `a holding keeps its bank or broker through a round trip and none stays none`() {
        val withBroker = pricedFund.copy(broker = "MyInvestor")

        assertEquals(listOf(withBroker), roundTrip(withBroker))
        assertNull(roundTrip(pricedFund).single().broker)
    }

    @Test
    fun `a version 8 backup with holdings imports them with no broker`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 8,
              "exportedAt": "2026-10-06T10:00:00Z",
              "portfolioHoldings": [
                {"id": "p3", "name": "Cuadro", "type": "OTHER", "kind": "MANUAL", "currency": "USD",
                 "valueMinor": 250000, "valuedAtEpochMs": 7}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(painting.copy(portfolioId = document.portfolios.single().id)), document.portfolioHoldings)
        assertNull(document.portfolioHoldings.single().broker)
    }

    @Test
    fun `a bank or broker must be present text of a sensible length`() {
        assertTrue("banco o bróker" in holdingReason(pricedFund.copy(broker = "")))
        assertTrue("banco o bróker" in holdingReason(pricedFund.copy(broker = "   ")))
        assertTrue("banco o bróker" in holdingReason(pricedFund.copy(broker = " Openbank")))
        assertTrue("banco o bróker" in holdingReason(pricedFund.copy(broker = "B".repeat(61))))
        val atCap = pricedFund.copy(broker = "B".repeat(60))
        assertEquals(listOf(atCap), roundTrip(atCap))
    }

    @Test
    fun `a holding keeps its fundamentals through a round trip and none stays none`() {
        assertEquals(listOf(fundamentalsFund), roundTrip(fundamentalsFund))
        val partial = pricedFund.copy(ongoingChargePercentScaled = 0, category = "Monetario")
        assertEquals(listOf(partial), roundTrip(partial))
        val plain = roundTrip(pricedFund).single()
        assertNull(plain.ongoingChargePercentScaled)
        assertNull(plain.riskLevel)
        assertNull(plain.category)
        assertNull(plain.managementCompany)
    }

    @Test
    fun `a version 9 backup with holdings imports them with no fundamentals`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 9,
              "exportedAt": "2026-10-06T10:00:00Z",
              "portfolioHoldings": [
                {"id": "p3", "name": "Cuadro", "type": "OTHER", "kind": "MANUAL", "currency": "USD",
                 "valueMinor": 250000, "valuedAtEpochMs": 7, "broker": "Openbank"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(painting.copy(portfolioId = document.portfolios.single().id, broker = "Openbank")),
            document.portfolioHoldings,
        )
        assertNull(document.portfolioHoldings.single().riskLevel)
    }

    @Test
    fun `fundamentals must be a plausible cost risk and texts`() {
        assertTrue("gastos" in holdingReason(fundamentalsFund.copy(ongoingChargePercentScaled = -1)))
        assertTrue("riesgo" in holdingReason(fundamentalsFund.copy(riskLevel = 0)))
        assertTrue("riesgo" in holdingReason(fundamentalsFund.copy(riskLevel = 8)))
        listOf("", "  ", " padded", "C".repeat(121)).forEach { text ->
            assertTrue("categoría" in holdingReason(fundamentalsFund.copy(category = text)), text)
            assertTrue("gestora" in holdingReason(fundamentalsFund.copy(managementCompany = text)), text)
        }
        val atCap = fundamentalsFund.copy(category = "C".repeat(120), riskLevel = 7, ongoingChargePercentScaled = 0)
        assertEquals(listOf(atCap), roundTrip(atCap))
    }

    @Test
    fun `a holding keeps its asset class through a round trip and none stays none`() {
        AssetClass.entries.forEach { assetClass ->
            val holding = pricedFund.copy(assetClass = assetClass.name)
            assertEquals(listOf(holding), roundTrip(holding), assetClass.name)
        }
        val manualCrypto = painting.copy(assetClass = "CRYPTO")
        assertEquals(listOf(manualCrypto), roundTrip(manualCrypto))
        assertNull(roundTrip(pricedFund).single().assetClass)
    }

    @Test
    fun `a version 11 backup with holdings imports them with no asset class`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 11,
              "exportedAt": "2026-10-06T10:00:00Z",
              "portfolios": [{"id": "pf-1", "name": "Mi cartera", "sortOrder": 0}],
              "portfolioHoldings": [
                {"id": "p3", "portfolioId": "pf-1", "name": "Cuadro", "type": "OTHER", "kind": "MANUAL",
                 "currency": "USD", "valueMinor": 250000, "valuedAtEpochMs": 7, "category": "Otros"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(painting.copy(category = "Otros")), document.portfolioHoldings)
        assertNull(document.portfolioHoldings.single().assetClass)
    }

    @Test
    fun `an asset class must be a known one`() {
        listOf("", "crypto", "BONDS", " EQUITY").forEach { name ->
            assertTrue("clase de activo" in holdingReason(pricedFund.copy(assetClass = name)), name)
        }
        assertTrue("BONDS" in holdingReason(pricedFund.copy(assetClass = "BONDS")))
        assertTrue("Vanguard Global Stock" in holdingReason(pricedFund.copy(assetClass = "BONDS")))
    }

    @Test
    fun `portfolios survive a round trip in the order they were saved`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(full))

        assertEquals(listOf(miCartera, hijos), decoded.portfolios)
        assertEquals(
            mapOf(
                "p1" to "pf-1",
                "p2" to "pf-1",
                "p3" to "pf-1",
                "p4" to "pf-2",
                "p5" to "pf-1",
                "p6" to "pf-1",
            ),
            decoded.portfolioHoldings.associate { it.id to it.portfolioId },
        )
    }

    @Test
    fun `a version 10 backup imports its holdings into a new Mi cartera`() {
        val document = BackupCodec.decode(
            """
            {
              "format": "patrimonio-backup",
              "version": 10,
              "exportedAt": "2026-10-06T10:00:00Z",
              "portfolioHoldings": [
                {"id": "p3", "name": "Cuadro", "type": "OTHER", "kind": "MANUAL", "currency": "USD",
                 "valueMinor": 250000, "valuedAtEpochMs": 7, "broker": "Openbank", "riskLevel": 3},
                {"id": "p1", "name": "Fondo", "type": "FUND", "kind": "QUOTED", "currency": "EUR",
                 "provider": "MORNINGSTAR", "providerCode": "0P00012PP6", "unitsScaled": 8100001}
              ]
            }
            """.trimIndent(),
        )

        val portfolio = document.portfolios.single()
        assertEquals("Mi cartera", portfolio.name)
        assertEquals(0, portfolio.sortOrder)
        assertTrue(UUID.matches(portfolio.id), portfolio.id)
        assertEquals(listOf("p3", "p1"), document.portfolioHoldings.map { it.id })
        assertEquals(listOf(portfolio.id, portfolio.id), document.portfolioHoldings.map { it.portfolioId })
        assertEquals("Openbank", document.portfolioHoldings.first().broker)
        assertEquals(3, document.portfolioHoldings.first().riskLevel)
    }

    @Test
    fun `every older backup gets a new Mi cartera of its own even without holdings`() {
        val v1 = """{"format":"patrimonio-backup","version":1,"exportedAt":"x"}"""

        val first = BackupCodec.decode(v1).portfolios.single()
        val second = BackupCodec.decode(v1).portfolios.single()

        assertEquals("Mi cartera", first.name)
        assertTrue(UUID.matches(first.id), first.id)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `a version 11 backup keeps exactly the portfolios it has and may have none without holdings`() {
        val withoutPortfolios = BackupCodec.decode(
            """{"format":"patrimonio-backup","version":11,"exportedAt":"x"}""",
        )
        val oneEmpty = BackupCodec.decode(BackupCodec.encode(full.copy(portfolioHoldings = emptyList())))

        assertEquals(emptyList(), withoutPortfolios.portfolios)
        assertEquals(listOf(miCartera, hijos), oneEmpty.portfolios)
    }

    @Test
    fun `a version 11 holding without a portfolio is rejected`() {
        val error = assertFailsWith<InvalidBackupException> {
            BackupCodec.decode(
                """
                {
                  "format": "patrimonio-backup",
                  "version": 11,
                  "exportedAt": "x",
                  "portfolios": [{"id": "pf-1", "name": "Mi cartera", "sortOrder": 0}],
                  "portfolioHoldings": [
                    {"id": "p3", "name": "Cuadro", "type": "OTHER", "kind": "MANUAL", "currency": "USD",
                     "valueMinor": 250000, "valuedAtEpochMs": 7}
                  ]
                }
                """.trimIndent(),
            )
        }

        assertTrue("campos" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a holding must be in a portfolio that is in the file`() {
        assertTrue("cartera que no existe" in holdingReason(pricedFund.copy(portfolioId = "nope")))
        assertTrue("cartera que no existe" in holdingReason(pricedFund.copy(portfolioId = "")))
        assertTrue("cartera que no existe" in reasonFor(full.copy(portfolios = emptyList())))
    }

    @Test
    fun `portfolios need a unique id and a unique trimmed name of a sensible length`() {
        assertTrue("id duplicado" in portfolioReason(miCartera, miCartera.copy(name = "Otra")))
        assertTrue("sin id" in portfolioReason(miCartera.copy(id = " ")))
        assertTrue("mismo nombre" in portfolioReason(miCartera, hijos.copy(name = "MI CARTERA")))
        listOf("", "   ", " Hijos", "Hijos ", "H".repeat(41)).forEach { name ->
            assertTrue("nombre" in portfolioReason(hijos.copy(name = name)), name)
        }
        val atCap = hijos.copy(name = "H".repeat(40))
        assertEquals(
            listOf(miCartera, atCap),
            BackupCodec.decode(BackupCodec.encode(full.copy(portfolios = listOf(miCartera, atCap)))).portfolios,
        )
    }

    @Test
    fun `a price date outside the supported calendar is rejected`() {
        assertTrue("fecha" in holdingReason(pricedFund.copy(priceDateEpochDay = Long.MAX_VALUE)))
    }

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
