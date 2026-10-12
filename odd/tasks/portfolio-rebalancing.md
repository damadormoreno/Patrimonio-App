# Portfolio rebalancing alerts

**Locator:** `odd/tasks/portfolio-rebalancing.md` · Engram topic `odd/portfolio-rebalancing/tasks`
**Branch:** `feat/portfolio-rebalancing` (from `main` @ `505e781`)
**Delivery strategy:** `single-pr` (user preference; no line budget)
**RDD:** off (global) → parent verification after each writer.

## Objective

Let the user set a target allocation per asset class for each portfolio, and tell them in Cartera when a
portfolio drifts out of its tolerance band, with where to put new money to get back on target without selling.

## Decisions (user, 2026-10-12)

1. Targets are **per asset class** (`AssetClass`: Renta variable, Renta fija, Monetario, Mixto, Oro y materias
   primas, Inmobiliario, Cripto, Otros) and **per portfolio** (not per product, not for *Todas*).
2. Tolerance band per portfolio, default **±5 percentage points**, editable.
3. The suggestion is about contributing, not selling: "aporta X € para volver al objetivo sin vender", split by
   class. (Selling is never suggested.)

## Design (parent proposal, within the decisions)

- **Math (pure)**, values in EUR minor units, targets in basis points (sum = 10 000):
  - weight_i = value_i / V (V = Σ value of classified holdings); drift_i = weight_i − target_i.
  - A portfolio **needs rebalancing** when any class has |drift_i| > tolerance.
  - **Minimal no-sell contribution**: C = max over classes with target_i > 0 of (value_i / target_i) − V (≥ 0);
    per-class split a_i = target_i × (V + C) − value_i (≥ 0, Σ a_i = C exactly after rounding to cents with a
    largest-remainder fix). After contributing, every class is exactly on target.
  - A held class with **target 0** can't be fixed by contributing → flagged "solo se corrige vendiendo" and left
    out of C (C is computed on the other classes).
  - **Unclassified** holdings (no effective class) are excluded from V with a note ("N productos sin clase").
  - Optional: for a given amount the user plans to contribute, split it to the most underweight classes first
    (fill deficits towards the target proportions) — include if it stays simple; otherwise later.
- **Persistence**: table `allocation_targets(portfolioId FK cascade, assetClass, targetBp)` with PK
  (portfolioId, assetClass); tolerance as `portfolios.rebalanceToleranceBp` (NOT NULL DEFAULT 500).
  Room v13 → v14 (AutoMigration if it handles both), backup v14 (older backups import without targets),
  `BACKED_UP_TABLES`, cloud `RECORD_KEYS`, clear-all. Targets must sum 100 % (validated in the repository).
- **UI**:
  - Portfolio detail (*Ver detalle*): section *Objetivo de reparto* — empty state with "Definir objetivo"; edit
    sheet with a % field per class (prefilled with the current split, rounded), live "Suma: 100 %", tolerance
    field, *Quitar objetivo*. The class breakdown shows target vs actual and the drift.
  - Cartera, selected portfolio out of band: a banner "Tu cartera se ha desviado del objetivo" with the worst
    drift and the no-sell contribution split; tap → portfolio detail. In *Todas*: a banner listing the portfolios
    that need rebalancing.

## Tasks

- [x] **T1 — Rebalance math (pure)**: drift, band check, minimal no-sell contribution and split, zero-target
  and unclassified handling (route: delegated writer).
- [ ] **T2 — Persistence**: entity/DAO/migration v14, repository (validated targets + tolerance), backup v14,
  cloud keys, clear-all (route: delegated writer).
- [ ] **T3 — UI**: targets editor and target-vs-actual in portfolio detail; Cartera banners (route: delegated
  writer).
- [ ] **T4 — Docs + manual test** (route: inline).

## Acceptance criteria

- Hand-computed examples: drift, band, contribution C and split exact to the cent (Σ split = C).
- Targets survive restart, backup round trip and portfolio deletion cleanup (cascade).
- Nothing alerts without targets; a portfolio within the band shows no banner.

## Checks

- `scripts/check.sh`, iOS cross-compile, emulator test (data backed up on the host first, restored after).

## Progress / evidence

- **T1** (commit below, `feat(rebalance): compute drift and the no-sell contribution`): `domain/calc/Rebalance.kt`
  — `validateTargets`, `rebalanceStatus` (weights/drift in bp half-even, band inclusive at the tolerance, C over
  the targeted classes with a per-class ceiling so the cent split needs no clamping, target-0 held classes in
  `onlySelling`, unclassified reported apart), `RebalanceStatus.splitContribution(amount)` (water-filling; the
  minimal split is the same routine with `amount = C`). Inputs come from `allocationByAssetClass` (same source as
  *Reparto por clase*). RED: unresolved references; GREEN: check.sh (1818 tests), iOS compile; parent re-run green.

## Next step

T2.
