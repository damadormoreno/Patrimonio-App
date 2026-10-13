# Release prep: personal beta on Google Play (testing) and TestFlight

**Locator:** `odd/tasks/release-beta.md` · Engram topic `odd/release-beta/tasks`
**Branch:** `chore/release-beta` (from `main` @ `eb2e7b0`)
**Delivery strategy:** `single-pr` (user preference; no line budget)
**RDD:** off (global) → parent verification after each writer.

## Objective

Ship the app to the user's own phones through Google Play testing tracks and TestFlight (personal beta, no public
release yet), with an app icon and the store-required basics. Public release is out of scope: the unofficial quote
sources (Morningstar/Yahoo) would need a licensed source or a bring-your-own-key model first (EODHD trial pending,
2026-10-13).

## Decisions (user, 2026-10-13)

1. Personal beta first: Google Play testing track + TestFlight.
2. Create an app icon (three proposals rendered: A navy + trend arrow, B cream + serif «P» with a trend tick,
   C ink + rising bars — the user picks).
3. Monetization and Ko-fi: later (store rules on external tip links are risky; an in-app «tip» purchase is the safe
   route).

## Audit findings (2026-10-13, explorer + parent checks)

- No launcher icon on Android (no mipmap, no `android:icon`); empty iOS AppIcon set.
- `targetSdk` 35; Google Play requires 36 for new apps and updates since 2026-08-31.
- No release `signingConfig`, no AAB job; `versionName` hardcoded "1.0" (`versionCode` from `VERSION_CODE`).
- iOS: no team, no `PrivacyInfo.xcprivacy`, `UIRequiredDeviceCapabilities` = armv7 (template), `CFBundleName`
  "iosApp", development region `en`.
- Settings footer says data lives only on the device even with cloud backup; CoinGecko attribution missing.
- No privacy policy URL / deletion web page (Play asks for them in the listing / Data safety).

## Tasks

- [x] **T1 — Release config**: targetSdk 36 (+ Android 16 behavior review), release signing from env/secrets,
  signed AAB, version from tag/env, CI job building the AAB on `v*` tags (artifact only), iOS Info.plist fixes,
  `PrivacyInfo.xcprivacy`, `docs/release.md` runbook (keystore, secrets, Play internal testing, Firebase SHA for
  Google sign-in, TestFlight) and `docs/privacy-policy.md` draft (route: delegated writer).
- [x] **T2 — In-app basics**: Settings footer honest about cloud backup, «Acerca de» (version, data sources with
  CoinGecko attribution, open-source/font licenses, privacy policy link when configured), CoinGecko attribution
  where crypto prices show (route: delegated writer).
- [x] **T3 — App icon**: chosen proposal as Android adaptive icon (vector foreground + background, monochrome for
  themed icons), Play Store 512 px PNG, iOS AppIcon 1024 px (route: inline or writer).
- [x] **T4 — Docs + manual check**: README, emulator (icon on launcher, Acerca de), release build locally
  (route: inline).

## Acceptance criteria

- `./gradlew :composeApp:bundleRelease` produces a signed AAB when the signing env vars are set (and fails clearly
  when they are not).
- The app targets API 36 and runs normally on the emulator (edge-to-edge, back navigation).
- Launcher shows the new icon (adaptive, themed); iOS asset catalog filled.
- The runbook lets the user complete Play/TestFlight setup without guessing.

## Checks

- `scripts/check.sh`, iOS cross-compile, release bundle build, emulator.

## Progress / evidence

- **T1** (commit below, `chore(release): sign release bundles and target API 36`): targetSdk 36 (edge-to-edge
  already handled; `enableOnBackInvokedCallback`; no orientation locks; native libs already 16 KB aligned); release
  signingConfig from `RELEASE_*` env vars/properties with a fail-fast guard on release tasks (debug/preview/check
  unaffected); `VERSION_NAME` (default `1.0.0-dev`); `.github/workflows/release.yml` (tags `v*`/dispatch → tests,
  signed AAB + mapping artifacts, keystore deleted always); iOS Info.plist (arm64, `Patrimonio`, `es`),
  `PrivacyInfo.xcprivacy` (added to the Xcode project; library required-reason entries marked uncertain); runbook
  `docs/release.md` and draft `docs/privacy-policy.md`. Outside the surfaces (accepted):
  `androidUnitTest/resources/robolectric.properties` `sdk=35` (Robolectric 4.14.1 rejects targetSdk 36).
  Writer: check.sh green, iOS compile, bundleRelease without vars fails with a clear message, with a throwaway
  keystore → signed AAB verified (`jarsigner`). Parent: guard message confirmed; installed on the emulator →
  `targetSdk=36`, navigation and back work.
- **T2** (commit below, `feat(settings): add an about screen and honest storage note`): `settingsFooterText`
  (cloud wording only when the backup is Active); «Acerca de» row (with the version) → screen with data sources
  (Morningstar/Yahoo «fuente pública no oficial», «Powered by CoinGecko», Frankfurter/BCE; links via
  `LocalUriHandler`), 16 open-source licences incl. Manrope/Instrument Serif OFL and rumbo MIT notices, privacy and
  contact rows hidden until `PRIVACY_POLICY_URL`/`CONTACT_EMAIL` are set (`AboutConfig.kt`); CoinGecko credit in
  the crypto chart note. RED: compile failures; GREEN: check.sh (2141 tests), iOS compile. Parent: emulator shows
  the row with «1.0.0-dev» and the screen sections.
- **T3** (commit below, `feat(app): add the app icon`): the user picked **B**; built from the bundled italic Instrument
  Serif (the app's amount font) with `tools/icon/AppIcon.java` (Java2D): adaptive icon (vector foreground in the
  108 viewport, composition fitted to 46 units so it stays inside the 66 safe circle; paper background; monochrome
  layer for themed icons), `android:icon`/`roundIcon`, iOS `AppIcon-1024.png` (opaque) in the asset catalog, Play
  listing `docs/store/play-icon-512.png`. Parent: previews under circle/squircle/themed masks; emulator launcher
  shows the icon; check.sh green.
- **T4** (commit below, `docs: document the beta release`): README (cloud copy in the intro, release workflow,
  runbook, privacy draft, icon generator, Acerca de config).

## Next step

Push + PR. Then the user: accounts, keystore + `RELEASE_*` secrets, first AAB upload to internal testing,
TestFlight from a Mac (see `docs/release.md`); EODHD trial for fund NAV coverage.
