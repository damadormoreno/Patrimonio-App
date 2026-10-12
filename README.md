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

Pestaña *Cartera*: fondos, ETF, acciones y criptomonedas buscados por ISIN, ticker o nombre. Cada producto se
añade con las participaciones o con el importe actual (se calculan las participaciones al último
precio) y se valora a ese precio; lo que no tenga cotización se añade con valor manual. El total en
EUR suma al patrimonio neto como bloque propio y aparece como *Cartera* en la composición. Cada
producto puede llevar, opcionalmente, el banco o bróker que lo gestiona (*Banco o bróker*, texto libre).

- **Varias carteras** (p. ej. *Jubilación*, *Hijos*): cada producto está en **una sola** cartera y el
  patrimonio neto suma todas. En la pestaña se elige *Todas* o una cartera (se recuerda la elección);
  lista, total y detalle siguen esa selección. Se crean, renombran y eliminan desde *Gestionar*: al
  eliminar una con productos se pide a qué otra moverlos, y la última no se puede borrar. Al añadir un
  producto se elige su cartera y desde su ficha se puede mover a otra.

- **Filtros**: *Filtrar* (junto a las carteras) por clase de activo, bróker y tipo. Varias opciones de un
  mismo filtro suman y filtros distintos se combinan; la lista y el total siguen el filtro («Filtrado · N de M
  productos») y se recuerda el último. No afectan al patrimonio neto ni al detalle de la cartera.
- **Objetivo de reparto y rebalanceo** (por cartera, en su detalle): porcentaje objetivo por clase de activo
  (suman 100 %) y un margen (±5 puntos por defecto). El detalle muestra el reparto actual frente al objetivo y
  la desviación de cada clase; si alguna se sale del margen, *Cartera* avisa («Tu cartera se ha desviado del
  objetivo») y dice cuánto aportar, y a qué clases, para volver al objetivo **sin vender**. Una calculadora
  reparte la próxima aportación entre las clases que más lo necesitan. Una clase con dinero y objetivo 0 % solo
  se corrige vendiendo (se avisa); los productos sin clase no cuentan. En *Todas* se listan las carteras que
  necesitan rebalanceo.

- **Precios**: Morningstar (`lt.morningstar.com`: búsqueda por ISIN y valor liquidativo en EUR,
  sobre todo fondos) y Yahoo Finance (búsqueda y cierres diarios de ETF y acciones), con la misma
  lógica que [rumbo](https://github.com/danidm98/rumbo) (MIT). Están detrás del puerto
  `QuoteSource` (`data/quotes/`), así que se pueden cambiar por otra fuente sin tocar el resto.
- **No son APIs oficiales**: pueden cambiar o dejar de responder sin aviso. Si falla una consulta
  se conserva el último precio con su fecha. Aceptable para uso personal; antes de publicar la app
  habría que pasar a una fuente con licencia.
- Los precios se actualizan al abrir la app si tienen más de 6 horas y con *Actualizar* (o
  deslizando hacia abajo) en la pestaña. Solo se admiten productos cotizados en EUR, USD, GBP o JPY.
- **Criptomonedas** con [CoinGecko](https://www.coingecko.com/) (API pública, sin clave): búsqueda,
  precio en EUR e histórico **del último año** (la API pública no da más). Las monedas que valen menos de
  un céntimo pierden precisión (los precios se guardan con 6 decimales).
- **Clase de activo** por producto: Renta variable, Renta fija, Monetario, Mixto, Oro y materias primas,
  Inmobiliario, Cripto u Otros. Se deduce de la categoría de Morningstar (o del tipo, para acciones y
  criptos) y se puede elegir a mano. El detalle de la cartera empieza por el reparto por clase; los
  productos sin clase salen como *Sin clasificar*. Los fondos mixtos cuentan enteros como *Mixto*.
- **Ficha previa** (al pulsar un resultado de la búsqueda, antes de añadirlo): la misma información que el
  detalle de un producto y su **encaje con tu cartera** (la seleccionada o todas): correlación con cada
  producto y con la cartera, una conclusión (*diversifica poco* ≥ 0,9 · *moderadamente* · *aporta
  diversificación* < 0,5) y, con un importe, cómo quedarían el peso, el TER y el riesgo medios y la
  concentración. Su histórico se descarga solo para la ficha; *Añadir a la cartera* lleva al formulario.
- **Detalle de un producto** (al pulsarlo): gráfica del precio (1M · 6M · 1A · 5A · Máx, deslizando el
  dedo se ve cada día), rentabilidad por año natural y anualizada a 1, 3 y 5 años, volatilidad
  (semanal anualizada, 3 años), máxima caída y, si Morningstar los tiene, gastos corrientes (TER) con
  su coste anual para tu posición, riesgo 1–7, categoría y gestora.
- **Detalle de la cartera** (al pulsar el total): evolución del valor con la composición actual (tus
  participaciones de hoy a precios pasados) con sus rentabilidades y riesgo, **matriz de correlación**
  de las rentabilidades semanales en EUR (últimos 3 años), reparto por tipo, banco y divisa, TER y
  riesgo medios ponderados y aviso de concentración si un producto supera el 40 %.
- Son rentabilidades **del producto**, no de tus compras; las tuyas van aparte en *Mi posición*.
- **Histórico**: precios diarios de Morningstar y Yahoo y tipos de cambio históricos de Frankfurter
  (para valorar en EUR productos en otra divisa). Se guardan en el dispositivo como caché, se
  descargan solo los días nuevos y no van en las copias de seguridad (se vuelven a descargar).

### Movimientos

Cada producto puede llevar sus **movimientos**: compra, venta, saldo inicial, dividendo, comisión y
traspaso. Un producto **sin movimientos** funciona como antes (participaciones a mano, sin rentabilidad
propia); **con movimientos**, las participaciones salen de ellos y no se editan a mano.

- **Saldo inicial**: al añadir el primer movimiento a un producto que ya tiene participaciones se ofrece
  partir de ellas, con una fecha y un coste **opcional**. Sin coste, ese tramo es *coste desconocido*
  (nunca cero): se consume el primero y sus plusvalías salen como *—*. También se pueden meter las
  compras una a una. Un producto nuevo se añade con *Lo que tengo ahora* (sin movimientos) o con *Una
  compra*.
- **Coste FIFO**: cada venta consume los lotes más antiguos. La comisión es un campo aparte: en una
  compra suma al coste y en una venta resta del importe. Los dividendos son ingresos y no tocan el coste.
  No se puede vender ni traspasar más de lo que se tiene en esa fecha.
- **Traspasos solo entre fondos** (los ETF, acciones y criptos tributan: son venta + compra). Las
  participaciones salen de un fondo y entran en otro, de cualquier cartera o bróker, con sus **fechas y
  costes de compra originales** y sin plusvalía realizada. El destino puede ser un fondo que ya tengas
  (con movimientos) u *Otro fondo…* buscado en el momento.
- **Divisas**: cada movimiento guarda el importe en la divisa del producto y su equivalente en EUR;
  coste y plusvalías se calculan en EUR. Se puede escribir cualquiera de los dos y el otro se calcula con
  el tipo de ese día; si se escriben los dos (lo que cobró de verdad el bróker), se respetan y se muestra
  el tipo resultante.
- **Mi posición** (en el detalle del producto, aparte de su histórico): aportado, valor actual,
  plusvalía latente y realizada, dividendos, comisiones, rentabilidad anual (**XIRR**), lotes abiertos y
  la evolución del valor de tu posición. Los mismos datos, por cartera o para *Todas*, en *Tu
  rentabilidad* del detalle de la cartera; los traspasos entre productos incluidos se compensan y los
  productos sin movimientos o con coste desconocido quedan fuera, con una nota.
- Una posición que llega a cero participaciones queda como *Cerrada* al final de la lista.
- Fuera de esta fase: TWR y recalcular los cierres mensuales pasados (solo se actualiza el mes en curso).

### Importar de MyInvestor

*Cartera → Importar*: los lotes abiertos de cada fondo de MyInvestor entran como compras con su **fecha
fiscal y su coste reales** (los que llegaron por traspaso conservan los originales).

1. En la web de MyInvestor, entra en cada fondo → *Plusvalías y minusvalías* → descarga el CSV. No le
   cambies el nombre: lleva el ISIN del fondo (el archivo no lo incluye).
2. Elige uno o varios CSV. Cada uno se asocia a tu fondo por el ISIN; si no lo tienes, se crea buscándolo
   en Morningstar (con *MyInvestor* como bróker) en la cartera que elijas.
3. Revisa la vista previa: lotes, invertido y valor de mercado (compáralos con MyInvestor) y las
   **participaciones actuales**. El archivo no las trae: se toman de la app (o se estiman con el último
   precio para un fondo nuevo) y conviene copiarlas de MyInvestor; se reparten entre los lotes en proporción
   a su valor de mercado, cuadrando exactamente. Avisa si no cuadran con el valor del archivo.
4. *Importar*: **sustituye** los movimientos de ese fondo, así que se puede repetir cada mes sin duplicar
   nada. Un fondo con traspasos registrados en la app no se puede reimportar.

No se importan los reembolsos (MyInvestor solo da su plusvalía, sin fecha ni participaciones), ni ETF o
acciones (MyInvestor no exporta sus órdenes), ni fondos en otra divisa que no sea el euro.

## Copias de seguridad

Ajustes → Datos → *Exportar copia* / *Importar copia* (selector nativo vía
[FileKit](https://github.com/vinceglb/FileKit)). El archivo es JSON legible
(`data/backup/BackupDocument.kt`) con sobre `{"format": "patrimonio-backup", "version": 14}`:

- Incluye activos, pasivos, grupos y miembros, histórico mensual y metas con sus aportaciones y
  vínculos (una meta se vincula a una cuenta **o** a un grupo), tipos de cuenta propios y los
  productos de la cartera con su último precio y los datos de Morningstar (TER, riesgo, categoría y
  gestora). No incluye tipos de cambio ni históricos de precios (cachés) ni preferencias. Las copias de versiones anteriores se siguen importando;
  en las de la 1 a la 3 las metas tenían id numérico y reciben un UUID nuevo al importarlas, y la
  sección de suscripciones de las copias de la 3 a la 6 se ignora (la función se eliminó en la 7);
  las anteriores a la 8 se importan sin cartera y las anteriores a la 9, sin banco o bróker,
  las anteriores a la 10, sin datos de Morningstar (se rellenan solos), y las anteriores a la 11
  meten todos los productos en una única cartera *Mi cartera*. Desde la 12 guardan la clase de activo
  elegida a mano y desde la 13, los movimientos (al importar, las participaciones de los productos con
  movimientos se recalculan a partir de ellos); desde la 14, el objetivo de reparto y el margen de cada cartera.
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
   renovación (ver *Cartera*); incluye el detalle de cada producto y de la cartera. ~~Fase 2~~:
   movimientos (compras, ventas, saldo inicial, comisiones, dividendos y traspasos entre fondos), coste
   FIFO y rentabilidad propia (XIRR, separada de la del producto). Fase 3: importación desde brókeres
   (~~MyInvestor, fondos~~; Openbank pendiente de ver qué exporta); más adelante, TWR.
3. **Login + sync** (opcional): Google / Apple / email junto con sincronización. Activos, pasivos,
   grupos y metas ya tienen id `String` (UUID); la migración 3 → 4 (`MIGRATION_3_4`)
   dio un UUID nuevo a las metas existentes. Las aportaciones y cambios de vínculo de cada meta
   siguen con id local autoincremental: cómo sincronizarlas se decide con el diseño del sync.
