package com.denebapps.patrimonio.ui.screens.portfolio

// What the portfolio detail screen shows, every text ready to show.

/** The title of the detail of every portfolio together; the detail of one is titled with its name. */
internal const val ALL_PORTFOLIOS_TITLE = "Tu cartera"

/**
 * The top of the screen: the [title], the total in EUR, how many products there are and how many are quoted or manual.
 */
data class PortfolioHeaderUi(
    val title: String = ALL_PORTFOLIOS_TITLE,
    val totalText: String = "",
    val productsText: String = "",
    val mixText: String? = null,
)

/**
 * The value of today's holdings at past prices. [subtitle] says what the line is, [excludedText] which holdings could
 * not be part of it (null when all are), and [ranges]/[chart] are drawn like the price chart of a position.
 * [yearReturns] and [risk] are those of the same series.
 */
data class EvolutionUi(
    val subtitle: String,
    val excludedText: String?,
    val ranges: List<RangeChipUi>,
    val chart: ChartUi,
    val yearReturns: List<YearReturnUi>,
    val risk: RiskUi,
)

/**
 * One cell of the matrix: [valueText] is printed in it ("0,85", "—" when there is no correlation), [value] picks its
 * color (null when there is none), and [detailText] is what tapping it says (null for the diagonal, which is just
 * the product with itself).
 */
data class CorrelationCellUi(val valueText: String, val value: Double?, val detailText: String?)

/**
 * The correlation matrix. [names] are the full names in row (and column) order, [labels] their short axis labels, so
 * the legend maps one to the other. [mostSimilarText] names the closest pair, [explanation] says how to read it.
 */
data class CorrelationUi(
    val labels: List<String>,
    val names: List<String>,
    val cells: List<List<CorrelationCellUi>>,
    val mostSimilarText: String?,
    val explanation: String,
)

/**
 * The correlation section: the [matrix] when at least two products qualify, otherwise the [message] that says what is
 * missing. [omittedText] names the quoted products that had too little history to be in the matrix.
 */
data class CorrelationSectionUi(
    val matrix: CorrelationUi? = null,
    val message: String? = null,
    val omittedText: String? = null,
)

/** A bucket of a split: its [label], [amountText] in euros, [shareText] and the length of its bar [share] (0 to 1). */
data class AllocationRowUi(val label: String, val amountText: String, val shareText: String, val share: Float)

/** A way of splitting the portfolio ("Por tipo") and its buckets, largest first. */
data class AllocationGroupUi(val title: String, val rows: List<AllocationRowUi>)

/**
 * The average ongoing charge ([terText] and what it costs a year, [yearlyCostText]) and the average risk class
 * ([riskText]); either pair is null when no product has the data, and a coverage note says when only some do.
 */
data class CostsRiskUi(
    val terText: String?,
    val yearlyCostText: String?,
    val terCoverage: String?,
    val riskText: String?,
    val riskCoverage: String?,
)

/** The largest holding: its [name] and [weightText], and a [warning] when it is a concentration risk. */
data class ConcentrationUi(val name: String, val weightText: String, val warning: String?)

/**
 * The whole portfolio detail screen. [historyStatus] says what the chart area holds (the sections that come from the
 * history are null unless it is [HistoryStatus.AVAILABLE]); [isSyncing] is a download in flight and [syncError] a
 * failed one while cached data is shown.
 */
data class PortfolioDetailUiState(
    val loaded: Boolean = false,
    val header: PortfolioHeaderUi = PortfolioHeaderUi(),
    val historyStatus: HistoryStatus = HistoryStatus.NOT_APPLICABLE,
    val evolution: EvolutionUi? = null,
    val correlation: CorrelationSectionUi? = null,
    val allocations: List<AllocationGroupUi> = emptyList(),
    val costsRisk: CostsRiskUi? = null,
    val concentration: ConcentrationUi? = null,
    val note: String? = null,
    val isSyncing: Boolean = false,
    val syncError: Boolean = false,
)
