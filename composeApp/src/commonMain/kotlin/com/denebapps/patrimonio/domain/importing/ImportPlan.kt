package com.denebapps.patrimonio.domain.importing

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.MovementDraft

/** The note every imported purchase carries. */
const val MYINVESTOR_IMPORT_NOTE = "MyInvestor"

/**
 * What importing a file into one holding would record: a purchase per open lot ([buys], oldest first), the
 * [totalUnits] they add up to and the [summary] of the file. The [holdingId] is the id the purchases are for; a fund
 * that does not exist yet needs its id chosen before the plan is made.
 */
data class ImportPlan(
    val holdingId: String,
    val totalUnits: Units,
    val buys: List<MovementDraft>,
    val summary: ImportSummary,
)

/**
 * Plans the import of [file] into holding [holdingId], which holds [totalUnits] in total: one BUY per open lot, dated on
 * its fiscal date, for its cost, with no fee and the note [MYINVESTOR_IMPORT_NOTE], and the units split by
 * [splitUnits]. The purchases are ordered by date, lots of the same day as they come in the file. Always for a holding
 * in EUR: the amounts are EUR and the caller refuses any other currency before planning.
 *
 * @throws IllegalArgumentException when [holdingId] is blank.
 */
fun planImport(file: MyInvestorFile, totalUnits: Units, holdingId: String): ImportResult<ImportPlan, UnitSplitError> {
    require(holdingId.isNotBlank()) { "holding id must not be blank" }
    val lots = file.lots.sortedBy { it.date }
    val units = when (val split = splitUnits(lots, totalUnits)) {
        is ImportResult.Err -> return split
        is ImportResult.Ok -> split.value
    }
    val buys = lots.zip(units) { lot, lotUnits ->
        MovementDraft(
            type = MovementType.BUY,
            date = lot.date,
            units = lotUnits,
            amount = CurrencyAmount(lot.cost, Currency.EUR),
            fee = Money.ZERO,
            amountEur = lot.cost,
            feeEur = Money.ZERO,
            note = MYINVESTOR_IMPORT_NOTE,
        )
    }
    return ImportResult.Ok(ImportPlan(holdingId, totalUnits, buys, file.summary))
}
