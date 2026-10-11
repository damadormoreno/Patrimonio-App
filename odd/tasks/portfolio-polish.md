# Portfolio polish before phase 2

**Locator:** `odd/tasks/portfolio-polish.md` · Engram topic `odd/portfolio-polish/tasks`
**Branch:** `fix/portfolio-polish` (from `main` @ `e16e756`)
**Delivery strategy:** `single-pr` (user preference)
**RDD:** off (global) → parent verification after the writer.

## Objective

Close the small pending items found while building the portfolio, before phase 2 (movements) starts touching the
same screens.

## Decisions (user, 2026-10-11)

- Do the pending improvements first; phase 2 design is explored in parallel (read-only) and proposed afterwards.
- Longer crypto history through Yahoo is NOT included (needs a coin ↔ Yahoo symbol mapping decision).

## Scope

1. **Asset class in the Cartera list**: a compact muted label per row (effective class; nothing for unclassified
   or show `Sin clasificar` — pick the least noisy, justify).
2. **Colors**: the asset-class palette (`assetClassColor`) must be distinguishable for common color-vision
   deficiencies (check pairwise contrast/ΔE under deuteranopia/protanopia simulation, in light and dark themes);
   the Patrimonio composition "Cartera" share must not share its color with "Inversión" (today both GREEN).
3. **Patrimonio composition header**: "N grupos" must not count the Cartera share as an account group.
4. **Latent bug**: `AccountGroupDao.insertGroup` uses `OnConflictStrategy.REPLACE`; replacing an existing group row
   would fire the `SET NULL` FK and silently unlink savings goals. Make inserting an existing id fail (ABORT) or
   switch edits to an explicit update — never a delete+insert. Test it.

## Tasks

- [x] **T1 — All four items** (route: delegated writer; 2+ non-trivial files).

## Checks

- `scripts/check.sh` (ktlint, unit tests, R8 preview build, schema committed)
- `./gradlew :composeApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`
- Manual on the Android emulator: Cartera list labels, portfolio detail colors (light/dark), Patrimonio header.

## Progress / evidence

- **T1** (commit below, `fix(portfolio): polish asset classes, colors and group inserts`):
  1. Cartera rows show a class dot + label (nothing for unclassified, to avoid noise).
  2. CVD-checked palette: Machado 2009 (protan/deutan) + CIEDE2000; old palette failed 26 pairs (worst 1,7:
     fixed income vs unclassified, light deutan); new class tokens worst pair ≥ 12,6 (light) / ≥ 11 (dark);
     new `chartPortfolio` token for Cartera (≥ 15 from every account color), no longer Inversión's green.
  3. Composition header "N grupos · Cartera" (also fixes "1 grupos").
  4. `AccountGroupDao.insertGroup` → `ABORT` (REPLACE deleted the row and SET NULL'd goal links); tests prove a
     duplicate insert fails without touching links and UPDATE keeps members/links. Side effect: a backup with
     duplicate group ids now fails import (rolled back) instead of silently replacing.
  RED: 9 failures (palette, Cartera color, labels, header, DAO); GREEN (parent re-run): check.sh (1314), iOS.
  Emulator: "1 grupo · Cartera", Cartera navy (light); dark: class labels in rows, class bar readable.

## Next step

Done; push + PR.
