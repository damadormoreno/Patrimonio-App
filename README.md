# Patrimonio

App personal para seguir el patrimonio neto: activos, pasivos, grupos de cuentas, histórico
mensual y metas de ahorro, multi-divisa con conversión a EUR. Sin cuenta ni backend: los datos
viven en el dispositivo.

Nace como extracción de la parte de patrimonio de *FinanzasPersonales*, sin movimientos,
presupuestos, categorías ni analítica.

## Stack

- Kotlin Multiplatform + Compose Multiplatform, un único módulo `:composeApp`
  (`androidTarget`, `iosArm64`, `iosSimulatorArm64`) + `iosApp/` (Xcode).
- Kotlin 2.2.0, Compose MP 1.9.0, AGP 8.7.3, minSdk 29 / target 35.
- Room KMP 2.7.1 (`BundledSQLiteDriver`), DataStore preferences, Koin 4, navigation-compose,
  Ktor (tipos de cambio de Frankfurter), kotlinx-datetime.
- Paquete / applicationId / bundle id: `com.denebapps.patrimonio`.

## Comandos

```bash
./gradlew :composeApp:testDebugUnitTest     # unit + Room (Robolectric)
./gradlew :composeApp:assembleDebug         # APK debug
./gradlew :composeApp:ktlintCheck           # lint/format
./gradlew :composeApp:compileKotlinIosArm64 # compile check iOS (macOS)
```

La primera build genera el schema de Room en `composeApp/schemas/` — **commitéalo**: es la base
para escribir y testear migraciones.

## Estructura

```
composeApp/src/commonMain/kotlin/com/denebapps/patrimonio/
├── domain/   modelo (Money, Currency, Asset, Liability, SavingsGoal…), calc puros, contratos
├── data/     Room (entities, DAOs), repositorios, FX (Frankfurter), DataStore
├── di/       módulos Koin
└── ui/       theme + componentes, navegación, pantallas (patrimonio, savings, settings, perfil)
```

`design-reference/` contiene el prototipo JSX original (especificación visual; no se distribuye).

## Roadmap

1. **Backup**: export/import JSON (Archivos / Drive / iCloud Drive). Android ya entra en Auto
   Backup (`allowBackup`), iOS en el backup de iCloud del dispositivo.
2. **Suscripciones**: alta con importe, divisa y periodicidad, coste mensual/anual normalizado a
   EUR con el FX existente, vínculo opcional al activo que paga y notificaciones locales de
   renovación (`expect/actual`: WorkManager / `UNUserNotificationCenter`).
3. **Login + sync** (opcional): Google / Apple / email junto con sincronización. Los ids de
   activos, pasivos y grupos ya son `String` (UUID); las metas de ahorro usan `Long`
   autogenerado y habría que migrarlas antes de sincronizar.
