# Portfolio phase 2: movements, cost basis and own returns

**Locator:** `odd/tasks/portfolio-movements.md` · Engram topic `odd/portfolio-movements/tasks`
**Branch:** `feat/portfolio-movements` (from `main` @ `383351c`)
**Delivery strategy:** `single-pr` (user preference)
**RDD:** off (global) → parent verification after each writer.

## Objective

Record buys, sells, fund transfers ("traspasos"), dividends and fees per position, derive the units from them, and
show the user's own results (cost, realized/unrealized gains, XIRR) separately from the product's history.

## Decisions (user, 2026-10-11/12)

1. **Two kinds of position.** *Without movements* (as today): units edited by hand; no own returns ("sin datos de
   compra"). *With movements*: units derived from the movements; manual unit edits are blocked (add a movement).
   - Adding the first movement to a position that already has units offers an **opening balance** ("saldo
     inicial"): units, date and total cost — the cost is OPTIONAL. Unknown cost → that tranche shows "coste
     desconocido"; FIFO consumes it first, so realized gains on those units show "—" until the cost is filled in.
     The user may instead enter the real purchase history, or replace the opening balance later.
   - New products: the add form offers *Lo que tengo ahora* (as today, no movements) or *Una compra* (date, units,
     amount paid, fee) which creates a position with movements.
2. **Traspasos** only between **funds** (origin and destination `InstrumentType.FUND`): a linked record (out + in)
   that carries the FIFO lots with their **original acquisition dates and cost**, no realized gain; may cross
   portfolios and brokers. ETFs, stocks and crypto are sell + buy (they are taxed).
3. **Fees**: a separate field on the movement; a buy's fee adds to its cost, a sell's fee reduces the proceeds.
4. **Dividends**: an income movement; does not change cost. Accumulating funds need nothing; reinvesting =
   dividend + buy.
5. **Currencies**: the movement keeps its amount in the holding currency plus its EUR equivalent at that day's FX
   (editable); cost basis and gains are in EUR.
6. **Validation**: selling or transferring more units than held on that date is an error.
7. **Own returns**: realized and unrealized gains (€ and %) and XIRR, per position, per portfolio and for Todas;
   positions without movements (or with unknown cost) are excluded with a note. TWR: later.
8. **Screens**: per position, a movements list and a *Mi posición* section (cost vs value, gains, XIRR, FIFO lots,
   value-of-my-position chart) separate from the product history; portfolio detail adds own gains and XIRR.
9. **Out of scope**: broker imports (phase 3), rebuilding past monthly net-worth snapshots (only the current month
   is refreshed, as today), TWR.

## Design notes

- Units stay materialized on the holding (`unitsScaled`), recomputed from the movements in the same transaction
  as every movement write, so `value()`, totals, snapshots and analytics keep a single code path. A position with
  movements may reach 0 units (closed) — it stays listed as "Cerrada" and contributes 0.
- Same-day ordering: buy < opening < transfer-in < fee < dividend < transfer-out < sell, then entry sequence
  (rumbo's order extended).
- Per-position XIRR treats transfers at market value on their date (price history; cost as fallback); at
  portfolio/Todas level transfers between included positions cancel out.
- Product history (`instrument_prices` cache) is untouched; the position history is units-by-date × price.
- Persistence: `movements` table, Room v12 → v13, backup v13 (older files import positions without movements),
  `BACKED_UP_TABLES`, cloud `RECORD_KEYS` (movements are user records), clear-all.

## Tasks

- [x] **T1 — Domain + pure calc**: movement model, ordering, FIFO lots with carried cost/dates, transfers, units by
  date, realized/unrealized gains, XIRR, validation (route: delegated writer).
- [ ] **T2 — Persistence**: `movements` table, Room v13 + migration test, repository (add/edit/delete, transfers
  atomic across holdings, materialized units, oversell validation), backup v13, clear-all/cloud (route: delegated
  writer).
- [ ] **T3 — UI movements**: movements list in the position detail, add/edit/delete sheet, opening-balance flow,
  add-product choice, block manual unit edits, traspaso flow (funds only) (route: delegated writer).
- [ ] **T4 — UI own returns**: *Mi posición* section and position chart, portfolio detail gains/XIRR, closed
  positions (route: delegated writer).
- [ ] **T5 — Docs** (route: inline): README *Cartera*, roadmap.

## Acceptance criteria

- Existing positions keep working untouched after the upgrade; nothing changes in net worth.
- A position with movements derives its units; manual edits are blocked; oversell is rejected.
- A fund-to-fund traspaso moves lots with their dates and cost and realizes no gain; non-funds can't use it.
- Gains and XIRR match hand-computed examples; unknown cost is shown as such, never as zero.
- Backups round-trip movements; older backups import without them.

## Checks

- `scripts/check.sh` (ktlint, unit tests, R8 preview build, schema committed)
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator: opening balance, buys/sells, traspaso between two funds, gains/XIRR, backup.

## Progress / evidence

- **T1** (commit below, `feat(portfolio): model movements with FIFO cost and own returns`): `Movement` (per-type
  invariants), `MovementType` order = same-day order, `Lot(acquiredOn, units, costEur?)`, `UnitsBalance` (≥ 0, for
  closed positions; `Units` unchanged); `computeLedgers` (FIFO, exact proportional cost via `mulDivHalfEven`,
  unknown cost kept apart in `GainBreakdown`, oversell → `InsufficientUnitsException`, transfers carry lots with
  dates/cost and rescale units by cumulative boundaries, no realized gain); `xirr` (bisection, ACT/365, null
  without both signs or same-day only); `positionCashFlows`/`combineCashFlows` (transfers cancel inside a scope);
  `valueSeries`. Tests: 67 new (hand-computed fixtures; XIRR cross-checks 10 %, 10 %, 21 %).
  RED: 276 compile errors for ledger/XIRR/flows (model tests written after the model). Parent mutation check:
  FIFO → LIFO makes 8 tests fail. GREEN (parent re-run): check.sh (1381 tests), iOS compile.
  Notes for T2: `sequence` global across the app; both transfer legs must go to `computeLedgers` together; fund-only
  eligibility checked in T2/T3; `HoldingValuation.Quoted.units` can't hold 0 → persist closed positions explicitly.

## Next step

T2 — persistence.
