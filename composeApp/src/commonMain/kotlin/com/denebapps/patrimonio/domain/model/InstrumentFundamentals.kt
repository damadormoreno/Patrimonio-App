package com.denebapps.patrimonio.domain.model

/** Scale factor for percentages stored as fixed point: `PERCENT_SCALE` == 1 %, so 0.18 % is `1_800`. */
const val PERCENT_SCALE: Long = 10_000L

/** The lowest and the highest risk class of the SRRI (Synthetic Risk and Reward Indicator) scale. */
const val MIN_RISK_LEVEL = 1
const val MAX_RISK_LEVEL = 7

/** The longest category or management company name a holding keeps, after trimming. */
const val MAX_FUNDAMENTALS_TEXT_LENGTH = 120

/**
 * What a data provider says about a product besides its price (today: Morningstar's screener). Every field is
 * optional because providers leave gaps. [ongoingChargePercentScaled] is the yearly ongoing charge (TER) in percent
 * times [PERCENT_SCALE] — never a `Double` — and [riskLevel] the SRRI class, 1 to 7. [category] and
 * [managementCompany] are free text, kept trimmed.
 */
data class InstrumentFundamentals(
    val ongoingChargePercentScaled: Long? = null,
    val riskLevel: Int? = null,
    val category: String? = null,
    val managementCompany: String? = null,
) {
    init {
        require(ongoingChargePercentScaled == null || ongoingChargePercentScaled >= 0) {
            "ongoing charge must not be negative, was $ongoingChargePercentScaled"
        }
        require(riskLevel == null || riskLevel in MIN_RISK_LEVEL..MAX_RISK_LEVEL) {
            "risk level must be between $MIN_RISK_LEVEL and $MAX_RISK_LEVEL, was $riskLevel"
        }
        require(category == null || normalizeFundamentalsText(category) == category) {
            "category must be trimmed, not blank and at most $MAX_FUNDAMENTALS_TEXT_LENGTH characters"
        }
        require(managementCompany == null || normalizeFundamentalsText(managementCompany) == managementCompany) {
            "management company must be trimmed, not blank and at most $MAX_FUNDAMENTALS_TEXT_LENGTH characters"
        }
    }

    /** `true` when the provider had nothing to say. */
    val isEmpty: Boolean
        get() = ongoingChargePercentScaled == null && riskLevel == null && category == null && managementCompany == null
}

/**
 * A provider text as it is stored: trimmed, `null` when nothing is left, cut at [MAX_FUNDAMENTALS_TEXT_LENGTH]
 * characters (and trimmed again, so the cut never leaves a trailing blank).
 */
fun normalizeFundamentalsText(raw: String?): String? =
    raw?.trim()?.take(MAX_FUNDAMENTALS_TEXT_LENGTH)?.trim()?.ifEmpty { null }
