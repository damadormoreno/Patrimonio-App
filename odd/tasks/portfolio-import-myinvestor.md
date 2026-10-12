# Portfolio phase 3a: import fund lots from MyInvestor

**Locator:** `odd/tasks/portfolio-import-myinvestor.md` · Engram topic `odd/portfolio-import-myinvestor/tasks`
**Branch:** `feat/portfolio-import-myinvestor` (from `main` @ `868af03`)
**Delivery strategy:** `single-pr` (user preference; no line budget)
**RDD:** off (global) → parent verification after each writer.

## Objective

Import the open lots of each MyInvestor fund from its "Plusvalías y minusvalías" CSV as purchases with their
original fiscal date and cost, so cost, latent gain and XIRR are exact without typing every buy by hand.

## Source format (verified with a real export, 2026-10-12)

- One CSV per fund, downloaded from MyInvestor web → fund → *Plusvalías y minusvalías*. File name
  `Plusvalías y minusvalías fondo <ISIN>.csv`; the ISIN is NOT inside the file.
- UTF-8 without BOM, CRLF, `;` separator, header `Fecha fiscal;Inversión;Valor de mercado;Resultado fiscal`,
  Spanish decimals (`299,89`, `-0,08`), dates `dd/MM/yyyy`. Tolerate `,` separator, a BOM, cp1252/latin-1, and
  thousands separators (`1.234,56`) — same tolerance as rumbo's importer.
- One row per open lot. Transferred-in lots keep the original fiscal date and cost. Redeemed lots (per rumbo) show
  investment and market value 0 and only the fiscal result. No units in the file.
- Reference: rumbo `app/importar.py` (`leer_csv_myinvestor`, MIT).

## Decisions (user, 2026-10-12)

1. Entry in *Cartera*: "Importar de MyInvestor", one or several CSV at once (FileKit picker).
2. Each file is matched to the user's fund by the ISIN in the file name; an unknown fund is created by looking the
   ISIN up in Morningstar (portfolio chosen by the user, broker prefilled "MyInvestor").
3. Each open lot becomes a BUY: fiscal date, cost = *Inversión* (EUR), fee 0, note "MyInvestor".
4. Units: the fund's current units (prefilled from the app; for a new fund estimated as total market value / latest
   price; always editable, to be copied from MyInvestor) are split across lots in proportion to their market value,
   with an exact largest-remainder split so they add up to the total.
5. Preview before saving: lots, total invested, total market value, units; warnings (redeemed lots not imported,
   app units far from the file's market value, file not recognised).
6. Re-import **replaces** all movements of that fund (the preview says how many); blocked when the fund has
   traspasos recorded in the app.
7. Out of this version: realized gains of redeemed lots (no date/units), ETFs/stocks (MyInvestor does not export
   them), non-EUR funds (clear error), Openbank.

## Tasks

- [x] **T1 — Parse + plan (pure)**: CSV reader, ISIN from file name, lots/redemptions, unit split, BUY drafts,
  typed errors/warnings (route: delegated writer).
- [x] **T2 — Persistence**: atomic "replace a holding's movements" (blocked by traspasos) and "create a holding
  from imported movements", reusing the movement-write pipeline (route: delegated writer).
- [ ] **T3 — UI**: Cartera entry, multi-file pick, preview per file (match/new fund, units field, totals,
  warnings, replace notice), import, result (route: delegated writer).
- [ ] **T4 — Docs + manual test** (route: inline): README, emulator with the real CSV.

## Acceptance criteria

- The sample export imports as 19 BUYs whose costs add up to the file's total investment and whose units add up
  exactly to the chosen total.
- Re-importing the same file leaves the same movements (no duplicates); a fund with traspasos is refused.
- Mi posición after import: aportado = Σ Inversión; value matches the app's price × units.
- Nothing is written before the user confirms the preview; an invalid file writes nothing.

## Checks

- `scripts/check.sh` (ktlint, unit tests, R8 preview build, schema committed)
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator with the real CSV (data backed up first, restored after).

## Progress / evidence

- **T1** (commit below, `feat(import): read MyInvestor gains files and plan the lots`): package `domain/importing`:
  `decodeCsvBytes` (strict UTF-8, BOM, Windows-1252 fallback), `isinFromFileName` (check digit),
  `readMyInvestorFile` (header by name, `;`/`,`, Spanish numbers, typed `MyInvestorReadError` with line/column),
  `splitUnits` (exact largest remainder, earlier lot wins ties, `TotalTooSmall`), `estimateTotalUnits`,
  `unitsLookOff` (> 2 %), `planImport` → EUR BUY `MovementDraft`s + `ImportSummary`. Synthetic fixtures only.
  RED: 266 compile errors; GREEN: check.sh (1686 tests), iOS compile. Parent spot check with the user's real
  export (temporary test, not committed): 19 lots, Σ cost 3.899,34 €, Σ value 4.658,35 €, units sum exact and
  proportional. Notes for T2/T3: drafts carry no holding id (new fund: generate the id first); EUR holdings only
  (`buildMovement` rejects another currency); match by `PortfolioHolding.isin`.
- **T2** (commit below, `feat(import): replace a fund's movements and create funds from purchases`):
  `MovementRepository.replaceHoldingMovements` (deletes and inserts in the same `commit` pipeline: replay,
  materialized units, snapshot; refused with `HoldingHasTransfersException`, empty list or invalid drafts → nothing
  written), `addHoldingWithPurchases` (generalizes `addHoldingWithPurchase`, all BUY), `movementsSummary`
  (count + has transfers, for the preview); DAO `countTransferLegs`/`listByHolding`; fake in sync. No schema change.
  RED: 42 compile errors; GREEN: check.sh (1702 tests), iOS compile; parent re-run of the repository tests green.

## Next step

T3.
