package com.denebapps.patrimonio.domain.importing

import com.denebapps.patrimonio.domain.model.Money

/**
 * Reads a MyInvestor "Plusvalías y minusvalías" export of one fund: [bytes] of the CSV (UTF-8 or Windows-1252, see
 * [decodeCsvBytes]) downloaded as [fileName], whose name carries the fund's ISIN ([isinFromFileName]).
 *
 * The first line is the header; the columns are found by name, in any order. The separator is `;`, or `,` when the
 * header has no `;`. Blank lines are skipped. A row with an investment or a market value is an open [OpenLot]; a row
 * with neither (both zero) is a [RedeemedLot] holding only its fiscal result.
 */
fun readMyInvestorFile(bytes: ByteArray, fileName: String): ImportResult<MyInvestorFile, MyInvestorReadError> {
    val lines = decodeCsvBytes(bytes).lines().withIndex().filter { it.value.isNotBlank() }
    val header = lines.firstOrNull() ?: return ImportResult.Err(MyInvestorReadError.NotMyInvestorFile)
    val separator = if (';' in header.value) ';' else ','
    val columns = columnIndexes(splitCsvRow(header.value, separator))
        ?: return ImportResult.Err(MyInvestorReadError.NotMyInvestorFile)

    val lots = mutableListOf<OpenLot>()
    val redeemed = mutableListOf<RedeemedLot>()
    for ((index, line) in lines.drop(1)) {
        val cells = splitCsvRow(line, separator)
        if (cells.all { it.isBlank() }) continue
        readRow(index + 1, cells, columns, lots, redeemed)?.let { return ImportResult.Err(it) }
    }
    if (lots.isEmpty()) return ImportResult.Err(MyInvestorReadError.NoOpenLots)
    return ImportResult.Ok(MyInvestorFile(isinFromFileName(fileName), lots, redeemed))
}

/** The index of each column in the header [cells], `null` when one is missing. */
private fun columnIndexes(cells: List<String>): Map<MyInvestorColumn, Int>? {
    val names = cells.map { foldHeader(it) }
    val indexes = MyInvestorColumn.entries.associateWith { column -> names.indexOf(column.header) }
    return indexes.takeIf { found -> found.values.none { it < 0 } }
}

/** A header cell in lower case without accents or surrounding spaces. */
private fun foldHeader(cell: String): String = cell.trim().lowercase().map { ACCENTS[it] ?: it }.joinToString("")

private val ACCENTS = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u')

/** Reads the row of file [line] into [lots] or [redeemed]; the problem of the row, or `null` when it is fine. */
private fun readRow(
    line: Int,
    cells: List<String>,
    columns: Map<MyInvestorColumn, Int>,
    lots: MutableList<OpenLot>,
    redeemed: MutableList<RedeemedLot>,
): MyInvestorReadError.MalformedRow? {
    fun fail(column: MyInvestorColumn, problem: RowProblem) = MyInvestorReadError.MalformedRow(line, column, problem)

    fun cell(column: MyInvestorColumn) = cells.getOrNull(columns.getValue(column))

    MyInvestorColumn.entries.firstOrNull { cell(it) == null }?.let { return fail(it, RowProblem.MISSING_COLUMN) }
    val date = parseSpanishDate(cell(MyInvestorColumn.FISCAL_DATE)!!)
        ?: return fail(MyInvestorColumn.FISCAL_DATE, RowProblem.INVALID_DATE)
    val amounts = AMOUNT_COLUMNS.map { column ->
        parseSignedCents(cell(column)!!) ?: return fail(column, RowProblem.INVALID_AMOUNT)
    }
    val (cost, marketValue, result) = amounts
    if (cost < 0) return fail(MyInvestorColumn.INVESTMENT, RowProblem.NEGATIVE_AMOUNT)
    if (marketValue < 0) return fail(MyInvestorColumn.MARKET_VALUE, RowProblem.NEGATIVE_AMOUNT)

    if (cost > 0 || marketValue > 0) {
        lots += OpenLot(date, Money(cost), Money(marketValue))
    } else {
        redeemed += RedeemedLot(date, Money(result))
    }
    return null
}

private val AMOUNT_COLUMNS =
    listOf(MyInvestorColumn.INVESTMENT, MyInvestorColumn.MARKET_VALUE, MyInvestorColumn.FISCAL_RESULT)
