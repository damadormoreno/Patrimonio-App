# Savings goal: amount remaining and monthly pace

**Locator:** `odd/tasks/savings-goal-remaining.md` · Engram topic `odd/savings-goal-remaining/tasks`
**Branch:** `feat/savings-goal-remaining` (from `main` @ `9e61a29`)
**Delivery strategy:** `ask-on-risk` · forecast ~350 authored changed lines (under the ~400 budget, single PR)
**RDD:** off (global) → after each commit run `gentle-ai review assess` and follow the tier.

## Objective

Show on each savings goal how much is left to reach the target and, when the goal has a target date,
the monthly amount needed to get there.

## Problem / why

Card (`SavingsGoalRow`) and detail (`GoalProgressSummary`) only show "X de Y" and the bar; the user
cannot see the gap or the pace needed.

## Scope (approved copy and placement)

Pure domain function (gets `today`) computes:
- remaining = target − progress, in the goal currency; surplus when progress > target.
- months left = months from today's month through the target-date month, inclusive (same month → 1).
- date passed = target date strictly before today (day precision).
- monthly = ceil(remaining / months) to a whole unit of the goal currency (EUR → euros, USD → dollars, JPY unchanged).

Card (`PatrimonioScreen.kt` → `SavingsGoalRow`):
- Right side of the amount row (11.5sp): `Faltan 3.800,00 €` (muted) · `Superada en 250,00 €` (income) · nothing when exactly reached.
- Below the bar, before the tracked-balance caption (11.5sp, muted, 1 line), only when money is missing and there is a date:
  `≈ 317 €/mes hasta marzo de 2027` or `Fecha superada`.

Detail (`SavingsGoalSheets.kt` → `GoalProgressSummary`):
- `Faltan 3.800,00 €` (12sp, muted, top 4.dp), then `≈ 317 €/mes hasta marzo de 2027` / `Fecha superada` (12sp, muted, top 2.dp).
- Reached: `Meta alcanzada · 100%` (unchanged) when exact; `Meta superada en 250,00 € · 105%` when over (income).

Rules:
- `balanceUnavailable` → no remaining, no pace, no "Fecha superada".
- Closed/cancelled goals → keep remaining/surplus, hide pace and "Fecha superada".
- Fix the `SavingsGoalProgressBar` KDoc (no % is drawn in the amount row).

## Constraints

- `Clock` + `() -> TimeZone` injected into `SavingsGoalsViewModel` like `SubscriptionsViewModel`.
- `SavingsGoalRowUi` currently has no `targetDate`; it must carry the computed result.
- Month names reuse `MONTHS_ES` in `domain/calc/MonthLabels.kt`.

## Tasks

- [x] **T1 — Domain function + tests** (route: delegated writer; trigger: 2+ non-trivial files across T1–T3)
  Tests: no date, date passed, goal exceeded, last month, non-EUR currency (+ exact reach, JPY rounding).
- [x] **T2 — ViewModel wiring** (route: delegated writer): inject clock/zone, compute per row, DI + VM test updated.
- [ ] **T3 — UI copy + KDoc fix** (route: delegated writer): card and detail per scope above.

## Acceptance criteria

- Copy and placement exactly as in Scope.
- Domain tests cover the five requested cases and pass.
- `ktlintCheck`, `testDebugUnitTest`, `compileKotlinIosArm64` pass (CI equivalents).

## Checks

- `./gradlew :composeApp:testDebugUnitTest`
- `./gradlew :composeApp:ktlintCheck`
- `./gradlew :composeApp:compileKotlinIosArm64`

## Progress / evidence

- **T1** (`db9a0e0`, `feat(savings): compute remaining amount and monthly pace for goals`): `savingsGoalPace(...)` in
  `domain/calc/SavingsGoalPace.kt` + `monthOfYearLabelEs` in `MonthLabels.kt`.
  RED: stub returning `Remaining(0, null)` → `SavingsGoalPaceTest` 11 tests, 11 failed.
  GREEN: `SavingsGoalPaceTest` 11/11 passed; `ktlintCheck` clean.
- **T2** (commit `feat(savings): expose goal pace in savings goal rows`): `SavingsGoalsViewModel` takes
  `clock` + `zoneProvider` (Koin `get()`), rows carry `pace: SavingsGoalPace?`; closed goals pass no target
  date; unavailable balance → `null`.
  RED: field stubbed to `null` → `SavingsGoalsViewModelTest` 24 tests, 2 failed (open goal monthly pace,
  closed goal remaining/surplus); the unavailable-balance guard passed trivially against the stub.
  GREEN: `SavingsGoalsViewModelTest` 24/24, `KoinModuleTest` 9/9 passed; `ktlintCheck` clean.

## Next step

T3 — UI copy + KDoc fix.
