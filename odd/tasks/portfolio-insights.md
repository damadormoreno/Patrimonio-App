# Portfolio insights: position detail and portfolio detail

**Locator:** `odd/tasks/portfolio-insights.md` · Engram topic `odd/portfolio-insights/tasks`
**Branch:** `feat/portfolio-insights` (stacked on `feat/portfolio-broker` @ `1293d22`, PR #30; retarget to `main`
once #30 merges)
**Delivery strategy:** `single-pr` (user preference: no size-based splitting)
**RDD:** off (global) → parent verification after each writer.

## Objective

Tapping a holding in Cartera opens a position detail with the product's price history chart, returns per
calendar year and other useful metrics. Tapping the portfolio total opens a portfolio detail with, at least,
the correlation matrix between holdings, plus other interesting portfolio-level information.

## Decisions (user, 2026-10-11)

- History and returns are the **product's** (price/NAV), not the user's gains: there are no movements yet.
- In phase 2 (movements) the product history and the position's own history must be **two different
  things**; the product history must be kept, not replaced.
- Charts: Canvas like the existing ones (`NetWorthHistoryScreen`, `NetWorthSparkline`); a third-party chart
  library is acceptable if really needed.
- Proposal accepted (below).

## Scope

Position detail (pushed screen from a Cartera row; the existing edit sheet stays reachable from it):
- Price chart with ranges 1M · 6M · 1A · 5A · Máx and the change over the selected range.
- Return per calendar year and year-to-date.
- Annualized return over 1, 3 and 5 years; annualized volatility; maximum drawdown.
- Morningstar data when available: ongoing charge (TER) and its yearly cost in € for the current value,
  risk (SRRI 1–7), category, management company.
- Weight of the position in the portfolio. Manual holdings: no history, show what applies.

Portfolio detail (pushed screen from the Cartera total):
- Correlation matrix between quoted holdings (weekly returns over their common history, in EUR).
- Value evolution with the **current** composition (constant units, clearly labelled), with its calendar-year
  returns, volatility and maximum drawdown.
- Allocation by type, by bank/broker and by currency.
- Weighted average TER (% and €/year) and weighted average risk.
- Concentration: largest weight, warning when one holding exceeds 40 %.

Out of scope: movements and real gains (phase 2), benchmarks/comparators, exports.

## Constraints

- History fetched from the same sources as quotes (Morningstar NAV timeseries in EUR; Yahoo chart
  `range=max`), cached on device and refreshed incrementally; it is a re-downloadable cache.
- Non-EUR instruments need historical FX for EUR-based portfolio analytics (Frankfurter time series).
- Pure, unit-tested analytics in `domain/calc`; overflow-safe fixed point where money is involved.
- DB and backup versions bump only if persisted user data changes (fundamentals on holdings).

## Tasks

- [x] **T1 — Data: price history + FX history caches, holding fundamentals (TER, SRRI, category,
  manager), DB/backup bumps, sync policy** (route: delegated writer).
- [x] **T2 — Analytics (pure calc)**: ranges, period/calendar-year/YTD returns, CAGR 1/3/5y, volatility,
  max drawdown, weekly alignment, correlation matrix, constant-composition portfolio series in EUR,
  weighted TER/risk, allocations, concentration (route: delegated writer).
- [ ] **T3 — Position detail screen + reusable Canvas line chart** (route: delegated writer).
- [ ] **T4 — Portfolio detail screen + correlation heatmap** (route: delegated writer).
- [ ] **T5 — Docs** (route: inline): README *Cartera* section.

## Acceptance criteria

- Both details open from Cartera, work offline with cached history, and degrade gracefully (manual holdings,
  holdings with short history, a single quoted holding → no matrix).
- Figures match independent calculations in tests; charts render the selected range.

## Checks

- `scripts/check.sh` (ktlint, unit tests, R8 preview build, schema committed)
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator with live data.

## Progress / evidence

- **T1** (commit below, `feat(portfolio): cache price and FX history and fetch fund fundamentals`):
  sibling ports `PriceHistorySource` + `FundamentalsSource` (implemented by `CompositeQuoteSource`);
  `instrument_prices` + `instrument_price_sync` cache (full first sync, incremental from last day − 7, skip
  < 6 h unless forced, ≤ 4 concurrent, pruned when no holding references it; wiped on clear-all/import; not
  backed up, not cloud-tracked); Yahoo history picks the smallest covering `range`; `fx_history` +
  `fx_history_sync` from Frankfurter time series (kept on clear-all like `fx_rates`), `FxHistory.rateOn` with
  ≤ 7-day previous fallback; `InstrumentFundamentals` (TER ×1e4, SRRI, category, manager) on candidates and
  holdings, backfill once a day per holding; Room v10 (`AutoMigration(9, 10)`, `10.json`); backup v10.
  Parent change: weekend NAVs now follow rumbo exactly (only fill an empty Friday; earliest weekend point
  wins), RED→GREEN in `MorningstarApiTest`.
  RED: test sources failed to compile against missing APIs; then 2 assertion failures. GREEN (parent re-run):
  `scripts/check.sh` "All checks passed." (779 tests), iOS compile executed.
- **T2** (commit below, `feat(portfolio): compute price and portfolio analytics`): `PriceAnalytics.kt`
  (`HistoryRange`, `pointsIn`, `rangeChange`, `calendarYearReturns`, `annualizedReturn`, ISO-week
  `weeklyCloses`/`weeklyReturns`, `volatility` = sample stdev × √52 over 3 y with fallback, ≥ 26 weeks,
  `maxDrawdown`) and `PortfolioAnalytics.kt` (`toEurSeries`, Pearson `correlationMatrix`, constant-composition
  series with ≤ 7-day forward fill, `holdingWeights`, allocation by type/broker/currency, `costSummary`,
  `riskSummary`, `concentration` > 40 %). Money exact, ratios `Double`. RED: test sources failed to compile;
  41 tests GREEN. Parent reviewed volatility/Pearson formulas. GREEN (parent re-run): check.sh (820 tests),
  iOS compile.

## Next step

T3 — position detail screen + Canvas chart.
