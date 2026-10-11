package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuoteMappingTest {
    @Test
    fun `detects ISINs case insensitively after trimming`() {
        assertTrue(looksLikeIsin("IE00B03HCZ61"))
        assertTrue(looksLikeIsin("  ie00b03hcz61 "))
        assertTrue(looksLikeIsin("US0378331005"))
    }

    @Test
    fun `rejects strings that are not ISINs`() {
        assertFalse(looksLikeIsin("IWDA.AS"))
        assertFalse(looksLikeIsin("AAPL"))
        assertFalse(looksLikeIsin("IE00B03HCZ6")) // 11 chars
        assertFalse(looksLikeIsin("IE00B03HCZ6A")) // check digit must be numeric
        assertFalse(looksLikeIsin("1E00B03HCZ61")) // country code must be letters
        assertFalse(looksLikeIsin("vanguard global stock"))
        assertFalse(looksLikeIsin(""))
    }

    @Test
    fun `maps Yahoo quote types and skips out of scope ones`() {
        assertEquals(InstrumentType.STOCK, yahooInstrumentType("EQUITY"))
        assertEquals(InstrumentType.ETF, yahooInstrumentType("ETF"))
        assertEquals(InstrumentType.FUND, yahooInstrumentType("MUTUALFUND"))
        assertNull(yahooInstrumentType("CRYPTOCURRENCY"))
        assertNull(yahooInstrumentType("FUTURE"))
        assertNull(yahooInstrumentType("INDEX"))
        assertNull(yahooInstrumentType(null))
    }

    @Test
    fun `yahooPrice keeps supported currencies and rounds to six decimals`() {
        assertEquals(Price(104_120_000L, Currency.EUR), yahooPrice(104.12, "EUR"))
        assertEquals(Price(189_345_700L, Currency.USD), yahooPrice(189.3457, "USD"))
        assertEquals(Price(17_000_000L, Currency.JPY), yahooPrice(17.0, "JPY"))
    }

    @Test
    fun `yahooPrice drops float noise beyond four decimals of the quoted unit`() {
        // Yahoo serves closes as single-precision floats: 131.67 arrives as 131.669998.
        assertEquals(Price(131_670_000L, Currency.EUR), yahooPrice(131.669998, "EUR"))
        assertEquals(Price(189_345_700L, Currency.USD), yahooPrice(189.34570312, "USD"))
        // Pence keep four decimals of the pence quote, so pounds keep six.
        assertEquals(Price(8_123_457L, Currency.GBP), yahooPrice(812.34567, "GBp"))
    }

    @Test
    fun `yahooPrice converts pence to pounds`() {
        assertEquals(Price(8_123_000L, Currency.GBP), yahooPrice(812.3, "GBp"))
        assertEquals(Price(8_123_000L, Currency.GBP), yahooPrice(812.3, "GBX"))
        assertEquals(Price(812_300_000L, Currency.GBP), yahooPrice(812.3, "GBP"))
    }

    @Test
    fun `yahooPrice filters unsupported currencies and invalid values`() {
        assertNull(yahooPrice(10.0, "CHF"))
        assertNull(yahooPrice(10.0, null))
        assertNull(yahooPrice(Double.NaN, "EUR"))
        assertNull(yahooPrice(-1.0, "EUR"))
        assertNull(yahooPrice(1.0E15, "EUR"))
    }
}
