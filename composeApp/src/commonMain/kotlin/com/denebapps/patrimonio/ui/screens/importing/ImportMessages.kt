package com.denebapps.patrimonio.ui.screens.importing

import com.denebapps.patrimonio.domain.importing.ImportSummary
import com.denebapps.patrimonio.domain.importing.MyInvestorColumn
import com.denebapps.patrimonio.domain.importing.MyInvestorReadError
import com.denebapps.patrimonio.domain.importing.RowProblem
import com.denebapps.patrimonio.domain.importing.UnitSplitError
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.ui.components.formatEur
import kotlin.math.abs

// Pure Spanish copy of the MyInvestor import (neutral es-ES): every text the preview and the results show.

private const val MINUS = "−"

internal const val ONLY_EUR_TEXT = "Solo se pueden importar fondos en euros."
internal const val NO_ISIN_TEXT =
    "No encontramos el ISIN en el nombre del archivo. No le cambies el nombre: lleva el ISIN."
internal const val DUPLICATE_FILE_TEXT = "Ya hay otro archivo de este fondo en la lista."
internal const val UNREADABLE_FILE_TEXT = "No se pudo leer el archivo."
internal const val MANUAL_HOLDING_TEXT = "Este producto se valora a mano y no admite compras."
internal const val SEARCH_FAILED_TEXT = "No se pudo buscar el fondo. Revisa tu conexión e inténtalo de nuevo."
internal const val QUOTE_FAILED_TEXT = "No se pudo obtener el precio del fondo. Inténtalo de nuevo más tarde."
internal const val UNITS_ESTIMATED_NOTE =
    "Estimadas con el último precio; cópialas de MyInvestor para que sean exactas"
internal const val UNITS_OFF_WARNING =
    "Las participaciones no cuadran con el valor de mercado del archivo; revísalas en MyInvestor."
internal const val HAND_TYPED_UNITS_NOTICE = "Sus participaciones pasarán a calcularse con estas compras"
internal const val UNITS_BLANK_TEXT = "Indica las participaciones actuales."
internal const val UNITS_INVALID_TEXT = "Indica un número de participaciones válido."
internal const val UNITS_TOO_BIG_TEXT = "Ese número de participaciones es demasiado grande."
internal const val LOT_WITHOUT_VALUE_TEXT =
    "Un lote del archivo no tiene valor de mercado, así que no se pueden repartir las participaciones."

internal fun notFoundText(isin: String) = "No hemos encontrado este fondo (ISIN $isin) en Morningstar."

/** Why a file could not be read, with the line when a row is at fault. */
internal fun readErrorMessage(error: MyInvestorReadError): String = when (error) {
    MyInvestorReadError.NotMyInvestorFile ->
        "Este archivo no es una exportación de «Plusvalías y minusvalías» de MyInvestor."
    MyInvestorReadError.NoOpenLots ->
        "Este fondo no tiene compras abiertas que importar: todo está reembolsado."
    is MyInvestorReadError.MalformedRow -> {
        val column = "«${columnName(error.column)}»"
        val problem = when (error.problem) {
            RowProblem.MISSING_COLUMN -> "falta el valor de $column"
            RowProblem.INVALID_DATE -> "$column no tiene una fecha válida"
            RowProblem.INVALID_AMOUNT -> "$column no tiene un importe válido"
            RowProblem.NEGATIVE_AMOUNT -> "el importe de $column no puede ser negativo"
        }
        "Línea ${error.line}: $problem."
    }
}

private fun columnName(column: MyInvestorColumn): String = when (column) {
    MyInvestorColumn.FISCAL_DATE -> "Fecha fiscal"
    MyInvestorColumn.INVESTMENT -> "Inversión"
    MyInvestorColumn.MARKET_VALUE -> "Valor de mercado"
    MyInvestorColumn.FISCAL_RESULT -> "Resultado fiscal"
}

/** Why the units typed cannot be shared among the [lotCount] lots of the file. */
internal fun unitSplitMessage(error: UnitSplitError, lotCount: Int): String = when (error) {
    UnitSplitError.TotalTooSmall -> "Son muy pocas participaciones para repartirlas entre $lotCount lotes."
    is UnitSplitError.LotWithoutMarketValue -> LOT_WITHOUT_VALUE_TEXT
}

/** "2 lotes · Invertido 1.500,00 € · Valor de mercado 1.620,00 €". */
internal fun summaryText(summary: ImportSummary): String {
    val lots = if (summary.lotCount == 1) "1 lote" else "${summary.lotCount} lotes"
    return "$lots · Invertido ${formatEur(summary.totalCost)} · Valor de mercado ${formatEur(summary.totalMarketValue)}"
}

/** The warning about the sold lots, which are not imported; `null` when there are none. */
internal fun redeemedWarning(summary: ImportSummary): String? {
    if (summary.redeemedCount == 0) return null
    val count = if (summary.redeemedCount == 1) {
        "1 reembolso no se importa"
    } else {
        "${summary.redeemedCount} reembolsos no se importan"
    }
    return "$count (MyInvestor solo da su plusvalía: ${signedEur(summary.redeemedResult)})"
}

/** "+12,50 €", "−12,50 €" and "0,00 €". */
internal fun signedEur(money: Money): String = when {
    money.minorUnits > 0 -> "+${formatEur(money)}"
    money.minorUnits < 0 -> MINUS + formatEur(Money(abs(money.minorUnits)))
    else -> formatEur(money)
}

/** What replacing the history of a fund throws away, or `null` when there is nothing to tell. */
internal fun replaceNotice(movementCount: Int, hasHandTypedUnits: Boolean): String? = when {
    movementCount == 1 -> "Sustituye el movimiento de este fondo"
    movementCount > 1 -> "Sustituye los $movementCount movimientos de este fondo"
    hasHandTypedUnits -> HAND_TYPED_UNITS_NOTICE
    else -> null
}

/** "Importado: 2 compras". */
internal fun importedText(purchases: Int): String =
    if (purchases == 1) "Importado: 1 compra" else "Importado: $purchases compras"
