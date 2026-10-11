package com.denebapps.patrimonio.domain.model

import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetClassTest {
    private fun infer(category: String?, type: InstrumentType = InstrumentType.FUND) = inferAssetClass(category, type)

    private fun assertCategories(expected: AssetClass, vararg categories: String) {
        categories.forEach { category ->
            assertEquals(expected, infer(category), "category '$category'")
        }
    }

    // Every name below was returned by the Morningstar screener (languageId=es-ES) on 2026-10-11.

    @Test
    fun `equity categories start with RV`() = assertCategories(
        AssetClass.EQUITY,
        "RV Global Emergente",
        "RV Europa Cap. Grande Growth",
        "RV USA Cap. Grande Blend",
        "RV Asia (ex-Japón)",
        "RV Sector Tecnología",
        "RV Sector Capital Riesgo",
        "RV Capital Privado",
        "RV Long/Short Global",
        "RV Sector Recursos Naturales",
        "RV Otros",
        "RV Global  Cap. Flexible",
    )

    @Test
    fun `fixed income categories start with RF or are bonds and debt`() = assertCategories(
        AssetClass.FIXED_INCOME,
        "RF Otros",
        "RF Bonos Alto Rendimiento Global - USD Cubierto",
        "RF Deuda Corporativa EUR",
        "RF Deuda Pública Corto Plazo EUR",
        "RF Ultra Corto Plazo EUR",
        "RF Convertibles Europa",
        "RF Flexible EUR",
        "RF Global Bonos Ligados Inflación",
        "EUR Deuda Subordinada",
        "Bonos Largo Plazo Pesos Chilenos",
    )

    @Test
    fun `money market categories`() = assertCategories(
        AssetClass.MONEY_MARKET,
        "Mercado Monetario EUR",
        "Mercado Monetario Corto Plazo EUR",
        "Mercado Monetario USD",
        "Mercado Monetario Otros",
    )

    @Test
    fun `mixed categories include target date funds`() = assertCategories(
        AssetClass.MIXED,
        "Mixtos Moderados EUR - Global",
        "Mixtos Defensivos EUR",
        "Mixtos Agresivos  EUR",
        "Mixtos Flexibles USD",
        "Mixtos GBP 40-60% Renta Variable",
        "Mixtos Global Emergentes ",
        "Mixtos Otros",
        "Fecha Objetivo 2021-2025",
    )

    @Test
    fun `commodity categories include precious metals even when prefixed RV`() = assertCategories(
        AssetClass.COMMODITIES,
        "Materias Primas - Diversificado",
        "Materias Primas - Metales Preciosos",
        "Materias Primas - Sector Energético",
        "RV Sector Oro y Metales preciosos",
        "Commodities Focused",
        "Commodities & Precious Metals",
        "Equity Precious Metals",
    )

    @Test
    fun `real estate categories`() = assertCategories(
        AssetClass.REAL_ESTATE,
        "Inmobiliario - Indirecto Global",
        "Inmobiliario - Indirecto Europa",
        "Inmobiliario - Indirecto Otros",
    )

    @Test
    fun `digital assets are crypto`() = assertCategories(AssetClass.CRYPTO, "Activos Digitales")

    @Test
    fun `alternatives, leveraged trading, guaranteed funds and others are other`() = assertCategories(
        AssetClass.OTHER,
        "Alternativo Event Driven",
        "Alternativo Multiestrategia EUR",
        "Alternativo Futuros Gestionados EUR",
        "Trading - Apalancados/Inversos RV",
        "Trading - Apalancados/Inversos Materias Primas",
        "Garantizados",
        "Capital Protegido",
        "Otros",
    )

    @Test
    fun `an English natural resources category is equity`() {
        assertEquals(AssetClass.EQUITY, infer("Natural Resources"))
    }

    @Test
    fun `matching ignores case accents and surrounding or repeated blanks`() {
        assertEquals(AssetClass.EQUITY, infer("rv sector tecnologia"))
        assertEquals(AssetClass.FIXED_INCOME, infer("  RF   DEUDA PÚBLICA EUR "))
        assertEquals(AssetClass.COMMODITIES, infer("materias primas - sector energetico"))
        assertEquals(AssetClass.MONEY_MARKET, infer("MERCADO MONETARIO EUR"))
        assertEquals(AssetClass.REAL_ESTATE, infer("inmobiliario - indirecto global"))
    }

    @Test
    fun `an unknown or blank category infers nothing for a fund or an ETF`() {
        assertNull(infer(null))
        assertNull(infer(""))
        assertNull(infer("   "))
        assertNull(infer("Categoría desconocida"))
        assertNull(infer("RVX Algo"))
        assertNull(infer(null, InstrumentType.ETF))
        assertNull(infer("Categoría desconocida", InstrumentType.OTHER))
    }

    @Test
    fun `a stock is equity without any category`() {
        assertEquals(AssetClass.EQUITY, infer(null, InstrumentType.STOCK))
        assertEquals(AssetClass.EQUITY, infer("Categoría desconocida", InstrumentType.STOCK))
    }

    @Test
    fun `the category decides for funds and ETFs whatever the type`() {
        assertEquals(AssetClass.FIXED_INCOME, infer("RF Otros", InstrumentType.ETF))
        assertEquals(AssetClass.COMMODITIES, infer("Materias Primas - Diversificado", InstrumentType.ETF))
        assertEquals(AssetClass.MONEY_MARKET, infer("Mercado Monetario EUR", InstrumentType.OTHER))
    }

    // ---- effective class ----------------------------------------------------------------------------------------

    private fun holding(
        assetClass: AssetClass? = null,
        category: String? = null,
        type: InstrumentType = InstrumentType.FUND,
    ) = PortfolioHolding(
        id = "h1",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Holding",
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(100), Currency.EUR), valuedAtEpochMs = 1L),
        fundamentals = category?.let { InstrumentFundamentals(category = it) },
        assetClass = assetClass,
    )

    @Test
    fun `a holding has no explicit class unless one is given`() {
        assertNull(holding().assetClass)
    }

    @Test
    fun `the explicit class wins over the inferred one`() {
        val chosen = holding(assetClass = AssetClass.OTHER, category = "RV Global Emergente")

        assertEquals(AssetClass.OTHER, chosen.effectiveAssetClass())
        assertEquals(AssetClass.OTHER, chosen.assetClass)
    }

    @Test
    fun `without an explicit class the category of the fundamentals is inferred`() {
        assertEquals(AssetClass.FIXED_INCOME, holding(category = "RF Otros").effectiveAssetClass())
        assertNull(holding(category = "RF Otros").assetClass)
    }

    @Test
    fun `a stock without fundamentals is inferred as equity and a fund without them is unclassified`() {
        assertEquals(AssetClass.EQUITY, holding(type = InstrumentType.STOCK).effectiveAssetClass())
        assertNull(holding().effectiveAssetClass())
        assertNull(holding(category = "Categoría desconocida").effectiveAssetClass())
    }

    @Test
    fun `an explicit class applies even when nothing could be inferred`() {
        assertEquals(AssetClass.CRYPTO, holding(assetClass = AssetClass.CRYPTO).effectiveAssetClass())
    }
}
