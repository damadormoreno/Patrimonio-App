# Portfolio: bank or broker per holding

**Locator:** `odd/tasks/portfolio-broker.md` · Engram topic `odd/portfolio-broker/tasks`
**Branch:** `feat/portfolio-broker` (from `main` @ `26f8044`)
**Delivery strategy:** `single-pr` (user preference: no size-based splitting)
**RDD:** off (global) → parent verification after the writer returns.

## Objective

Let the user record which bank or broker manages each portfolio holding (e.g. Openbank, MyInvestor) when
creating or editing it, before phase 2 (movements) starts.

## Scope

- Optional free-text field "Banco o bróker" on every holding (quoted and manual), trimmed; blank → none.
- Add sheet (both the quoted entry form and the manual form) and detail sheet (edit) show the field.
- No suggestions: plain text field (user decision, 2026-10-11).
- Cartera list rows and the detail sheet show the broker when present.
- Persistence: nullable column on `portfolio_holdings`, Room v8 → v9 (`AutoMigration`), migration test.
- Backup format v9 with optional `broker`; v8 and older files import with no broker; v10 rejected.

Out of scope: suggestions/autocomplete, grouping/filtering the portfolio by broker, linking a holding to an account/asset.

## Tasks

- [x] **T1 — Data: domain field, Room v9, repository update, backup v9** (route: delegated writer; trigger:
  2+ non-trivial files).
- [x] **T2 — UI: add/detail field, broker shown in rows/detail** (route: same delegated writer).

## Acceptance criteria

- A holding can be created and edited with or without a broker; the value survives app restart, backup
  export/import and the v8 → v9 migration (existing holdings get no broker).

## Checks

- `scripts/check.sh` (ktlint, unit tests, R8 preview build, schema committed)
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator: migrate the existing v8 DB, add/edit with broker, export/import.

## Progress / evidence

- **T1** (`feat(portfolio): store the bank or broker of each holding`): `PortfolioHolding.broker` +
  `normalizeBroker` (trim, blank → null, ≤ 60), `AutoMigration(8, 9)` (`9.json`), `updateBroker`, backup v9.
- **T2** (`feat(portfolio): edit the bank or broker of a holding`): shared `BrokerField` in add (quoted and
  manual) and detail sheets; broker-only edits save without restamping a manual value's date; broker on
  its own muted line in rows and in the detail identity block. No suggestions (user decision).
- RED: test sources failed to compile (~45 errors) before production code. GREEN (parent re-run):
  `scripts/check.sh` "All checks passed." (ktlint, 697 tests, R8 preview, schema), iOS compile.
- Manual (emulator): existing v8 DB → v9 on device, holdings intact with no broker; set "MyInvestor" on a
  holding from the detail sheet → shown in the row, units unchanged, persisted.

## Next step

Done. Push and PR are the user's decision.
