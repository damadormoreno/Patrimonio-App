# Portfolio holdings in account groups (and goals through groups)

**Locator:** `odd/tasks/holdings-in-groups.md` · Engram topic `odd/holdings-in-groups/tasks`
**Branch:** `feat/holdings-in-groups` (from `main` @ `01fab26`)
**Delivery strategy:** `single-pr` (user preference; no line budget)
**RDD:** off (global) → parent verification after each writer.

## Objective

Let funds, ETFs, stocks and crypto from the portfolio be members of account groups, so a savings goal linked to
a group counts them (e.g. goal «Casa» = group with a savings account + a money-market fund + an ultra-short bond
fund). Also show the portfolio's products in Patrimonio like the other categories.

## Decisions (user, 2026-10-12)

1. Holdings join **groups**; goals keep linking to an account or a group (no new goal ↔ holding link).
2. Patrimonio shows the portfolio's products like the other categories (expandable Cartera card with a row per
   product).
3. After this feature: redesign the Cartera tab layout (too cramped) — separate feature.

## Findings (explorer map, 2026-10-12)

- Groups and goal progress **never feed net worth** (net worth = assets − liabilities + portfolio total, computed
  once in `PatrimonioViewModel`, `NetWorthSnapshotUpserter`); group totals (`groupTotal`) and goal progress
  (`trackedBalance`, goal currency) are per-group/per-goal views → no double counting by design.
- `account_group_members(groupId, assetId)` with FK to `assets`; assets may be in several groups; the built-in
  «Todas las cuentas» group (`ALL_ACCOUNTS_ID`, members = null) is synthesized, never a row.
- Backup imports members **before** portfolios/holdings; clear-all deletes members before holdings.
- Usage tags (`accountUsage`, folder/goal icons, `AccountFilter`, delete warning) are keyed by asset id.
- `PortfolioRepositoryImpl.delete` has no usage warning; `PortfolioHolding.value()` is null when unpriced.

## Design

- **Schema v15**: new table `account_group_holding_members(groupId FK → account_groups CASCADE, holdingId FK →
  portfolio_holdings CASCADE, PK (groupId, holdingId), index holdingId)` — keeps the asset FK intact. A holding
  may be in several groups (same rule as assets). `AutoMigration(14, 15)`.
- **Domain**: `AccountGroup.memberHoldingIds: Set<String>` (empty for the built-in group, which stays "all
  accounts" only); repository insert/update set both member sets atomically; a group needs ≥ 1 member of either
  kind.
- **Values**: `groupTotal` (EUR) and `trackedBalance` (goal currency) include member holdings' `value()`
  converted with the same rates; an **unpriced** holding counts as 0 (it has no value yet) rather than making the
  goal "unavailable" — document it.
- **Usage**: holding usage (groups/goals through groups) for the holding detail and the delete warning;
  deleting a holding removes its memberships (CASCADE) after a warning naming the groups (and the goals linked to
  them).
- **Backup v15**: `accountGroupHoldingMembers` section, validated (group and holding exist, unique pairs),
  imported **after** holdings; older backups import without it; `BACKED_UP_TABLES`; clear-all order.
- **UI**:
  - Group form checklist: a «Cartera» section listing products (name, portfolio, value) next to the accounts;
    selected total includes them.
  - Group list/rows: member holdings shown with the portfolio look and their EUR value.
  - Patrimonio: the Cartera card expands to one row per product (name, value, folder/goal icons like asset
    rows); tapping a row opens the product detail.
  - Product detail: «En el grupo X» / «Cuenta para la meta Y» line when applicable; delete warning.

## Tasks

- [x] **T1 — Persistence + domain**: table/migration v15, DAO, `AccountGroup.memberHoldingIds`, repository,
  holding delete cascade, backup v15 (+ import order), clear-all (route: delegated writer).
- [x] **T2 — Values + usage (pure)**: `groupTotal`, `trackedBalance`, holding usage, delete warning data
  (route: delegated writer).
- [x] **T3 — UI**: group form/list, Patrimonio Cartera rows, product detail usage + delete warning (route:
  delegated writer).
- [x] **T4 — Docs + manual test** (route: inline).

## Acceptance criteria

- A goal linked to a group with an account + two funds tracks the sum (goal currency) and updates with prices.
- Net worth is unchanged by adding holdings to groups.
- Deleting a holding in a group warns and leaves the group consistent; backups round-trip memberships.

## Checks

- `scripts/check.sh`, iOS cross-compile, emulator test on the user's data (export + pull the backup to the host
  first, restore after).

## Progress / evidence

- **T1** (commit below, `feat(groups): let account groups hold portfolio products`): Room v15
  `AutoMigration(14, 15)` (`account_group_holding_members`, both FKs CASCADE, `15.json`); DAO relation +
  insert/list/delete; `AccountGroup.memberHoldingIds` (built-in group: empty); `insertGroup`/`updateGroup`/
  `setMembers(groupId, assetIds, holdingIds)` transactional, unknown holding → `PortfolioHoldingNotFoundException`
  with nothing written; `groupsContainingHolding` (one-shot, for the delete warning); backup v15 section imported
  after holdings, validated; clear-all order; `BACKED_UP_TABLES`; cloud `RECORD_KEYS` unchanged (members are not
  records, like asset members). RED: unresolved references; GREEN: check.sh (1954 tests), iOS compile; parent
  re-run of migration/group/backup tests green.
- **T2** (commit below, `feat(groups): count portfolio products in group totals and goals`): `groupHoldings`,
  `groupTotal(..., holdings)`, `trackedBalance(..., holdings)` (unpriced = 0 and skipped before conversion;
  missing/unusable rate for a priced foreign holding → null, as for assets), `holdingUsage` (groups + goals via
  groups, built-in excluded); `GruposViewModel` (observes holdings, `onHoldingToggle`/`selectedHoldingIds`, editing
  keeps holding members) and `SavingsGoalsViewModel` wired; regression: net worth identical with holdings in groups.
  RED: compile errors; GREEN: check.sh (1986 tests), iOS compile; parent re-run of the touched suites green.
- **T3** (commit below, `feat(groups): show portfolio products in groups, Patrimonio and their detail`): group form
  «Cartera · Qué productos incluir» section (portfolio name when several, «sin precio», usage tags, search covers
  both lists, footer «1 cuenta y 2 productos seleccionados»); group member rows include products (tap → detail);
  Patrimonio Cartera card expandable with product rows (closed last, folder/spark icons, tap → `PositionDetail`,
  «Abrir cartera» link; assignment filters narrow products, a type filter hides the card); product detail usage
  line and delete warning naming groups/goals. Writer note: for products, «En una meta» also covers goals that
  follow the product's group (accounts don't get that). RED not observed by running (tests written first, compile
  failures by construction); GREEN: check.sh (2021 tests), iOS compile.
- **T4** (commit below, `docs: document products in groups`): README. Manual test on the user's emulator data
  (exported to `pre-grupos.json` and pulled to the host first; the install migrated v14 → v15 intact): group «Casa»
  = Cuenta corriente + Amundi + iShares Dev → 26.484,22 € (= 12.500 + 9.325,87 + 4.658,35); net worth unchanged
  (44.458,82 €); Patrimonio Cartera card with folder icons on the grouped funds; Amundi detail «En el grupo Casa»
  and delete warning «Está en el grupo «Casa»; se quitará de él.» (cancelled); goal «Entrada piso» 40.000 € linked
  to the group → 26.484,22 € «Faltan 13.515,78 €». Restored the backup: every table identical (fundamentals cache
  timestamp excluded), new table empty.

## Next step

Done; push + PR. Then the Cartera tab redesign (separate feature).
