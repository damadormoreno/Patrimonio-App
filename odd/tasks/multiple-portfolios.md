# Multiple portfolios

**Locator:** `odd/tasks/multiple-portfolios.md` · Engram topic `odd/multiple-portfolios/tasks`
**Branch:** `feat/multiple-portfolios` (stacked on `feat/portfolio-insights` @ `2e0c120`, PR #31; retarget to `main`
once #31 merges)
**Delivery strategy:** `single-pr` (user preference: no size-based splitting)
**RDD:** off (global) → parent verification after each writer.

## Objective

Let the user keep several portfolios (e.g. "Jubilación", "Hijos") inside the Cartera tab.

## Decisions (user, 2026-10-11)

- Each holding belongs to exactly ONE portfolio (no overlapping like account groups).
- Proposal accepted: existing holdings move to a portfolio "Mi cartera"; a selector `Todas · <each> · +` in the
  Cartera tab; per-portfolio list, total and detail; create/rename/delete (deleting one with holdings asks where
  to move them); choose the portfolio when adding a holding and move it from its edit sheet; net worth sums all.

## Scope

- Data: `portfolios` table (UUID id, name, sort order); `portfolio_holdings.portfolioId` (FK, required). There is
  always at least one portfolio: the migration and fresh installs create "Mi cartera"; the last one can't be
  deleted. Names trimmed, non-blank, ≤ 40 chars, unique ignoring case.
- Room v10 → v11 (manual migration: create table, insert "Mi cartera", assign every holding) + migration test.
- Backup v11 with portfolios and each holding's portfolio; older files import into a single "Mi cartera".
  Clear-all leaves one empty "Mi cartera".
- Repository: observe, create, rename, delete (moving holdings to a target portfolio in the same transaction),
  move a holding.
- UI: selector (hidden behind a "Nueva cartera" action while there is only one portfolio), management (rename,
  delete with move target), add-holding portfolio picker (defaults to the selected one), move from the edit
  sheet, list/total/detail filtered by the selection ("Todas" = everything), remembered selection.
- Position detail weight: within its portfolio (and in the total when there are several).

Out of scope: reordering portfolios by drag, per-portfolio colors/icons, portfolio targets/goals.

## Tasks

- [x] **T1 — Data: portfolios table, Room v11 migration, repository, backup v11, seeding/clear-all**
  (route: delegated writer).
- [x] **T2 — UI: selector, management, add/move, filtered list/total/details** (route: delegated writer).
- [x] **T3 — Docs** (route: inline): README *Cartera* and backup sections.

## Acceptance criteria

- Upgrading keeps every holding, now inside "Mi cartera"; nothing changes in net worth.
- Holdings can be created in, moved between and listed by portfolio; totals per portfolio add up to the total.
- Deleting a portfolio never deletes holdings; the last portfolio can't be deleted.
- Backups round-trip portfolios; older backups import into "Mi cartera".

## Checks

- `scripts/check.sh` (ktlint, unit tests, R8 preview build, schema committed)
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator: migrate the current v10 DB, create/rename/delete/move, details per portfolio.

## Progress / evidence

- **T1** (commit below, `feat(portfolio): store holdings in separate portfolios`): `Portfolio` + new
  `PortfolioCollectionRepository` (observe, create, rename, delete with move in one transaction, move holding);
  names normalized (≤ 40) and unique ignoring case in Kotlin (SQLite NOCASE folds ASCII only);
  `MIGRATION_10_11` (manual, `MIGRATION_3_4` style): `portfolios` + "Mi cartera" (SQL v4 UUID), holdings
  rebuilt with `portfolioId` NOT NULL, FK `RESTRICT`, index, `foreign_key_check`; `ensureDefaultPortfolio()`
  in first-run seeding, clear-all and import; backup v11 (older → one "Mi cartera"); `portfolios` in
  `BACKED_UP_TABLES`. Writer deviation accepted: NOT in cloud `RECORD_KEYS` (an always-present default row
  would defeat the "local data emptied" guard). RED: 525 compile errors; GREEN (parent re-run): check.sh
  "All checks passed." (1055 tests), iOS compile.
- **T2** (commit below, `feat(portfolio): manage several portfolios in the Cartera tab`): selection persisted in
  preferences (`selected_portfolio_id`, null = Todas, falls back when gone); chip row `Todas · … · + Nueva` with
  `Gestionar ▾` (single portfolio: `Nueva cartera` link, delete disabled); flat Todas list with a muted portfolio
  line; create/rename/delete dialogs with inline Spanish errors and move target; `Cartera` pickers in add
  (quoted + manual) and edit; `PortfolioDetail(portfolioId?)`; position weight `en «X» · y % del total`.
  RED: 213 compile errors; GREEN 1114 tests. Parent: check.sh green; emulator over the real v10 DB → v11
  ("Mi cartera" with the 3 holdings, broker kept); duplicate and blank name errors; created "Jubilacion",
  moved Vanguard there (weight 100 % · 47,6 % del total; Mi cartera 14.255,32 € = 27.207,52 − 12.952,20);
  Todas lists portfolio names; deleted "Jubilacion" moving its holding back → nothing lost, snapshot
  39.707,52 € unchanged.
- **T3** (commit below, `docs: document multiple portfolios`): README *Cartera* (several portfolios) and backup v11.

## Next step

Done; push and PR (stacked on #31 while it is open).
