package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef

/**
 * Port to the fundamentals (cost, risk, category, manager) a data provider publishes about a product. Separate from
 * [QuoteSource] because it is looked up by ISIN, for holdings that already exist. Implementations MUST surface every
 * failure as [QuoteUnavailableException].
 */
interface FundamentalsSource {
    /**
     * The fundamentals of the product with [isin], or `null` when the source knows none. [preferred] is the instrument
     * the holding is quoted as, to pick the right line when the ISIN has several.
     */
    suspend fun fundamentals(isin: String, preferred: InstrumentRef?): InstrumentFundamentals?
}
