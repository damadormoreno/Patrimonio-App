# Portfolio (phase 1): replace subscriptions with an investment portfolio tab

**Locator:** `odd/tasks/portfolio-holdings.md` · Engram topic `odd/portfolio-holdings/tasks`
**Branch:** `feat/portfolio-holdings` (from `main` @ `02628bb`)
**Delivery strategy:** `single-pr` (user decision: no size-based splitting; lines as needed)
**RDD:** off (global) → after each work-unit commit run `gentle-ai review assess` and follow the tier.

## Objective

Remove the "Suscripciones" feature entirely and add a "Cartera" tab where the user builds an investment
portfolio: search funds/ETFs/stocks by ISIN, ticker or name, add them with the units held (or the current
total), and see them valued at the latest price. The portfolio total counts as its own block in net worth.

## Problem / why

The user prefers tracking investments over subscriptions. Prices for Spanish/European funds come from the
same public endpoints the open-source app rumbo (MIT, github.com/danidm98/rumbo) uses. The app is for
personal use for now and may be published later, so the price source must be swappable.

## Scope (phase 1, approved)

- Delete subscriptions: domain, data, Room table (`subscriptions`), renewal notifications (WorkManager,
  `UNUserNotificationCenter`), Settings "Avisos" section, notification permission, `work-runtime`,
  backup section, cloud record key, tests and README/docs references.
- Old backups that contain `subscriptions` still import (the section is ignored).
- Cartera tab replaces the Suscripciones tab.
- Holdings: instrument (name, ISIN/ticker, provider + provider code, currency, type), units held, latest
  price + price date. Entry by units, or by current total (units = total / current price). Manual holdings
  (user-entered price/value) when no quote is available.
- Quote source behind a port: Morningstar adapter (ISIN screener + NAV time series, EUR) and Yahoo
  adapter (search + chart), plus manual. Retry with backoff; keep the last known price on failure.
- Prices refresh on app start when stale and on demand.
- Net worth: portfolio total (EUR) is added to assets in the current-month snapshot and in the
  Patrimonio screen totals; composition shows a "Cartera" share. Not linkable to goals/groups yet.
- Backup and "clear all data" include holdings.

Out of scope (later phases): movements (buy/sell/fees/dividends), FIFO cost, traspasos, price history
charts, XIRR/TWR, broker imports, crypto.

## Constraints

- `Money` is minor units without currency; `Currency` is a closed enum (EUR, USD, GBP, JPY). Units need a
  scaled `Long` (precedent: `RATE_SCALE = 1_000_000`); value math must be overflow-safe and tested.
- DB is v6: removal ships as v7 (drop `subscriptions`), holdings as v8, each with a migration test.
- Backup format bumps with each data change; files with a higher version are rejected, older ones import.
- Morningstar/Yahoo endpoints are unofficial (Morningstar path tokens `t92wz0sj7c`, `klr5zyak8x`; keep
  `]` and `$` unescaped). Isolate them in adapters; never let a failure break the screen.

## Tasks

- [x] **T1 — Remove subscriptions** (route: delegated writer; trigger: many non-trivial files).
  DB v7 migration dropping the table + migration test; backup format bump, legacy files still import.
- [x] **T2 — Quote source port + Morningstar/Yahoo adapters** (route: delegated writer).
  Search (ISIN/ticker/name) and latest price; MockEngine tests for parsing, ISIN→SecId, failures.
- [x] **T3 — Holdings persistence** (route: delegated writer). Domain model with scaled units, Room v8 +
  migration test, repository, price refresh policy, backup/clear-all/cloud keys.
- [x] **T4 — Net worth integration** (route: delegated writer). Snapshot upserter + Patrimonio totals and
  "Cartera" composition share.
- [x] **T5 — Cartera tab UI** (route: delegated writer). List with total and per-holding value/price date,
  add sheet (search → pick → units or total; manual mode), delete, refresh.
- [x] **T6 — Docs** (route: inline). README roadmap/backup sections.

## Acceptance criteria

- No subscription code, table, notification or setting remains; app builds and migrates a v6 DB with
  subscriptions to the new version without losing other data.
- A fund can be found by ISIN and an ETF by ticker, added by units or total, and valued in EUR.
- Net worth and the monthly snapshot include the portfolio total; composition shows "Cartera".
- Price fetch failures keep the last price and show its date; the app never crashes on bad responses.

## Checks

- `./gradlew :composeApp:ktlintCheck`
- `./gradlew :composeApp:testDebugUnitTest`
- `./gradlew :composeApp:assembleDebug`
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator (iOS not available locally).

## Delivery

One branch, one PR. The ~400-line heuristic does not apply (user decision, 2026-10-11).

## Progress / evidence

- **T1** (`ad06ce6`, `feat!: remove the subscriptions feature`): 27 files deleted (domain, data, UI,
  reminders, notification permission, 6 subscription-only tests); `MIGRATION_6_7` drops `subscriptions`
  (schema `7.json`); backup format v7, v3–v6 files still import (section ignored via `ignoreUnknownKeys`);
  Settings "Avisos" and `androidx.work` removed; single tab hides the tab bar until Cartera lands.
  RED: `AppDatabaseMigrationTest` + `BackupCodecTest` → 32 tests, 3 failed (6→7 migration, v6 import,
  v7 envelope). GREEN (parent re-run): ktlint, `testDebugUnitTest` 463/463, `assembleDebug`,
  `compileKotlinIosArm64` (executed) all pass. Risk treated as high (migration + deletions); RDD off →
  parent independent re-run as verification. Orphan DataStore keys `renewal_reminders_*` left harmless.
  Manual on-device v6→v7 migration pending (done at the end over v6 → final version).
- **T2** (commit below, `feat(portfolio): add quote sources for funds, ETFs and stocks`): `QuoteSource` port +
  `CompositeQuoteSource` over `MorningstarApi` (screener + 30-day timeseries, EUR, literal `]`/`$` in `id`) and
  `YahooFinanceApi` (search + chart, GBp→GBP); `Price` scaled ×1e6 with overflow guard; `QuoteHttp` with
  rumbo's User-Agent, 3 attempts with linear backoff on 429/5xx/network, per-request 15 s/30 s timeouts
  (parent moved them off the shared client so FX/cloud keep no limits). Yahoo search candidates carry no
  currency until priced (T5 fetches the quote on pick); non-enum currencies → `QuoteUnavailableException`.
  RED: 47 tests, 39 failed against `TODO()` stubs (PriceTest had no RED: written before stubs).
  GREEN (parent re-run): ktlint, 511 tests, assemble, iOS compile executed. Writer did two manual curl
  calls to the live Morningstar timeseries (literal and encoded `]`/`$` both work); no network in tests.
- **T3** (commit below, `feat(portfolio): persist holdings and refresh their prices`): `PortfolioHolding` with
  sealed `HoldingValuation` (`Quoted(ref, currency, units, lastQuote?)` / `Manual(value, valuedAt)`); `Units`
  ×1e6; `mulDivHalfEven` exact 128-bit fixed point (parent reviewed: carries, MIN_VALUE, round-up overflow);
  table `portfolio_holdings` via `AutoMigration(7, 8)` (`8.json`); `PortfolioRepository.refreshPrices`
  (stale > 6 h or never, `Semaphore(4)`, failures keep last price, currency mismatch = failure) fired on
  start from `initKoin`; backup v8 with `portfolioHoldings` (v1–v7 import empty, v9 rejected); clear-all,
  `BACKED_UP_TABLES` and cloud `RECORD_KEYS` include the table (price refreshes trigger cloud auto-upload).
  RED: calc+migration 37 tests/29 failed; persistence/backup 102 tests/30 failed (DAO test had no RED).
  GREEN (parent re-run): ktlint, 572 tests, assemble, iOS compile.
- **T4** (commit below, `feat(portfolio): count the portfolio in net worth`): `NetWorthSnapshotUpserter` adds
  `portfolioTotalEur` to `assetsMinor`; `PortfolioRepositoryImpl` refreshes the current-month snapshot inside
  its write transactions (and once per price refresh that stored prices); `PatrimonioViewModel` includes
  the portfolio in Activos and net worth, adds a "Cartera" composition share (`TypeColor.GREEN`, shared with
  "Inversión" since all 8 colors are taken) and exposes `portfolio: PortfolioSummaryUi?` for T5; ACTIVOS is
  no longer empty with only holdings. Clear-all already wipes snapshots (`netWorthDao().deleteAll()`).
  RED: 53 tests, 15 failed (upserter, repository, ViewModel). GREEN (parent re-run): ktlint, 591 tests,
  assemble, iOS compile.
- **T5** (commit below, `feat(portfolio): add the Cartera tab`): `TabKey.CARTERA` (icon `trend`) as second tab;
  `CarteraScreen` (total, rows, "Actualizar" + pull-to-refresh, failure banner), `AddHoldingSheet` (400 ms
  debounced search, pick → quote, `Participaciones | Importe actual` with live previews, manual fallback and
  "Añadir manualmente"), `HoldingDetailSheet` (edit units/value, delete with confirmation); "Cartera" card on
  Patrimonio switches tab; `parseUnitsEs`/`formatUnits`. RED: 79 tests, 74 failed against stubs.
  GREEN (parent re-run): ktlint, 663 tests, assemble, iOS compile.
- **Manual test (Android emulator, Pixel API 35, live network)**:
  - Built `main` @ `02628bb` (DB v6), created an account and a subscription ("Netflix"), installed this
    branch over it: DB migrated to v8 on device (`user_version` 8, identity hash = `8.json`), no
    `subscriptions` table, account and October snapshot intact.
  - Tabs Patrimonio · Cartera; Settings has no "Avisos".
  - ISIN `IE00B03HCZ61` → Morningstar fund, live NAV 64,761 € (09/10/2026); 154,321 units → 9.993,98 €.
    `IWDA` → Yahoo `IWDA.AS`, amount mode 5.000 € → 37,973723 units. Manual "Plan de pensiones" 3.200 €.
  - "Actualizar" refreshed both quoted prices without errors. Net worth 12.500 + 18.193,98 = 30.693,98 €
    on screen and in the snapshot; composition "Cartera 59%"; Cartera card opens the tab.
  - Detail: units 154,321 → 200 (12.952,20 €); delete manual with confirmation; snapshot followed.
  - Export v8 (holdings, no `subscriptions`) → clear all → import: every table identical.
  - Defect found: Yahoo float noise (131,669998 €) → fixed in `e0f5646`
    (`fix(portfolio): drop float noise from Yahoo prices`, RED→GREEN `QuoteMappingTest`, 664 tests),
    verified on device (131,67 €).
  - Not tested: iOS (no macOS; the CI iOS job is the real check).
- **T6** (commit below, `docs: document the Cartera tab`): README intro/stack/structure, new *Cartera*
  section (sources, unofficial endpoints, refresh, limits), backup v8, roadmap.

## Next step

Done. Push and PR are the user's decision.
