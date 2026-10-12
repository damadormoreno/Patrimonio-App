package com.denebapps.patrimonio.ui.screens.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.parseUnitsEs
import com.denebapps.patrimonio.domain.calc.unitsInputText
import com.denebapps.patrimonio.domain.importing.ImportPlan
import com.denebapps.patrimonio.domain.importing.ImportResult
import com.denebapps.patrimonio.domain.importing.MyInvestorFile
import com.denebapps.patrimonio.domain.importing.estimateTotalUnits
import com.denebapps.patrimonio.domain.importing.isinFromFileName
import com.denebapps.patrimonio.domain.importing.planImport
import com.denebapps.patrimonio.domain.importing.readMyInvestorFile
import com.denebapps.patrimonio.domain.importing.unitsLookOff
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.QuoteSource
import com.denebapps.patrimonio.ui.screens.portfolio.MIN_PORTFOLIOS_TO_CHOOSE
import com.denebapps.patrimonio.ui.screens.portfolio.PortfolioOptionUi
import com.denebapps.patrimonio.ui.screens.portfolio.defaultPortfolioId
import com.denebapps.patrimonio.ui.screens.portfolio.holdingName
import com.denebapps.patrimonio.ui.screens.portfolio.movementErrorMessage
import com.denebapps.patrimonio.ui.screens.portfolio.newHoldingId
import com.denebapps.patrimonio.ui.screens.portfolio.toOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/** The broker a fund created by the import is given. */
internal const val MYINVESTOR_BROKER = "MyInvestor"

/** A CSV the user picked: its [name] and a way to read it, so that the ViewModel never touches the file picker. */
class PickedFile(val name: String, val readBytes: suspend () -> ByteArray)

/** Where a file is: being read, ready to import, or unusable ([ImportCardUi.errorText] says why). */
enum class ImportCardStatus { LOADING, READY, ERROR }

/** What importing a card came to: [success] and the [text] to show. */
data class ImportCardResultUi(val success: Boolean, val text: String)

/**
 * One file of the list, every text ready to show. A `READY` card has a [title], the [subtitle] (the portfolio the fund
 * is in or goes to), the [summaryText] of the file, a [notice] about what the import replaces, [warnings], and the
 * units field ([unitsText], with its [unitsError] and an optional [unitsNote]). A new fund ([isNewFund]) goes to
 * [portfolioId]. [result] is what importing it came to.
 */
data class ImportCardUi(
    val id: Int,
    val fileName: String,
    val status: ImportCardStatus,
    val errorText: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val isNewFund: Boolean = false,
    val portfolioId: String? = null,
    val summaryText: String? = null,
    val notice: String? = null,
    val warnings: List<String> = emptyList(),
    val unitsText: String = "",
    val unitsError: String? = null,
    val unitsNote: String? = null,
    val result: ImportCardResultUi? = null,
)

/**
 * The import screen: the [cards] of the files picked, the [portfolios] a new fund can go to, whether the import is
 * running ([importing]) or done ([finished]), and whether it can start ([canImport]).
 */
data class ImportUiState(
    val cards: List<ImportCardUi> = emptyList(),
    val portfolios: List<PortfolioOptionUi> = emptyList(),
    val importing: Boolean = false,
    val finished: Boolean = false,
    val canImport: Boolean = false,
) {
    val canPickPortfolio: Boolean get() = portfolios.size >= MIN_PORTFOLIOS_TO_CHOOSE
}

/** Where a file's lots go: into a fund the user has, or into a new one found by its ISIN. */
private sealed interface Target {
    val holdingId: String

    data class Existing(val holding: PortfolioHolding, val movementCount: Int) : Target {
        override val holdingId: String get() = holding.id
    }

    data class New(val candidate: InstrumentCandidate, val quote: Quote, override val holdingId: String) : Target
}

private sealed interface Resolution {
    data object Loading : Resolution

    data class Failed(val message: String) : Resolution

    /** [initialUnits] is the units field as first filled; [estimated] says it was worked out, not known. */
    data class Ready(
        val file: MyInvestorFile,
        val target: Target,
        val price: Price?,
        val initialUnits: String,
        val estimated: Boolean,
    ) : Resolution
}

private data class CardModel(
    val id: Int,
    val fileName: String,
    val isin: String?,
    val resolution: Resolution = Resolution.Loading,
    val unitsText: String = "",
    val portfolioId: String? = null,
    val result: ImportCardResultUi? = null,
)

private data class Session(
    val cards: List<CardModel> = emptyList(),
    val portfolios: List<PortfolioOptionUi> = emptyList(),
    val defaultPortfolioId: String? = null,
    val importing: Boolean = false,
    val finished: Boolean = false,
)

private class UnitsCheck(val units: Units?, val error: String?)

/**
 * Imports the open lots of MyInvestor "Plusvalías y minusvalías" files as purchases. Each file is read when picked
 * ([onFilesPicked]) and matched, by the ISIN in its name, to a fund the user has (its movements will be replaced) or
 * to a new one that the quote search finds (it will be created, in the portfolio chosen with [onPortfolioChange],
 * with the broker MyInvestor). Nothing is written until [onImport], which goes through the valid cards one by one so
 * that one failing does not stop the next; each card ends with its [ImportCardUi.result]. The units of each fund are
 * editable ([onUnitsChange]) because the file has none: they start as the app's, or as an estimate for a new fund.
 */
class ImportMyInvestorViewModel(
    private val quoteSource: QuoteSource,
    private val portfolioRepository: PortfolioRepository,
    private val portfolios: PortfolioCollectionRepository,
    private val preferences: PreferencesRepository,
    private val movementRepository: MovementRepository,
    private val clock: Clock,
    private val idProvider: () -> String = ::newHoldingId,
) : ViewModel() {
    private var session = Session()
    private val mutableState = MutableStateFlow(render(session))
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()
    private var nextCardId = 0

    init {
        viewModelScope.launch {
            val remembered = preferences.observeSelectedPortfolioId().first()
            portfolios.observePortfolios().collect { all -> showPortfolios(all, remembered) }
        }
    }

    /** Adds the picked [files] to the list and reads them one by one; nothing is written. */
    fun onFilesPicked(files: List<PickedFile>) {
        if (files.isEmpty() || session.importing || session.finished) return
        val added = files.map { CardModel(id = nextCardId++, fileName = it.name, isin = isinFromFileName(it.name)) }
        change { it.copy(cards = it.cards + added) }
        viewModelScope.launch {
            files.zip(added).forEach { (file, model) ->
                val resolution = resolve(file)
                val units = (resolution as? Resolution.Ready)?.initialUnits.orEmpty()
                change { current ->
                    current.copy(
                        cards = current.cards.map {
                            if (it.id == model.id) it.copy(resolution = resolution, unitsText = units) else it
                        },
                    )
                }
            }
        }
    }

    fun onUnitsChange(cardId: Int, text: String) = edit(cardId) { it.copy(unitsText = text) }

    /** Sends the new fund of card [cardId] to [portfolioId]; ignored if there is no such portfolio. */
    fun onPortfolioChange(cardId: Int, portfolioId: String) {
        if (session.portfolios.none { it.id == portfolioId }) return
        edit(cardId) { it.copy(portfolioId = portfolioId) }
    }

    fun onRemove(cardId: Int) {
        if (session.importing || session.finished) return
        change { current -> current.copy(cards = current.cards.filterNot { it.id == cardId }) }
    }

    /** Imports the valid cards in order, each in one step; a failing one is reported and the next goes on. */
    fun onImport() {
        if (!mutableState.value.canImport) return
        change { it.copy(importing = true) }
        viewModelScope.launch {
            val duplicates = duplicateIds(session.cards)
            session.cards.filter { it.id !in duplicates && it.resolution is Resolution.Ready }.forEach { model ->
                val result = importCard(model, model.resolution as Resolution.Ready)
                change { current ->
                    current.copy(cards = current.cards.map { if (it.id == model.id) it.copy(result = result) else it })
                }
            }
            change { it.copy(importing = false, finished = true) }
        }
    }

    private suspend fun importCard(model: CardModel, ready: Resolution.Ready): ImportCardResultUi {
        val target = ready.target
        return try {
            val units = checkNotNull(parseUnitsEs(model.unitsText)) { "the units were checked before" }
            when (val planned = planImport(ready.file, units, target.holdingId)) {
                is ImportResult.Err ->
                    ImportCardResultUi(false, unitSplitMessage(planned.error, ready.file.lots.size))
                is ImportResult.Ok -> {
                    write(model, target, planned.value)
                    ImportCardResultUi(true, importedText(planned.value.buys.size))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ImportCardResultUi(false, movementErrorMessage(failure))
        }
    }

    private suspend fun write(model: CardModel, target: Target, plan: ImportPlan) {
        when (target) {
            is Target.Existing -> movementRepository.replaceHoldingMovements(target.holdingId, plan.buys)
            is Target.New -> movementRepository.addHoldingWithPurchases(
                newHolding(target, plan, checkNotNull(effectivePortfolioId(model, session))),
                plan.buys,
            )
        }
    }

    private fun newHolding(target: Target.New, plan: ImportPlan, portfolioId: String): PortfolioHolding {
        val candidate = target.candidate
        return PortfolioHolding(
            id = target.holdingId,
            portfolioId = portfolioId,
            name = candidate.holdingName(),
            type = InstrumentType.FUND,
            isin = candidate.isin,
            ticker = candidate.ticker,
            valuation = HoldingValuation.Quoted(
                ref = candidate.ref,
                currency = Currency.EUR,
                units = plan.totalUnits,
                lastQuote = LastQuote(target.quote.price, target.quote.date, clock.now().toEpochMilliseconds()),
            ),
            broker = MYINVESTOR_BROKER,
            fundamentals = candidate.fundamentals,
        )
    }

    // ---- Reading a file -----------------------------------------------------------------------------------------

    private suspend fun resolve(file: PickedFile): Resolution {
        val bytes = try {
            file.readBytes()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Resolution.Failed(UNREADABLE_FILE_TEXT)
        }
        val parsed = when (val read = readMyInvestorFile(bytes, file.name)) {
            is ImportResult.Err -> return Resolution.Failed(readErrorMessage(read.error))
            is ImportResult.Ok -> read.value
        }
        val isin = parsed.isin ?: return Resolution.Failed(NO_ISIN_TEXT)
        val existing = portfolioRepository.observeAll().first().firstOrNull { it.isin.equals(isin, ignoreCase = true) }
        return if (existing != null) resolveExisting(parsed, existing) else resolveNew(parsed, isin)
    }

    private suspend fun resolveExisting(file: MyInvestorFile, holding: PortfolioHolding): Resolution {
        val valuation = holding.valuation as? HoldingValuation.Quoted
            ?: return Resolution.Failed(MANUAL_HOLDING_TEXT)
        if (valuation.currency != Currency.EUR) return Resolution.Failed(ONLY_EUR_TEXT)
        val summary = try {
            movementRepository.movementsSummary(holding.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Resolution.Failed(movementErrorMessage(failure))
        }
        if (summary.hasTransfers) {
            return Resolution.Failed(movementErrorMessage(HoldingHasTransfersException(holding.id)))
        }
        val price = valuation.lastQuote?.price
        val held = valuation.units.scaled.takeIf { it > 0 }?.let(::Units)
        val units = held ?: estimateTotalUnits(file.lots, price)
        return Resolution.Ready(
            file = file,
            target = Target.Existing(holding, summary.movementCount),
            price = price,
            initialUnits = units?.let(::unitsInputText).orEmpty(),
            estimated = held == null,
        )
    }

    private suspend fun resolveNew(file: MyInvestorFile, isin: String): Resolution {
        val found = try {
            quoteSource.search(isin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Resolution.Failed(SEARCH_FAILED_TEXT)
        }
        val matches = found.filter { it.isin.equals(isin, ignoreCase = true) }
        val candidate = matches.firstOrNull { it.ref.provider == QuoteProvider.MORNINGSTAR } ?: matches.firstOrNull()
            ?: return Resolution.Failed(notFoundText(isin))
        if (candidate.currency != null && candidate.currency != Currency.EUR) {
            return Resolution.Failed(ONLY_EUR_TEXT)
        }
        val quote = try {
            quoteSource.latestQuote(candidate.ref)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Resolution.Failed(QUOTE_FAILED_TEXT)
        }
        if (quote.price.currency != Currency.EUR) return Resolution.Failed(ONLY_EUR_TEXT)
        return Resolution.Ready(
            file = file,
            target = Target.New(candidate, quote, idProvider()),
            price = quote.price,
            initialUnits = estimateTotalUnits(file.lots, quote.price)?.let(::unitsInputText).orEmpty(),
            estimated = true,
        )
    }

    // ---- State --------------------------------------------------------------------------------------------------

    private fun showPortfolios(all: List<Portfolio>, remembered: String?) = change {
        it.copy(portfolios = all.toOptions(), defaultPortfolioId = defaultPortfolioId(all, remembered))
    }

    private fun edit(cardId: Int, transform: (CardModel) -> CardModel) {
        if (session.importing || session.finished) return
        change { current ->
            current.copy(cards = current.cards.map { if (it.id == cardId) transform(it) else it })
        }
    }

    private fun change(transform: (Session) -> Session) {
        session = transform(session)
        mutableState.value = render(session)
    }

    private fun render(session: Session): ImportUiState {
        val duplicates = duplicateIds(session.cards)
        val cards = session.cards.map { renderCard(it, it.id in duplicates, session) }
        val ready = cards.filter { it.status == ImportCardStatus.READY }
        val canImport = !session.importing && !session.finished &&
            cards.none { it.status == ImportCardStatus.LOADING } &&
            ready.isNotEmpty() && ready.all { it.unitsError == null }
        return ImportUiState(cards, session.portfolios, session.importing, session.finished, canImport)
    }

    /** The cards whose fund an earlier card already brings: the first file of a fund wins. */
    private fun duplicateIds(cards: List<CardModel>): Set<Int> {
        val seen = mutableSetOf<String>()
        return cards.filter { it.isin != null && !seen.add(it.isin) }.mapTo(mutableSetOf()) { it.id }
    }

    private fun effectivePortfolioId(model: CardModel, session: Session): String? =
        model.portfolioId?.takeIf { id -> session.portfolios.any { it.id == id } } ?: session.defaultPortfolioId

    private fun renderCard(model: CardModel, duplicate: Boolean, session: Session): ImportCardUi {
        val base = ImportCardUi(model.id, model.fileName, ImportCardStatus.LOADING, result = model.result)
        val resolution = model.resolution
        return when {
            resolution is Resolution.Loading -> base
            resolution is Resolution.Failed ->
                base.copy(status = ImportCardStatus.ERROR, errorText = resolution.message)
            duplicate -> base.copy(status = ImportCardStatus.ERROR, errorText = DUPLICATE_FILE_TEXT)
            resolution is Resolution.Ready -> renderReady(model, resolution, base, session)
            else -> base
        }
    }

    private fun renderReady(
        model: CardModel,
        ready: Resolution.Ready,
        base: ImportCardUi,
        session: Session,
    ): ImportCardUi {
        val summary = ready.file.summary
        val check = checkUnits(model.unitsText, ready.file, ready.target.holdingId)
        val warnings = listOfNotNull(
            redeemedWarning(summary),
            UNITS_OFF_WARNING.takeIf { looksOff(check.units, ready.price, summary.totalMarketValue) },
        )
        val note = UNITS_ESTIMATED_NOTE.takeIf {
            ready.estimated && model.unitsText.isNotEmpty() && model.unitsText == ready.initialUnits
        }
        val common = base.copy(
            status = ImportCardStatus.READY,
            summaryText = summaryText(summary),
            warnings = warnings,
            unitsText = model.unitsText,
            unitsError = check.error,
            unitsNote = note,
        )
        return when (val target = ready.target) {
            is Target.Existing -> common.copy(
                title = target.holding.name,
                subtitle = session.portfolios.firstOrNull { it.id == target.holding.portfolioId }?.name,
                notice = replaceNotice(target.movementCount, hasHandTypedUnits = !target.holding.isClosed()),
            )
            is Target.New -> {
                val portfolioId = effectivePortfolioId(model, session)
                common.copy(
                    title = "Fondo nuevo: ${target.candidate.holdingName()}",
                    subtitle = session.portfolios.firstOrNull { it.id == portfolioId }?.name,
                    isNewFund = true,
                    portfolioId = portfolioId,
                )
            }
        }
    }

    /** Whether [text] are units that can be shared among the lots of [file]; otherwise why not. */
    private fun checkUnits(text: String, file: MyInvestorFile, holdingId: String): UnitsCheck {
        if (text.isBlank()) return UnitsCheck(null, UNITS_BLANK_TEXT)
        val units = parseUnitsEs(text) ?: return UnitsCheck(null, UNITS_INVALID_TEXT)
        return try {
            when (val plan = planImport(file, units, holdingId)) {
                is ImportResult.Err -> UnitsCheck(null, unitSplitMessage(plan.error, file.lots.size))
                is ImportResult.Ok -> UnitsCheck(units, null)
            }
        } catch (_: ArithmeticException) {
            UnitsCheck(null, UNITS_TOO_BIG_TEXT)
        }
    }

    /** Whether [units] x [price] is more than 2 % away from the market value of the file; false without both. */
    private fun looksOff(units: Units?, price: Price?, marketValue: Money): Boolean =
        units != null && price != null && marketValue.minorUnits > 0 && unitsLookOff(units, price, marketValue)

    private fun PortfolioHolding.isClosed(): Boolean = (valuation as? HoldingValuation.Quoted)?.isClosed ?: true
}
