# Patrimonio

App personal para seguir el patrimonio neto: activos, pasivos, grupos de cuentas, histórico
mensual, metas de ahorro y cartera de inversión, multi-divisa con conversión a EUR. Sin cuenta ni backend: los datos
viven en el dispositivo.

Nace como extracción de la parte de patrimonio de *FinanzasPersonales*, sin movimientos,
presupuestos, categorías ni analítica.

## Stack

- Kotlin Multiplatform + Compose Multiplatform, un único módulo `:composeApp`
  (`androidTarget`, `iosArm64`, `iosSimulatorArm64`) + `iosApp/` (Xcode).
- Kotlin 2.2.0, Compose MP 1.9.0, AGP 8.9.1, minSdk 29 / compileSdk 36 / target 35.
- Room KMP 2.7.1 (`BundledSQLiteDriver`), DataStore preferences, Koin 4, navigation-compose,
  Ktor (tipos de cambio de Frankfurter y cotizaciones de Morningstar/Yahoo), kotlinx-datetime.
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
├── data/     Room (entities, DAOs), repositorios, FX (Frankfurter), cotizaciones, DataStore
├── di/       módulos Koin
└── ui/       theme + componentes, navegación, pantallas (patrimonio, cartera, savings, settings, perfil)
```

`design-reference/` contiene el prototipo JSX original (especificación visual; no se distribuye).

## Cartera

Pestaña *Cartera*: fondos, ETF y acciones buscados por ISIN, ticker o nombre. Cada producto se
añade con las participaciones o con el importe actual (se calculan las participaciones al último
precio) y se valora a ese precio; lo que no tenga cotización se añade con valor manual. El total en
EUR suma al patrimonio neto como bloque propio y aparece como *Cartera* en la composición.

- **Precios**: Morningstar (`lt.morningstar.com`: búsqueda por ISIN y valor liquidativo en EUR,
  sobre todo fondos) y Yahoo Finance (búsqueda y cierres diarios de ETF y acciones), con la misma
  lógica que [rumbo](https://github.com/danidm98/rumbo) (MIT). Están detrás del puerto
  `QuoteSource` (`data/quotes/`), así que se pueden cambiar por otra fuente sin tocar el resto.
- **No son APIs oficiales**: pueden cambiar o dejar de responder sin aviso. Si falla una consulta
  se conserva el último precio con su fecha. Aceptable para uso personal; antes de publicar la app
  habría que pasar a una fuente con licencia.
- Los precios se actualizan al abrir la app si tienen más de 6 horas y con *Actualizar* (o
  deslizando hacia abajo) en la pestaña. Solo se admiten productos cotizados en EUR, USD, GBP o JPY.
- Fuera de esta primera fase: movimientos (compras, ventas, traspasos), coste y plusvalías,
  histórico de precios, rentabilidad (XIRR/TWR) e importación desde brókeres.

## Copias de seguridad

Ajustes → Datos → *Exportar copia* / *Importar copia* (selector nativo vía
[FileKit](https://github.com/vinceglb/FileKit)). El archivo es JSON legible
(`data/backup/BackupDocument.kt`) con sobre `{"format": "patrimonio-backup", "version": 8}`:

- Incluye activos, pasivos, grupos y miembros, histórico mensual y metas con sus aportaciones y
  vínculos (una meta se vincula a una cuenta **o** a un grupo), tipos de cuenta propios y los
  productos de la cartera con su último precio. No incluye
  tipos de cambio (caché) ni preferencias. Las copias de versiones anteriores se siguen importando;
  en las de la 1 a la 3 las metas tenían id numérico y reciben un UUID nuevo al importarlas, y la
  sección de suscripciones de las copias de la 3 a la 6 se ignora (la función se eliminó en la 7);
  las anteriores a la 8 se importan sin cartera.
- Importar **reemplaza** todos los datos en una sola transacción. El archivo se valida entero antes
  (divisas, tipos, ids únicos, referencias, reglas del ledger de metas), así que uno inválido no
  toca nada.
- El formato está desacoplado de las entidades Room: un cambio de schema no cambia el archivo; un
  cambio de formato sube `version` y `BackupCodec` decide qué versiones sabe leer.

Además, Android incluye la DB en Auto Backup (`allowBackup`) e iOS en el backup de iCloud del
dispositivo.

## Roadmap

1. ~~**Backup**~~: export/import JSON desde Ajustes (ver *Copias de seguridad*).
2. ~~**Cartera** (fase 1)~~: sustituye a Suscripciones, que se eliminaron junto con los avisos de
   renovación (ver *Cartera*). Siguientes fases: movimientos (compras, ventas, comisiones,
   dividendos y traspasos), coste FIFO, histórico de precios, rentabilidad e importación.
3. **Login + sync** (opcional): Google / Apple / email junto con sincronización. Activos, pasivos,
   grupos y metas ya tienen id `String` (UUID); la migración 3 → 4 (`MIGRATION_3_4`)
   dio un UUID nuevo a las metas existentes. Las aportaciones y cambios de vínculo de cada meta
   siguen con id local autoincremental: cómo sincronizarlas se decide con el diseño del sync.
