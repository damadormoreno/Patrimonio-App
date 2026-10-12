package com.denebapps.patrimonio.domain.importing

import com.denebapps.patrimonio.domain.calc.checkedAdd
import com.denebapps.patrimonio.domain.model.Money
import kotlinx.datetime.LocalDate

/**
 * One purchase of a fund that MyInvestor still holds: bought (or transferred in) on [date], the fiscal date, for
 * [cost] (the file's "Inversión"), worth [marketValue] on the day of the export. Both are EUR, non-negative, and not
 * both zero. The file has no units: see [splitUnits].
 */
data class OpenLot(val date: LocalDate, val cost: Money, val marketValue: Money) {
    init {
        require(cost.minorUnits >= 0 && marketValue.minorUnits >= 0) { "lot amounts must not be negative" }
    }
}

/** A lot that was sold: the file only says its [result] (gain or loss, EUR) and its fiscal [date]. */
data class RedeemedLot(val date: LocalDate, val result: Money)

/**
 * What a MyInvestor "Plusvalías y minusvalías" file says about one fund: the [lots] it still has, in file order, the
 * [redeemed] ones, and the fund's [isin] when the file name carries a valid one (the file itself does not).
 */
data class MyInvestorFile(val isin: String?, val lots: List<OpenLot>, val redeemed: List<RedeemedLot>) {
    init {
        require(lots.isNotEmpty()) { "a MyInvestor file has at least one open lot" }
    }

    /** The numbers of the file, for the preview. */
    val summary: ImportSummary
        get() = ImportSummary(
            lotCount = lots.size,
            totalCost = Money(lots.fold(0L) { sum, lot -> checkedAdd(sum, lot.cost.minorUnits) }),
            totalMarketValue = Money(lots.fold(0L) { sum, lot -> checkedAdd(sum, lot.marketValue.minorUnits) }),
            redeemedCount = redeemed.size,
            redeemedResult = Money(redeemed.fold(0L) { sum, lot -> checkedAdd(sum, lot.result.minorUnits) }),
        )
}

/**
 * The numbers shown before importing, all in EUR: the open lots (how many, what they cost, what they are worth) and
 * the redeemed ones, which are not imported (how many, and their fiscal result added up).
 */
data class ImportSummary(
    val lotCount: Int,
    val totalCost: Money,
    val totalMarketValue: Money,
    val redeemedCount: Int,
    val redeemedResult: Money,
)

/** The columns of the file, found in its header by [header] (compared without case or accents). */
enum class MyInvestorColumn(internal val header: String) {
    FISCAL_DATE("fecha fiscal"),
    INVESTMENT("inversion"),
    MARKET_VALUE("valor de mercado"),
    FISCAL_RESULT("resultado fiscal"),
}

/** What is wrong with a cell of a row. */
enum class RowProblem {
    /** The row ends before this column. */
    MISSING_COLUMN,

    /** The cell is not a date. */
    INVALID_DATE,

    /** The cell is not an amount in euros (or has more than two decimals). */
    INVALID_AMOUNT,

    /** The investment or the market value is negative. */
    NEGATIVE_AMOUNT,
}

/** Why a file cannot be read. */
sealed interface MyInvestorReadError {
    /** The file has no header with the four MyInvestor columns: it is not a gains export (or it is empty). */
    data object NotMyInvestorFile : MyInvestorReadError

    /** The file is fine but the fund has no lots left: everything was redeemed (or there are no rows). */
    data object NoOpenLots : MyInvestorReadError

    /** The row on file [line] (1-based, blank lines counted) has a [problem] in [column]. */
    data class MalformedRow(val line: Int, val column: MyInvestorColumn, val problem: RowProblem) :
        MyInvestorReadError
}
