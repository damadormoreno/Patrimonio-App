package com.denebapps.patrimonio.ui.screens.portfolio

// What the preview of a search result shows, every text ready to show.

/** Whether the preview has what it needs: being downloaded, ready, or the reason there is nothing to show. */
enum class PreviewStatus { LOADING, READY, NO_DATA, UNAVAILABLE }

/**
 * The top of the screen. [identifier] is what identifies the product plus its data source, [exchange] the market when
 * the search knew it. [priceText] and [dateText] are the last price of the history and its day (null until loaded).
 */
data class PreviewHeaderUi(
    val name: String,
    val typeLabel: String,
    val identifier: String,
    val exchange: String?,
    val priceText: String? = null,
    val dateText: String? = null,
)

/** How the verdict banner is colored: [ALERT] for little diversification, [POSITIVE] for plenty. */
enum class FitTone { ALERT, NEUTRAL, POSITIVE }

/**
 * The banner of the fit section: the [title] of the verdict, a sentence ([detail]) that says what it means and a muted
 * [caption] with the correlation and the weeks it was measured over.
 */
data class FitVerdictUi(val title: String, val detail: String, val caption: String, val tone: FitTone)

/** A holding the product moves like: its [name] and [correlationText] ("—" with a [note] when it cannot be told). */
data class SimilarHoldingUi(val name: String, val correlationText: String, val note: String?)

/**
 * The portfolio after adding the amount entered: the [weightText] of the new product, the average cost ([terText])
 * and risk ([riskText]) before and after, with a coverage note each when only some products have the data, and the
 * concentration [warning] when it would fire.
 */
data class ProjectionUi(
    val weightText: String,
    val terText: String?,
    val terCoverage: String?,
    val riskText: String?,
    val riskCoverage: String?,
    val warning: String?,
)

/**
 * "Encaje con tu cartera". [message] says why there is nothing to compare (null when there is a [verdict]); [similar]
 * are the holdings it resembles most. The amount field is shown when [canProject]; [projection] is the result for the
 * amount entered (null without one).
 */
data class FitSectionUi(
    val title: String,
    val message: String?,
    val verdict: FitVerdictUi?,
    val similar: List<SimilarHoldingUi>,
    val canProject: Boolean,
    val projection: ProjectionUi?,
)

/**
 * The whole preview screen. The sections that come from the price history are empty unless [status] is
 * [PreviewStatus.READY]; [fit] is null until the portfolio has been read. [scopePortfolioId] is the portfolio the
 * product is compared with (null for all of them) and the one the add form starts on. [amountText] is the amount as
 * typed, while the projection follows it a moment later.
 */
data class InstrumentPreviewUiState(
    val header: PreviewHeaderUi,
    val status: PreviewStatus = PreviewStatus.LOADING,
    val ranges: List<RangeChipUi> = emptyList(),
    val chart: ChartUi? = null,
    val yearReturns: List<YearReturnUi> = emptyList(),
    val annualized: List<FigureUi> = emptyList(),
    val risk: RiskUi? = null,
    val product: ProductDataUi? = null,
    val fit: FitSectionUi? = null,
    val scopePortfolioId: String? = null,
    val amountText: String = "",
)
