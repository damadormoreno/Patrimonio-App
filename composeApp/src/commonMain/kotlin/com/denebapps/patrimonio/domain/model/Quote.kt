package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/** Latest known [price] of [ref], dated [date] (the provider's last trading/valuation day, UTC). */
data class Quote(val ref: InstrumentRef, val price: Price, val date: LocalDate)
