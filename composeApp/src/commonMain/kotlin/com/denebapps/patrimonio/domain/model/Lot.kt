package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/**
 * Units of a position that were acquired together: the FIFO unit of cost basis. [costEur] is the total cost of the
 * lot in EUR, `null` when it is unknown (an opening balance entered without a cost) — never zero. A lot keeps its
 * [acquiredOn] date and cost when it moves to another fund in a transfer.
 */
data class Lot(val acquiredOn: LocalDate, val units: Units, val costEur: Money?)
