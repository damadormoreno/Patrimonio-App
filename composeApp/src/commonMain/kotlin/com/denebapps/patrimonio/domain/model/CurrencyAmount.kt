package com.denebapps.patrimonio.domain.model

/** A [Money] value tagged with the [Currency] it was recorded in. */
data class CurrencyAmount(val amount: Money, val currency: Currency)
