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
4. (2026-10-12, after PR #42 opened, user request) A goal may follow **any mix of accounts, portfolio products and
   groups** (several of each), not "accounts XOR one group". Its balance is the union without double counting (an
   account selected directly and also inside a selected group counts once). Delivered in the same PR #42.

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
- [x] **T5 — Goals with mixed links: persistence + domain + values**: schema **v16** (not v15: the emulator, and
  possibly the user's phone via the PR preview, already run v15) — goal ↔ holdings and goal ↔ groups (several)
  tables, migrate `linkedGroupId` into the groups table, link events for holdings/groups, `trackedBalance` as a
  deduplicated union, sharing notices on effective overlap, usage (direct + via groups), deletes write events,
  backup v16 (route: delegated writer).
- [x] **T6 — Goals with mixed links: UI**: goal form with Cuentas / Cartera / Grupos sections, captions, delete
  warnings (route: delegated writer).
- [ ] **T7 — Docs + manual test** (route: inline).

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
- **T5** (commit below, `feat(goals): follow any mix of accounts, products and groups`): manual `MIGRATION_15_16`
  (new `savings_goal_holdings`/`savings_goal_groups`, `linkedGroupId` copied then the goals table rebuilt without
  it, link events gain `fromHoldingId`/`toHoldingId`, `PRAGMA foreign_key_check`); `SavingsGoal` with three link
  sets (XOR removed); events: removals then additions, `HOLDING_DELETED`, group swap = UNLINK + LINK; repository
  validates added ids atomically; `trackedBalance` = deduplicated union (direct + via groups); sharing notices on
  effective overlap; `accountUsage`/`holdingUsage` count goals direct or via groups; backup v16 (older files map
  `linkedGroupId` to one group). RED not observed by running (tests written first); GREEN: check.sh (2062 tests),
  iOS compile. Parent mutation check: dropping the dedup (`distinctBy`) fails 2 tests; parent re-run of goal,
  migration and backup suites green.
- **T6** (commit below, `feat(goals): pick accounts, products and groups for a goal`): goal form section «Seguir el
  saldo de (opcional)» with independent Cuentas / Cartera / Grupos checklists (product rows reuse the Cartera
  badge; usage tags exclude the edited goal), hint when nothing is picked (manual goal), live footer «Sigue N
  cuentas, M productos y K grupos · total X € (sin contar dos veces lo que se repite)» using `trackedBalance` on a
  probe goal; captions name up to 3 targets, else counts per kind; sharing notices classify ids; Spanish save
  errors. No UI renders link history today, so no history texts were needed. RED not observed by running; writer
  mutations (overlap flag forced false; group toggle clearing accounts) fail 7 tests; GREEN: check.sh (2079
  tests), iOS compile.

## Next step

T7 (docs + manual test), then update PR #42. Then the Cartera tab redesign (separate feature).
