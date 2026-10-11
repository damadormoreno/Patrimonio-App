package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/** The [price] of a product on one calendar [date] (the provider's UTC day): one point of its price history. */
data class PricePoint(val date: LocalDate, val price: Price)
