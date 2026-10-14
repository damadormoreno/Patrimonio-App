# Feature: optional EODHD API key (bring your own key)

## Objective

Let the user enter their own EODHD API key so funds, ETFs and stocks have a licensed price source. By default EODHD
is a **fallback** used only when the holding's own (unofficial) provider fails; a switch makes it the **primary**
source for paid keys. Without a key the app behaves exactly as today.

## Problem / why

- Prices come from unofficial endpoints (Morningstar, Yahoo). If one breaks, holdings keep a stale price: there is no
  fallback (`CompositeQuoteSource` routes by `InstrumentRef.provider` only).
- Public release stays free with the current sources (owner's decision); EODHD is the user's own licensed option.
- Free EODHD plan: 20 calls/day, about 1 year of history. Paid plans: full history, larger quota.

## Decisions (owner)

- Order: unofficial first, EODHD as fallback (B), with a switch «Usar EODHD como fuente principal» for paid keys.
- No in-app tip link; nothing else monetized.

## Design

- **Overlay, not a new provider:** holdings keep their `QuoteProvider` (no schema or backup-format change). A decorator
  around `CompositeQuoteSource` adds EODHD for `latestQuote` / `priceHistory` of MORNINGSTAR and YAHOO refs; COINGECKO
  refs never use EODHD. `search` is unchanged.
- **Identity:** the ref alone has no ISIN, so a lookup maps `InstrumentRef` → the holding's ISIN / ticker (from
  `portfolio_holdings`). Symbol resolution: ISIN → `/api/search/{ISIN}` (funds resolve as `{ISIN}.EUFUND`), else
  ticker → Yahoo suffix mapping or `/api/search/{ticker}`. Resolved symbols are cached (DataStore) so each instrument
  costs one search call once.
- **Endpoints (verified 2026-10-14):** `GET /api/eod/{SYMBOL}?api_token=K&fmt=json&from=YYYY-MM-DD` → `[{date, open,
  high, low, close, adjusted_close, volume}]`; latest quote = last row of a short window (`real-time` gives no date for
  funds). `GET /api/user?api_token=K&fmt=json` → `{apiRequests, dailyRateLimit, subscriptionType, ...}` does not
  consume quota → used to validate the key and show usage. Invalid key → HTTP 401 «Unauthenticated».
- **Quota:** no retries for EODHD on 402/429 (treat as daily quota exhausted: skip EODHD until the next UTC day and go
  to the other source); 401 → key invalid state.
- **Key storage:** own DataStore file `patrimonio-datasources.preferences_pb`, excluded from Android backup and device
  transfer (`backup_rules.xml`, `data_extraction_rules.xml`); never in `BackupDocument`. The key is never logged nor
  put in exception messages (it travels in the URL).
- **History:** shorter EODHD windows merge with stored history (REPLACE upsert, incremental sync); use `close` (NAV),
  not `adjusted_close`. Currency mismatch rejected as today; GBX handled like `yahooPrice`.

## Tasks

- [x] T1 — `EodhdApi` adapter (search by ISIN/ticker, eod history, latest quote, user/usage) + error mapping (401
  invalid key, 402/429 quota, no retries) + key-free messages. MockEngine tests. Route: delegated (writer).
- [x] T2 — Data-source settings store (key, primary switch, quota-exhausted day, symbol cache) in its own DataStore
  file + Android backup exclusions + Koin wiring. Tests. Route: delegated (same writer).
- [x] T3 — Fallback/primary decorator over `CompositeQuoteSource` + `InstrumentRef` → ISIN/ticker lookup + symbol
  resolution with cache. Tests for both orders, crypto bypass, quota skip, invalid key, no key. Route: delegated.
- [x] T4 — UI: Ajustes → «Fuentes de datos» (key entry masked, validate with `/api/user`, usage «n de 20 llamadas
  hoy», primary switch with explanation, remove key) + About data source EODHD + privacy policy (web) update with new
  date + README/docs. Route: delegated (writer).
- [x] T5 — Verification: `scripts/check.sh`, iOS cross-compile, emulator with EODHD's public `demo` key (the owner's
  real key is never typed by the agent), backup of the owner's emulator data first.

## Acceptance criteria

- No key → identical behaviour to today (tests prove the decorator is a pass-through).
- Fallback order: provider failure → EODHD price; primary order: EODHD first, provider on EODHD failure.
- Quota exhausted / invalid key never block refresh; the other source is used.
- The key never appears in logs, exceptions, exports or cloud backup.
- `scripts/check.sh` green; iOS compiles.

## Delivery

- Forecast ~1,200 authored lines. Strategy: single PR (owner's standing preference: one PR per feature), work-unit
  commits per task.

## Progress

- Branch `feat/eodhd-key` from `origin/main` (d5af3ac). RDD off (global).
- T1–T3 done (one delegated writer, sonnet). Commits `54d72da` (T1 client), `906a764` (T2 store), `c63d1d3` (T3
  decorator + wiring). Evidence: RED = compile failures before each implementation; GREEN EodhdApiTest 19/19,
  DataStore store 12/12, EodhdQuoteSourceTest 35/35, RoomInstrumentIdentityLookupTest 3/3, KoinModuleTest 20/20;
  parent re-ran EodhdApiTest + EodhdQuoteSourceTest: green; `scripts/check.sh` green; no Room schema change.
  - Key safety: one request per call, messages carry path + status only, no cause attached (Ktor and serializer
    messages quote URL/body); the shared HttpClient has no logging plugin.
  - Choices: 403 is not «invalid key» (EODHD `demo` key gets 403 on `/search`); decorator also rejects a quote in a
    currency different from the holding's (falls to the delegate); empty full history counts as «unknown».
- **iOS compile:** plain `compileKotlinIosArm64` is SKIPPED on Windows (false green, as the writer ran it); the real
  klib compile needs `-Pkotlin.native.enableKlibsCrossCompilation=true`. With it, T2 failed (reified
  `encodeToString` unresolved on Kotlin/Native) → fixed with an explicit `MapSerializer` (commit `fix(quotes)`), iOS
  klib compile green. CI `ios` job dispatched on macOS as a second check (run 38576327875).

- T4 done (delegated writer, sonnet), commit `ff2e113`: «Fuentes de datos» section + `DataSourcesViewModel`
  (19 tests, RED = compile failure), About EODHD entry, privacy policy EODHD paragraph (EODHD privacy URL answered
  200), README bullet. The key field is a private `ApiKeyField` (shared `TextField` has no keyboard options).
- T5 done (parent):
  - Emulator (Pixel API 35, preview build; the emulator snapshot had no app data, so nothing of the owner's was at
    risk): fake key → «EODHD no reconoce esta clave»; public `demo` key → saved, masked, usage line shown; switch
    disabled without key, enabled with key, subtitle changes; «Quitar clave» asks, then resets key and switch.
  - Found on the emulator: usage numbers were ungrouped («10000000») → test changed first (RED: `100[]000`), then
    `groupThousands` (GREEN).
  - `scripts/check.sh`: first run 1 failure in `CloudBackupSyncTest` (unrelated, passed 3/3 in isolation and in the
    next full run: flaky, follow-up task suggested); second run «All checks passed».
  - iOS klib compile with `-Pkotlin.native.enableKlibsCrossCompilation=true`: green. CI on macOS (run 38576327875):
    iOS and Android jobs green.
- [x] T4, [x] T5.

## Next step

PR for review; owner redeploys the website (privacy policy now mentions EODHD).
