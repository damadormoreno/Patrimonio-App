# Design reference

Source: Claude Design project **"App Finanzas"**
(https://claude.ai/design/p/bef9333f-1f3c-4b25-b2c2-40450f732647), exported 2026-07-06.

Inherited from *FinanzasPersonales*. These files are the **visual specification** for the
KMP/Compose Multiplatform app. Only `tokens.css`, `shared.jsx`, `patrimonio.jsx`, `grupos.jsx`,
`perfil.jsx` and the settings part of `screens.jsx` apply today; `login.jsx`/`lock.jsx` are
reference for a future login/lock.
They are a React/JSX prototype — do NOT ship them; port them to Compose.

| File | Contents |
|---|---|
| `tokens.css` | Design tokens: light/dark palettes, category colors, fonts, radii, shadows |
| `shared.jsx` | Icon SVG paths (24×24, stroke 1.7), categories, money/date formatters, sample data, primitives (CatIcon, Amount, Pill, BudgetBar, TabBar, FAB, ScreenHeader) |
| `screens.jsx` | Home, Movimientos, Presupuestos, Analíticas, Ajustes + TrendChart, EmptyState, SettingsCard/Row |
| `patrimonio.jsx` | Patrimonio screen, NetWorthCard + sparkline, StackedShare, group cards, AddPatrimonioSheet |
| `add-sheet.jsx` | AddSheet (new/edit transaction), Label, CurrencyDropdown |
| `grupos.jsx` | GruposSheet (account groups, overlapping views), NuevoGrupoSheet |
| `perfil.jsx` | PerfilScreen (profile fields, security, backup provider per platform) |
| `login.jsx` | Auth flow: Welcome, SignIn, SignUp (+password strength), Forgot, Verify (OTP), Done |
| `lock.jsx` | LockScreen: 4-digit PIN pad + biometric (Face ID / fingerprint per platform) |
| `app.jsx` | Prototype shell: navigation wiring, state flows between screens and sheets |

Notes for implementers:

- Fonts: **Manrope** (sans, UI) and **Instrument Serif italic** (display, hero amounts).
- The prototype's reference "today" is 2026-05-21; the real app uses the system date.
- FX rates are static: EUR 1, USD 0.92, JPY 0.0061, GBP 1.17 (to EUR).
- Icon paths in `ICONS` are plain SVG path data — port with `PathParser` to `ImageVector`.
- The prototype seeds sample data; the real app starts EMPTY (empty states everywhere).
- Screenshot `screenshots/01-home-light.png` exists in the Claude Design project (binary, not exported here).
