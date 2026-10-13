# Cartera tab redesign (less cramped)

**Locator:** `odd/tasks/cartera-redesign.md` · Engram topic `odd/cartera-redesign/tasks`
**Branch:** `feat/cartera-redesign` (stacked on `feat/holdings-in-groups`, PR #42; retarget/rebase on `main` once
#42 merges)
**Delivery strategy:** `single-pr` (user preference; no line budget)
**RDD:** off (global) → parent verification after the writer.

## Objective

The Cartera tab feels cramped (user screenshot, 2026-10-13, dark theme): the header has «Importar» and
«Actualizar» pills next to the big title; the scope row has Todas / portfolio chips with the selected chip clipped
by the pinned «Filtrar» and «Gestionar»; each product row takes four lines.

## Decisions (user, 2026-10-13)

1. «Gestionar» leaves the scope row: renaming and deleting a portfolio move to that portfolio's detail
   («Ver detalle»), next to the allocation target.
2. Product rows use **two lines**: name + value on top; a muted line with type · class · broker · units below.
3. Parent proposal accepted with the above: header = title with «Importar» and «Actualizar» as icon buttons (pull
   to refresh stays); scope row = Todas + portfolios + «+ Nueva» only; «Filtrar» becomes an icon with the active
   count near the total / above the list.

## Tasks

- [x] **T1 — Redesign** (route: delegated writer; several UI files).
- [x] **T2 — Docs + manual test** (route: inline): README (*Cartera*: where Gestionar/Filtrar live now),
  emulator light/dark, phone width.

## Acceptance criteria

- At 360–412 dp width nothing in the header or scope row is clipped; the selected portfolio chip is fully visible.
- Rename/delete a portfolio from its detail with the same rules as today (move holdings on delete, last portfolio
  can't be deleted).
- Filters, rebalance banner, closed rows, import and refresh keep working; accessibility labels for icon buttons.

## Checks

- `scripts/check.sh`, iOS cross-compile, emulator (user data backed up on the host first, restored after).

## Progress / evidence

- **T1** (commit below, `feat(portfolio): declutter the Cartera tab`): header icon buttons (download → «Importar de
  MyInvestor», new `RefreshIcon` → «Actualizar precios», spinner while refreshing via `HeaderIconBtn(loading)`);
  scope row only Todas / portfolios / «+ Nueva», selected chip kept in view; «Gestionar cartera» section in the
  portfolio detail (`PortfolioManagement`, rename/delete with the existing dialog and rules; after delete back to
  Cartera on «Todas»); «Productos» list header with the filter icon + count badge and «Filtrado · N de M»; rows in
  two lines with `holdingMetaLine`. RED observed (77 compile errors against HEAD in a temp worktree); GREEN:
  check.sh, iOS compile. Parent follow-up after the emulator check: units moved before broker/portfolio in line 2
  (they were being cut, e.g. «157,270…»); RED 5 failing tests → GREEN; check.sh (2091 tests), iOS compile.
- **T2** (commit below, `docs: document the Cartera layout`): README. Emulator (user data snapshot before/after
  identical): light and dark at phone width — header icons, chips unclipped, «Productos» + filter icon, two-line
  rows («Fondo · Renta variable · 157,270425 part. · MyInvestor»); portfolio detail «Gestionar cartera» → Eliminar
  shows «Sus 3 productos pasarán a: ju» (cancelled).

## Next step

Push + PR (stacked on #42; retarget to `main` after #42 merges).
