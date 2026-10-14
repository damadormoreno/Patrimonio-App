# Feature: public web pages (privacy policy and account deletion)

## Objective

Publish the privacy policy and an account-deletion page on Firebase Hosting (project `patrimonio-116ff`), so the
Play listing and Data safety form have the two public URLs they require, and «Acerca de» links to them.

## Problem / why

- Google Play requires a public privacy-policy URL and, for apps with accounts, a public web page where users can
  request account deletion without the app installed. Apple requires the privacy-policy URL.
- In-app deletion already exists (Cuenta → Borrar cuenta deletes the Firestore copy and the Firebase account;
  Ajustes → Borrar todos los datos wipes the device). Only the web side is missing.
- GitHub Pages is not an option: the repository will become private (free plan). Firebase Hosting is free and in the
  same project.

## Scope

- `firebase.json` + `.firebaserc` (hosting only, `cleanUrls`), static site under `hosting/public/`:
  `index.html`, `privacidad.html`, `borrar-cuenta.html`, shared CSS. No third-party requests (no Google Fonts, no
  analytics): system fonts or the app's OFL fonts self-hosted.
- Fill the privacy-policy placeholders with the owner's data (name, contact email, Firestore region
  `europe-southwest1`, last-updated date, minimum age, deletion timeframe). The HTML becomes the single source;
  `docs/privacy-policy.md` is removed and references point to the HTML.
- `AboutConfig.kt`: `PRIVACY_POLICY_URL = "https://patrimonio-116ff.web.app/privacidad"`, `CONTACT_EMAIL`.
- Docs: deploy steps (`npx firebase-tools login` / `deploy --only hosting`) and the two URLs for Play Console.
- `.gitignore`: `.firebase/`, `firebase-debug.log`.

Out of scope: deploying (needs the owner's `firebase login`), automating deploys in CI, an English version.

## Constraints

- No secrets, no real financial figures. The owner's name and email are published by explicit decision.
- The policy must match what the app actually does (verify against code: data sources, cloud backup, no analytics).

## Tasks

- [x] T1 — Hosting config + three pages + CSS, policy filled in, md removed and references updated, AboutConfig URLs,
  docs, `.gitignore`. Route: delegated direct (writer trigger: 5+ non-trivial files). Checks: `scripts/check.sh`,
  pages rendered in the browser pane at phone and desktop width, link check between pages.

## Acceptance criteria

- `https://patrimonio-116ff.web.app/privacidad` and `/borrar-cuenta` would serve the pages after deploy (verified
  locally with `cleanUrls` semantics: files named `privacidad.html`, `borrar-cuenta.html`).
- No placeholder `[...]` left in the policy; contents match the app's behaviour.
- «Acerca de» shows the policy and contact rows.
- `scripts/check.sh` green.

## Delivery

- Forecast ~500 authored lines, mostly static HTML copy. Strategy: single PR (one cohesive unit; splitting static
  pages from their config gives no review benefit).

## Progress

- Branch `feat/web-pages` from `origin/main` (b075313). RDD off (global).
- T1 done (delegated writer, sonnet). Evidence:
  - AboutContentTest RED (2 failures) before the AboutConfig change, GREEN after; parent re-ran it: green.
  - `scripts/check.sh`: All checks passed.
  - No placeholders left; no external resources loaded (only `<a href>` links); internal links resolve.
  - Rendered with `serve` at 375px (no horizontal scroll, self-hosted fonts loaded) and desktop dark mode.
  - External links: 200 for Google, Firebase, Yahoo, Frankfurter, AEPD; CoinGecko and Morningstar answer 403 to curl
    (bot protection), not broken links.
- Policy corrections found against the code: «Borrar todos los datos» clears only financial data (profile and
  preferences stay); Firebase Auth is global (Firestore is europe-southwest1); Google profile photo download; search
  text is sent to quote providers; no analytics or crash SDK in the app.

## Next step

Owner deploys (`npx --yes firebase-tools login`, then `deploy --only hosting`) and enters both URLs in Play Console.
