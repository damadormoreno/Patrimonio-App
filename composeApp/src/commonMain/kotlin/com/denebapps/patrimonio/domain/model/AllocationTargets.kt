package com.denebapps.patrimonio.domain.model

/**
 * What a portfolio aims for: [targetsBp] is the share of each asset class in basis points (hundredths of a
 * percentage point). Only the classes above zero are in the map and they add up to 10 000; a portfolio with no targets
 * has no [AllocationTargets] at all. [toleranceBp] is the band either side of each target within which the portfolio
 * is on target.
 */
data class AllocationTargets(
    val portfolioId: String,
    val targetsBp: Map<AssetClass, Int>,
    val toleranceBp: Int,
)
