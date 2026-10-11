# Asset classes and crypto (CoinGecko)

**Locator:** `odd/tasks/asset-classes-crypto.md` · Engram topic `odd/asset-classes-crypto/tasks`
**Branch:** `feat/asset-classes-crypto` (stacked on `feat/instrument-preview`, PR #33; retarget to `main` once
#33 merges)
**Delivery strategy:** `single-pr` (user preference: no size-based splitting)
**RDD:** off (global) → parent verification after each writer.

## Objective

Show the portfolio split by asset class (e.g. 80 % renta variable · 10 % renta fija · 5 % oro · 5 % cripto) and
support crypto holdings priced through CoinGecko.

## Decisions (user, 2026-10-11)

- Asset class per holding with 8 classes: Renta variable · Renta fija · Monetario · Mixto · Oro y materias
  primas · Inmobiliario · Cripto · Otros. Auto-filled from the Morningstar category when possible, always
  editable; Yahoo and manual holdings are classified by the user.
- Crypto through CoinGecko (search, price, history).

## Verified constraints (2026-10-11)

- CoinGecko public API: `/api/v3/search?query=` returns `coins[{id, symbol, name}]`; `/coins/{id}/market_chart`
  with `days=max` is REJECTED for public users ("limited to querying historical data within the past 365 days");
  `days=365&interval=daily` returns 366 points. → crypto history covers the last year only.
- Mixed funds count entirely as "Mixto" (no look-through split).
- `UNITS_SCALE` and `PRICE_SCALE` are 1e6: fine for BTC/ETH; coins priced below a cent lose precision (out of
  scope; would need a scale migration).

## Scope

- Data: `AssetClass` enum; nullable `assetClass` on holdings (explicit user choice); effective class =
  explicit ?: inferred from the Morningstar category (`RV…` → equity, `RF…` → fixed income, monetarios, mixtos,
  materias primas, inmobiliario) ?: crypto for crypto holdings ?: unclassified. Room v11 → v12, backup v12.
- Analytics: allocation by asset class (incl. an "Sin clasificar" bucket), also in the preview projection.
- Crypto: `QuoteProvider.COINGECKO`, `InstrumentType.CRYPTO`; search (name/symbol queries), latest EUR price,
  365-day daily history; retries/backoff on 429; class Cripto automatically.
- UI: asset class picker in add (quoted + manual) and edit, prefilled with the inferred class; "Reparto por
  clase de activo" first in the portfolio detail; class mix in the preview projection; crypto results/pill.

Out of scope: look-through of mixed funds, crypto history beyond 1 year, micro-priced tokens precision,
CoinGecko API key.

## Tasks

- [x] **T1 — Asset classes: model, inference, Room v12, backup v12, allocation calc** (route: delegated writer).
- [ ] **T2 — CoinGecko adapter + crypto in search/quotes/history** (route: delegated writer).
- [ ] **T3 — UI: pickers, portfolio detail block, preview projection, crypto results** (route: delegated writer).
- [ ] **T4 — Docs** (route: inline).

## Acceptance criteria

- Existing Morningstar holdings show the inferred class without user action; Yahoo/manual ones can be set.
- Portfolio detail shows the class split per portfolio and for Todas; shares add up to 100 %.
- Bitcoin can be found, previewed (1-year history), added and valued in EUR; it counts as Cripto.

## Checks

- `scripts/check.sh` (ktlint, unit tests, R8 preview build, schema committed)
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator with live data.

## Progress / evidence

- **T1** (commit below, `feat(portfolio): classify holdings by asset class`): `AssetClass` + `inferAssetClass`
  built from 158 real es-ES Morningstar categories (rules in order: Trading → OTHER; Activos Digitales → CRYPTO;
  Inmobiliario; Materias Primas / metales preciosos incl. `RV Sector Oro…` → COMMODITIES; `Mercado Monetario`;
  Mixtos / Fecha Objetivo; Alternativo/Otros/Garantizados → OTHER; RF/Bonos/deuda; RV → EQUITY; whitespace and
  accent tolerant); STOCK → EQUITY; `effectiveAssetClass()` = explicit ?: inferred; Room v12 `AutoMigration`
  (`12.json`), `updateAssetClass`, backup v12, `allocationByAssetClass`, `AddProjection.classAllocationBefore/
  After`. RED: compile errors; GREEN (parent re-run): check.sh (1226 tests), iOS compile.
  Noted (pre-existing, not touched): a `BackupCodecTest` v5 test's `.replace("version": 10…)` never matches.

## Next step

T2 — CoinGecko.
