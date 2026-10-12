package com.denebapps.patrimonio.domain.importing

import com.denebapps.patrimonio.domain.model.Money
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class MyInvestorReaderTest {
    @Test
    fun `reads the open lots, the redeemed ones and the ISIN of a CRLF export`() {
        val file = read(SAMPLE_CRLF).ok()

        assertEquals("IE00BD0NCM55", file.isin)
        assertEquals(
            listOf(
                OpenLot(LocalDate(2023, 3, 15), Money(50_000), Money(56_210)),
                OpenLot(LocalDate(2025, 2, 9), Money(7_500), Money(7_605)),
                OpenLot(LocalDate(2023, 6, 2), Money(123_456), Money(130_140)),
                OpenLot(LocalDate(2023, 6, 2), Money(10_000), Money(10_430)),
                OpenLot(LocalDate(2024, 11, 20), Money(25_050), Money(24_025)),
            ),
            file.lots,
        )
        assertEquals(listOf(RedeemedLot(LocalDate(2022, 1, 10), Money(1_234))), file.redeemed)
    }

    @Test
    fun `reads the same file with LF line ends and blank lines`() {
        val text = SAMPLE_CRLF.replace("\r\n", "\n\n").trimEnd() + "\n\n"

        assertEquals(read(SAMPLE_CRLF).ok().lots, read(text).ok().lots)
    }

    @Test
    fun `a byte order mark in front of the header is ignored`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + SAMPLE_CRLF.encodeToByteArray()

        assertEquals(5, readMyInvestorFile(bytes, FILE_NAME).ok().lots.size)
    }

    @Test
    fun `a file saved as Windows-1252 is read, its accent and euro sign included`() {
        val text = "Fecha fiscal;Inversión;Valor de mercado;Resultado fiscal\r\n" +
            "15/03/2023;500 €;562,10 €;62,10\r\n"

        val file = readMyInvestorFile(cp1252(text), FILE_NAME).ok()

        assertEquals(listOf(OpenLot(LocalDate(2023, 3, 15), Money(50_000), Money(56_210))), file.lots)
    }

    @Test
    fun `a comma separated file with quoted Spanish numbers or with decimal points is read`() {
        val text = "Fecha fiscal,Inversión,Valor de mercado,Resultado fiscal\n" +
            "15/03/2023,\"1.234,56\",\"1.300,10\",\"65,54\"\n" +
            "16/03/2023,100.5,104.25,3.75\n"

        val lots = read(text).ok().lots

        assertEquals(listOf(Money(123_456), Money(10_050)), lots.map { it.cost })
        assertEquals(listOf(Money(130_010), Money(10_425)), lots.map { it.marketValue })
    }

    @Test
    fun `the columns are found by name in any order, ignoring case and accents`() {
        val text = "RESULTADO FISCAL;valor de mercado;Fecha Fiscal;INVERSION\n" +
            "62,10;562,10;15/03/2023;500\n"

        val file = read(text).ok()

        assertEquals(listOf(OpenLot(LocalDate(2023, 3, 15), Money(50_000), Money(56_210))), file.lots)
    }

    @Test
    fun `Spanish numbers with thousands separators, signs, spaces and euro signs are read exactly`() {
        val file = read(
            HEADER + "\n" +
                "01/01/2024;1.234,56;1.300;0\n" +
                "02/01/2024; 12,5 € ;13,00;-0,08\n" +
                "03/01/2024;0;0;-0,08\n" +
                "04/01/2024;0,01;0;0\n",
        ).ok()

        assertEquals(listOf(Money(123_456), Money(1_250), Money(1)), file.lots.map { it.cost })
        assertEquals(listOf(Money(130_000), Money(1_300), Money(0)), file.lots.map { it.marketValue })
        assertEquals(listOf(Money(-8)), file.redeemed.map { it.result })
    }

    @Test
    fun `dates may have one digit for the day or the month and dashes`() {
        val file = read(HEADER + "\n5/3/2023;10;11;1\n05-04-2023;10;11;1\n").ok()

        assertEquals(listOf(LocalDate(2023, 3, 5), LocalDate(2023, 4, 5)), file.lots.map { it.date })
    }

    @Test
    fun `a lot with a market value but no cost, or the other way round, is still open`() {
        val file = read(HEADER + "\n01/01/2024;0;50;50\n02/01/2024;40;0;-40\n").ok()

        assertEquals(2, file.lots.size)
        assertEquals(emptyList(), file.redeemed)
    }

    @Test
    fun `the ISIN comes from the file name and is null when there is none or its check digit fails`() {
        assertEquals("IE00BD0NCM55", read(SAMPLE_CRLF, FILE_NAME).ok().isin)
        assertNull(read(SAMPLE_CRLF, "Plusvalías y minusvalías fondo IE00BD0NCM56.csv").ok().isin)
        assertNull(read(SAMPLE_CRLF, "Plusvalías y minusvalías fondo.csv").ok().isin)
    }

    @Test
    fun `an empty file or one without the MyInvestor header is not a gains file`() {
        assertEquals(MyInvestorReadError.NotMyInvestorFile, read("").err())
        assertEquals(MyInvestorReadError.NotMyInvestorFile, read("\r\n\r\n").err())
        assertEquals(MyInvestorReadError.NotMyInvestorFile, read("Fecha;Importe\n01/01/2024;5\n").err())
        assertEquals(
            MyInvestorReadError.NotMyInvestorFile,
            read("Fecha fiscal;Inversión;Valor de mercado\n01/01/2024;5;6\n").err(),
        )
    }

    @Test
    fun `a file with only redeemed lots or no rows at all has no open lots`() {
        assertEquals(MyInvestorReadError.NoOpenLots, read(HEADER + "\n").err())
        assertEquals(MyInvestorReadError.NoOpenLots, read(HEADER + "\n10/01/2022;0;0;12,34\n").err())
    }

    @Test
    fun `a malformed row is reported with its line number and column`() {
        val badDate = HEADER + "\n01/01/2024;10;11;1\n\n31/02/2024;10;11;1\n"
        assertEquals(
            MyInvestorReadError.MalformedRow(4, MyInvestorColumn.FISCAL_DATE, RowProblem.INVALID_DATE),
            read(badDate).err(),
        )

        val badAmount = HEADER + "\n01/01/2024;diez;11;1\n"
        assertEquals(
            MyInvestorReadError.MalformedRow(2, MyInvestorColumn.INVESTMENT, RowProblem.INVALID_AMOUNT),
            read(badAmount).err(),
        )

        val badResult = HEADER + "\n01/01/2024;10;11;1,234\n"
        assertEquals(
            MyInvestorReadError.MalformedRow(2, MyInvestorColumn.FISCAL_RESULT, RowProblem.INVALID_AMOUNT),
            read(badResult).err(),
        )

        val negative = HEADER + "\n01/01/2024;10;-11;1\n"
        assertEquals(
            MyInvestorReadError.MalformedRow(2, MyInvestorColumn.MARKET_VALUE, RowProblem.NEGATIVE_AMOUNT),
            read(negative).err(),
        )

        val short = HEADER + "\n01/01/2024;10;11\n"
        assertEquals(
            MyInvestorReadError.MalformedRow(2, MyInvestorColumn.FISCAL_RESULT, RowProblem.MISSING_COLUMN),
            read(short).err(),
        )
    }

    private fun read(text: String, fileName: String = FILE_NAME) = readMyInvestorFile(
        text.encodeToByteArray(),
        fileName,
    )

    private fun cp1252(text: String): ByteArray = ByteArray(text.length) { index ->
        when (val char = text[index]) {
            '€' -> 0x80.toByte()
            else -> {
                check(char.code < 256) { "not a Windows-1252 test character: $char" }
                char.code.toByte()
            }
        }
    }

    companion object {
        const val FILE_NAME = "Plusvalías y minusvalías fondo IE00BD0NCM55.csv"
        const val HEADER = "Fecha fiscal;Inversión;Valor de mercado;Resultado fiscal"

        /** Five open lots (two on the same day, one out of date order, one at a loss) and a redeemed one. */
        val SAMPLE_CRLF = listOf(
            HEADER,
            "15/03/2023;500;562,10;62,10",
            "09/02/2025;75;76,05;1,05",
            "02/06/2023;1.234,56;1.301,40;66,84",
            "02/06/2023;100;104,30;4,30",
            "10/01/2022;0;0;12,34",
            "20/11/2024;250,50;240,25;-10,25",
        ).joinToString(separator = "\r\n", postfix = "\r\n")
    }
}

internal fun <T, E> ImportResult<T, E>.ok(): T = when (this) {
    is ImportResult.Ok -> value
    is ImportResult.Err -> fail("expected a success, got $error")
}

internal fun <T, E> ImportResult<T, E>.err(): E = when (this) {
    is ImportResult.Ok -> fail("expected an error, got $value")
    is ImportResult.Err -> error
}
