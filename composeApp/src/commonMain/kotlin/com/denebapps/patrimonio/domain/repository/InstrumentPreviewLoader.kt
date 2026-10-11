package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentPreviewData

/**
 * Downloads what the preview of a search result needs, before the product is part of the portfolio. Nothing is stored
 * in the price history cache: the product's own history only lives in the returned [InstrumentPreviewData] (adding it
 * to the portfolio makes the regular sync download it again). The exchange rates of a foreign currency do go to the
 * currency-wide cache of [FxHistoryRepository], which belongs to no holding.
 */
interface InstrumentPreviewLoader {
    /**
     * The history of [candidate], in EUR too.
     *
     * @throws PreviewUnavailableException when the history cannot be shown, see [PreviewFailure].
     */
    suspend fun load(candidate: InstrumentCandidate): InstrumentPreviewData
}

/** Why a preview has no data. */
enum class PreviewFailure {
    /** The provider answered but has no price history for the product (or no usable one). */
    NO_DATA,

    /** The history or the exchange rates could not be downloaded: network failure, bad status or payload. */
    UNAVAILABLE,
}

/** The preview of a product could not be built; [failure] tells whether retrying can help. */
class PreviewUnavailableException(val failure: PreviewFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)
