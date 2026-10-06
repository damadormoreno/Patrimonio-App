# Patrimonio

App personal para seguir el patrimonio neto: activos, pasivos, grupos de cuentas, histórico
mensual y metas de ahorro, multi-divisa con conversión a EUR. Sin cuenta ni backend: los datos
viven en el dispositivo.

Nace como extracción de la parte de patrimonio de *FinanzasPersonales*, sin movimientos,
presupuestos, categorías ni analítica.

## Stack

- Kotlin Multiplatform + Compose Multiplatform, un único módulo `:composeApp`
  (`androidTarget`, `iosArm64`, `iosSimulatorArm64`) + `iosApp/` (Xcode).
- Kotlin 2.2.0, Compose MP 1.9.0, AGP 8.9.1, minSdk 29 / compileSdk 36 / target 35.
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
para escribir y testear migraciones. CI falla si el schema generado no coincide con el commiteado.

## CI

`.github/workflows/ci.yml`, en cada PR y en cada push a `main`:

- **android** (Ubuntu): `ktlintCheck`, `testDebugUnitTest`, `assembleDebug` y comprobación del
  schema de Room. Si fallan los tests, sube los informes como artifact.
- **ios** (macOS): `compileKotlinIosArm64`. Los minutos de macOS cuentan x10 en repos privados.

## Estructura

```
composeApp/src/commonMain/kotlin/com/denebapps/patrimonio/
├── domain/   modelo (Money, Currency, Asset, Liability, SavingsGoal…), calc puros, contratos
├── data/     Room (entities, DAOs), repositorios, FX (Frankfurter), DataStore
├── di/       módulos Koin
└── ui/       theme + componentes, navegación, pantallas (patrimonio, savings, settings, perfil)
```

`design-reference/` contiene el prototipo JSX original (especificación visual; no se distribuye).

## Copias de seguridad

Ajustes → Datos → *Exportar copia* / *Importar copia* (selector nativo vía
[FileKit](https://github.com/vinceglb/FileKit)). El archivo es JSON legible
(`data/backup/BackupDocument.kt`) con sobre `{"format": "patrimonio-backup", "version": 2}`:

- Incluye activos, pasivos, grupos y miembros, histórico mensual y metas con sus aportaciones y
  vínculos (una meta se vincula a una cuenta **o** a un grupo). No incluye tipos de cambio (caché)
  ni preferencias. Las copias de la versión 1 (sin vínculo a grupo) se siguen importando.
- Importar **reemplaza** todos los datos en una sola transacción. El archivo se valida entero antes
  (divisas, tipos, ids únicos, referencias, reglas del ledger de metas), así que uno inválido no
  toca nada.
- El formato está desacoplado de las entidades Room: un cambio de schema no cambia el archivo; un
  cambio de formato sube `version` y `BackupCodec` decide qué versiones sabe leer.

Además, Android incluye la DB en Auto Backup (`allowBackup`) e iOS en el backup de iCloud del
dispositivo.

## Roadmap

1. ~~**Backup**~~: export/import JSON desde Ajustes (ver *Copias de seguridad*).
2. ~~**Suscripciones**~~: pestaña con gasto mensual/anual en EUR, próximos cargos, pausadas y
   activo que paga (informativo, no mueve saldos).
3. ~~**Avisos de renovación**~~: notificación local a las 9:00 el mismo día, 1, 3 o 7 días antes
   de cada cargo, desactivada por defecto (Ajustes → Avisos). WorkManager en Android y
   `UNUserNotificationCenter` en iOS. Límites conocidos:
   - Se programan los próximos 60 días (máx. 50 avisos, por el límite de 64 de iOS); abrir la app
     amplía esa ventana. Si no abres la app en dos meses, dejan de llegar.
   - Un aviso cuya hora ya ha pasado al planificar no se envía. Si activas los avisos el mismo día de
     un cargo después de las 9:00, ese cargo se queda sin aviso.
   - En iOS no se muestran con la app en primer plano (falta un `UNUserNotificationCenterDelegate`).
4. **Login + sync** (opcional): Google / Apple / email junto con sincronización. Los ids de
   activos, pasivos y grupos ya son `String` (UUID); las metas de ahorro usan `Long`
   autogenerado y habría que migrarlas antes de sincronizar.
