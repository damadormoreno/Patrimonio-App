package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.Quote

/**
 * Port to a public market-data source. Implementations are swappable (the current ones use unofficial
 * endpoints) and MUST surface every failure as [QuoteUnavailableException] — never transport or
 * serialization exceptions.
 */
interface QuoteSource {
    /** Finds instruments by ISIN, ticker or name. An empty list means "nothing found". */
    suspend fun search(query: String): List<InstrumentCandidate>

    /** Latest price of [ref]. */
    suspend fun latestQuote(ref: InstrumentRef): Quote
}

/** The quote source could not answer: network failure, bad status, malformed payload or unsupported data. */
class QuoteUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
