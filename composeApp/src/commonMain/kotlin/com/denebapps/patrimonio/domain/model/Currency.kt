package com.denebapps.patrimonio.domain.model

/**
 * Closed set of currencies supported by the app. [decimals] is the number of minor-unit digits
 * (2 for EUR/USD/GBP cents, 0 for JPY).
 */
enum class Currency(val code: String, val decimals: Int) {
    EUR("EUR", 2),
    USD("USD", 2),
    GBP("GBP", 2),
    JPY("JPY", 0),
}
