# Instrument preview before adding it

**Locator:** `odd/tasks/instrument-preview.md` · Engram topic `odd/instrument-preview/tasks`
**Branch:** `feat/instrument-preview` (from `main` @ `3d04254`, after PR #32)
**Delivery strategy:** `single-pr` (user preference: no size-based splitting)
**RDD:** off (global) → parent verification after each writer.

## Objective

When the user searches a fund/ETF/stock and taps a result, show a preview with the product's information and how
it fits the user's portfolio, before adding it.

## Decisions (user, 2026-10-11)

- Proposal accepted: preview screen between the search result and the add form.
- Motivation seen in the user's own data: three MSCI World products correlated 0,96–1,00; the preview should warn
  before adding another similar one.

## Scope

1. Product information (reuse the position detail building blocks): price chart with ranges and scrubbing,
   calendar-year returns as bars, annualized returns, volatility, maximum drawdown, Morningstar fundamentals
   (TER, risk, category, manager) when the candidate has them. Header with name, ISIN/ticker, type, source.
2. Fit with the portfolio (current Cartera selection, or Todas):
   - correlation of the candidate with each holding (weekly EUR returns, same rules as the portfolio matrix),
     most similar first: `Se mueve casi igual que «Vanguard Global Stock» (0,98)`;
   - correlation with the portfolio as a whole (constant-composition series) and a verdict: diversifica poco
     (≥ 0,9) · diversifica moderadamente (0,5–0,9) · aporta diversificación (< 0,5);
   - optional amount field: projected weight of the new product, weighted TER, weighted risk and whether the
     concentration warning (> 40 %) would fire.
   - Hidden when the portfolio has no quoted holdings with history, or the candidate's history is too short.
3. `Añadir a la cartera` continues to the existing add form with the candidate already picked (units/amount,
   broker, portfolio).

Constraints: the candidate's history is fetched for the preview only (not persisted until the product is added;
the existing sync downloads it then). Non-EUR candidates need FX history (existing FX history cache). Yahoo
candidates have no fundamentals.

Out of scope: comparing two arbitrary products, saving a watchlist, benchmark comparisons.

## Tasks

- [x] **T1 — Fit analytics + transient preview data** (pure calc + loader; route: delegated writer).
- [x] **T2 — Preview screen + navigation from search to add form** (route: delegated writer).
- [x] **T3 — Docs** (route: inline): README *Cartera*.

## Acceptance criteria

- Tapping a search result opens the preview; `Añadir a la cartera` reaches the add form with the product picked.
- Figures match the position detail for the same product; fit verdict follows the thresholds.
- Works offline-degraded: a failed download shows an error with retry and still allows adding.

## Checks

- `scripts/check.sh` (ktlint, unit tests, R8 preview build, schema committed)
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator with live data (e.g. another MSCI World fund vs a bond or gold ETF).

## Progress / evidence

- **T1** (commit below, `feat(portfolio): compute how a product fits the portfolio`): pure `instrumentFit`
  (per-holding and portfolio correlation reusing extracted `weeklyReturnsByWeek`/`pairCorrelation`, verdict
  0,9/0,5, projection with weight/TER/risk/concentration via a stand-in holding); `InstrumentPreviewLoader`
  (history only through `PriceHistorySource`, never persisted — Room test; currency from the history points,
  deviation accepted: no extra latest-quote call; FX history sync for non-EUR); `PortfolioSeriesLoader`
  (cache-only EUR series of a scope) shared with the portfolio detail (`eurSeriesById`, `quotedRefs`).
  RED: 49 tests, 33 failed against `TODO()`; GREEN (parent re-run): check.sh (1148 tests), iOS compile.
- **T2** (commit below, `feat(portfolio): preview a product before adding it`): route `InstrumentPreview(provider,
  code)`; the candidate stays owned by `AddHoldingViewModel` (read from the `AddHolding` back-stack entry), so the
  search survives; `Añadir a la cartera` → `onPick(ref, portfolioId)`. Shared `PriceHistoryCards`; fit section
  (verdict banner, top 3 similar, `Si inviertes` with 250 ms debounce → weight, TER/risk before → after,
  concentration). RED: 84 tests, 35 failed; GREEN 1188.
  Parent fixes found in the manual test (in the same commit): weighted averages showed 4 decimals
  ("0,23 % → 0,1668 %") → new `formatAverageTer` (2 decimals, half-even; also in the portfolio detail, test
  "0,3172 %" → "0,32 %", RED→GREEN `AverageTerFormatTest`); concentration warnings showed "40 %" for 40,2 % →
  one decimal in both screens (expectations 82→81,8, 71→71,4, 79→79,1, 89→89,1). GREEN 1190, check.sh, iOS.
  Emulator, live data: iShares Dev World (`IE00BD0NCM55`) → "Diversifica poco" (1,00 · 156 semanas; Amundi
  1,00, Vanguard 1,00, iShares 0,96); 30.000 € → 52,4 %, concentration warning; Xetra-Gold (`4GLD.DE`) →
  "Aporta diversificación" (0,13), 5.000 € → 15,5 %; back keeps the search; `Añadir a la cartera` opens the
  form with the product picked (portfolio picker shown: the emulator has 2 portfolios).
- **T3** (commit below, `docs: document the product preview`): README *Cartera* (ficha previa).

## Next step

Done; push and PR to main.
