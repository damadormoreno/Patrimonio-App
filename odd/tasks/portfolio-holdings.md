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

- [ ] **T1 — Remove subscriptions** (route: delegated writer; trigger: many non-trivial files).
  DB v7 migration dropping the table + migration test; backup format bump, legacy files still import.
- [ ] **T2 — Quote source port + Morningstar/Yahoo adapters** (route: delegated writer).
  Search (ISIN/ticker/name) and latest price; MockEngine tests for parsing, ISIN→SecId, failures.
- [ ] **T3 — Holdings persistence** (route: delegated writer). Domain model with scaled units, Room v8 +
  migration test, repository, price refresh policy, backup/clear-all/cloud keys.
- [ ] **T4 — Net worth integration** (route: delegated writer). Snapshot upserter + Patrimonio totals and
  "Cartera" composition share.
- [ ] **T5 — Cartera tab UI** (route: delegated writer). List with total and per-holding value/price date,
  add sheet (search → pick → units or total; manual mode), delete, refresh.
- [ ] **T6 — Docs** (route: inline). README roadmap/backup sections.

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

_Nothing implemented yet._

## Next step

Start T1.
