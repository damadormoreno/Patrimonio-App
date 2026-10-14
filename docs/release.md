# Publicar una beta personal (Google Play y TestFlight)

Guía para llevar la app a tus propios móviles por los canales de prueba de las tiendas. No cubre la
publicación abierta al público. Las builds de prueba del día a día siguen yendo por Firebase App
Distribution ([preview-builds.md](preview-builds.md)); esto es para la build firmada de tienda.

## Resumen

| | Android | iOS |
| --- | --- | --- |
| Artefacto | App Bundle (`.aab`) firmado con la clave de subida | Archivo de Xcode subido a App Store Connect |
| Quién lo genera | Workflow `release.yml` al subir un tag `v*` | Xcode en un Mac (Product → Archive) |
| Canal de prueba | Pruebas internas de Google Play | TestFlight (testers internos) |
| Primera subida | A mano en Play Console | Desde Xcode |

## Versiones

- **`versionName`** (lo que ve el usuario): sale del tag de Git sin la `v`. `v1.0.0-beta.1` →
  `1.0.0-beta.1`. Usa `MAYOR.MENOR.PARCHE` y, si es una beta, un sufijo `-beta.N`.
- **`versionCode`** (entero que Play exige creciente en cada subida): el número de ejecución del workflow
  Release (`github.run_number`). Crece solo con cada ejecución, sea del tag que sea. No borres ni
  renombres `release.yml`: el contador empezaría de cero y Play rechazaría los códigos más bajos que
  los ya subidos. Si hiciera falta, se puede fijar una base más alta sumando un desplazamiento en el
  workflow.
- **Local** (sin variables): `versionName` = `1.0.0-dev`, `versionCode` = `1`.
- La variante `preview` usa otro id (`com.denebapps.patrimonio.preview`) y su propio contador; no
  interfiere con la de tienda.
- **iOS** lleva sus números aparte, en Xcode: `MARKETING_VERSION` (versión visible) y
  `CURRENT_PROJECT_VERSION` (build, debe subir en cada subida del mismo `MARKETING_VERSION`).

## 1. Android: clave de subida y secrets (una vez)

Play firma la app que llega a los usuarios con su propia clave (App signing by Google Play). Tú firmas
cada `.aab` con una **clave de subida**, que Play solo usa para saber que el bundle es tuyo. Si la
pierdes, Play permite restablecerla; aun así, guárdala bien.

1. Genera el keystore (fuera del repositorio):

   ```
   keytool -genkeypair -v -keystore patrimonio-upload.jks -alias <ALIAS> \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

   Te pide la contraseña del keystore, la de la clave (puede ser la misma) y tus datos. Anota el alias
   y las contraseñas en tu gestor de contraseñas.

2. Guarda `patrimonio-upload.jks` en un sitio seguro con copia (gestor de contraseñas con adjuntos, caja
   fuerte cifrada). **Nunca lo subas al repositorio.**

3. Conviértelo a Base64 en una sola línea:

   ```
   base64 -w0 patrimonio-upload.jks            # Git Bash / Linux
   base64 -i patrimonio-upload.jks              # macOS
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("patrimonio-upload.jks"))   # PowerShell
   ```

4. En GitHub: repositorio → **Settings → Secrets and variables → Actions → New repository secret**.
   Crea estos cuatro:

   | Secret | Valor |
   | --- | --- |
   | `RELEASE_KEYSTORE_BASE64` | el Base64 del paso anterior |
   | `RELEASE_KEYSTORE_PASSWORD` | contraseña del keystore |
   | `RELEASE_KEY_ALIAS` | el `<ALIAS>` |
   | `RELEASE_KEY_PASSWORD` | contraseña de la clave |

## 2. Android: generar el AAB

1. Desde `main` (con lo que quieras publicar ya mergeado), crea y sube el tag:

   ```
   git tag v1.0.0-beta.1
   git push origin v1.0.0-beta.1
   ```

   También se puede lanzar a mano: Actions → Release → *Run workflow* (pide el `versionName`).

2. El workflow ejecuta los tests, firma y construye el bundle, y deja como artefactos (90 días) el
   **`.aab`** y el **`mapping.txt`** de R8. El resumen de la ejecución lista las huellas SHA-1/SHA-256 del
   certificado de subida. El `.aab` ya lleva el mapping dentro, así que Play desofusca solo los
   informes de fallos; el archivo suelto sirve para trazas de otras fuentes.
3. Descarga el artefacto `patrimonio-<versión>-aab` (Actions → la ejecución → Artifacts).

El workflow **no sube nada a Play**. La primera subida es a mano. Cuando exista una cuenta de servicio de
Play (Play Console → Configuración → Acceso a la API), se puede añadir un paso con
`r0adkll/upload-google-play` y el track `internal` (hay un comentario en `release.yml`).

### Build local (opcional)

```
export RELEASE_KEYSTORE_FILE=/ruta/absoluta/patrimonio-upload.jks
export RELEASE_KEYSTORE_PASSWORD=...
export RELEASE_KEY_ALIAS=...
export RELEASE_KEY_PASSWORD=...
VERSION_NAME=1.0.0-beta.1 VERSION_CODE=1 ./gradlew :composeApp:bundleRelease
```

Las cuatro variables también valen como propiedades de Gradle (`~/.gradle/gradle.properties`). El
bundle queda en `composeApp/build/outputs/bundle/release/composeApp-release.aab`.

**Sin las cuatro**, `bundleRelease` y `assembleRelease` fallan al empezar con «Release signing is not
configured» y la lista de lo que falta: un bundle sin firmar no sirve en Play y es fácil de confundir con
uno bueno. `assembleDebug`, `assemblePreview` y `scripts/check.sh` no necesitan la clave.

## 3. Android: Play Console, pruebas internas

Requisito previo: cuenta de desarrollador de Google Play (pago único, verificación de identidad).

1. **Crear la app**: Play Console → *Crear app*. Nombre «Patrimonio», idioma predeterminado español,
   *App* (no juego), *Gratuita*, y acepta las declaraciones. Una app gratuita no puede pasar a de pago
   después.
2. **App signing by Google Play**: las apps nuevas con App Bundle ya entran en este programa. Al subir
   el primer `.aab`, Play toma como clave de subida la del keystore que lo firmó.
3. **Pruebas internas**: *Probar y publicar → Pruebas → Pruebas internas*.
   1. Pestaña *Verificadores*: crea una lista de correos (cuentas de Google de los testers, hasta 100) y
      actívala.
   2. *Crear versión* → sube el `.aab` a mano → nombre de la versión y notas → *Guardar* → *Revisar* →
      *Iniciar lanzamiento en pruebas internas*.
   3. En la pestaña *Verificadores* copia el **enlace de participación**; cada tester debe abrirlo con su
      cuenta, aceptar y luego instalar desde Play. Las pruebas internas no pasan revisión y suelen estar
      disponibles en minutos.
4. **Contenido de la app** (Play lo pide antes de cualquier lanzamiento, también el interno, en el panel
   *Configurar tu app*):
   - **Política de privacidad**: `https://patrimonio-116ff.web.app/privacidad`. La página está en
     `hosting/public/privacidad.html` y se publica en Firebase Hosting ([web.md](web.md)); hay que desplegarla
     antes de pegar la URL.
   - **Seguridad de los datos (Data safety)**. Punto de partida según la política de privacidad
     (revísalo con lo que haga la versión que subas):
     - Recoge: correo electrónico e ID de usuario (cuenta opcional) y contenido del usuario (la copia en
       la nube, cifrada en el móvil). Finalidad: funcionalidad de la app. Sin publicidad ni analítica.
     - Los datos viajan cifrados (HTTPS). El usuario puede pedir su borrado: desde la app y por correo.
     - No se comparten con terceros para publicidad. Las consultas de precios envían solo identificadores
       de instrumentos (ISIN, ticker, divisa), no datos personales.
   - **Eliminación de la cuenta**: Play exige, para apps con cuentas, una URL donde se explique cómo pedir
     el borrado de la cuenta y los datos (además del borrado dentro de la app). En *Seguridad de los datos*,
     en la URL de eliminación de la cuenta: `https://patrimonio-116ff.web.app/borrar-cuenta`
     (`hosting/public/borrar-cuenta.html`: pasos en la app, borrado por correo en 30 días y qué se elimina).
   - **Anuncios**: la app no los tiene. **Acceso a la app**: todo es accesible sin credenciales. **Público
     objetivo**: adultos. **Clasificación de contenido**: cuestionario IARC. **Funciones financieras**:
     revisa la declaración; la app no mueve dinero, solo registra patrimonio.
5. **Nivel de API objetivo**: la app apunta a la API 36, que es lo que Play exige desde el 31-08-2026.
   Los `.so` de la app están alineados a 16 KB, requisito para apps que apuntan a Android 15 o más.

### Antes de poder publicar al público

Las cuentas **personales** creadas después de noviembre de 2023 tienen que pasar una **prueba cerrada con
al menos 12 testers durante 14 días seguidos** antes de pedir acceso a producción. Las pruebas internas no
cuentan: hay que crear una pista de *Pruebas cerradas*, añadir a los 12 testers (que deben aceptar la
invitación y mantenerla) y esperar los 14 días. Compruébalo en Play Console, porque Google cambia estos
requisitos con frecuencia. Para una beta solo en tus móviles no hace falta.

## 4. Inicio de sesión con Google en la build de Play

La versión instalada desde Play la firma Play, no tú, así que su huella es distinta de la de subida y de
la de preview. Sin registrarla, el selector de cuentas de Google falla
([cloud-account.md](cloud-account.md#iniciar-sesión-con-google-android)).

1. Play Console → tu app → *Probar y publicar → Configuración → Integridad de la app* →
   *Firma de apps* → **Certificado de la clave de firma de la app**: copia la SHA-1 y la SHA-256.
2. [Consola de Firebase](https://console.firebase.google.com) → proyecto `patrimonio-116ff` →
   *Configuración del proyecto → General → Tus apps*. Si la app Android `com.denebapps.patrimonio` no
   está, agrégala. → *Agregar huella digital* con ambas.
3. (Opcional) añade también la huella del certificado de **subida** si vas a instalar a mano un APK
   generado desde el `.aab` firmado con ella.

Los cambios de huellas tardan unos minutos en propagarse.

## 5. iOS: TestFlight

Necesitas un Mac con Xcode y una cuenta del Apple Developer Program (de pago, anual).

1. **Equipo de firma**: abre `iosApp/iosApp.xcodeproj` → target `iosApp` → *Signing & Capabilities* →
   marca *Automatically manage signing* y elige tu *Team*. El repositorio no lleva equipo ni identidad
   de firma (`DEVELOPMENT_TEAM` vacío): es personal, no lo subas.
2. **Bundle id**: `com.denebapps.patrimonio`. Si Xcode lo da por ocupado en tu cuenta, hay que cambiarlo
   en el proyecto de Xcode (y en Firebase si registras esa app).
3. **App Store Connect**: *Mis apps → +* → *Nueva app*: plataforma iOS, nombre «Patrimonio», idioma
   principal español, el bundle id anterior y un SKU a tu gusto.
4. **Números de versión**: en Xcode, *General*: *Version* (`MARKETING_VERSION`) y *Build*
   (`CURRENT_PROJECT_VERSION`). Cada subida necesita un *Build* nuevo y mayor.
5. **Archivar y subir**: destino *Any iOS Device (arm64)* → *Product → Archive* → en el Organizer,
   *Distribute App → App Store Connect → Upload*. La fase «Compile Kotlin Framework» llama a Gradle, así
   que el Mac necesita el JDK 17.
6. **Procesamiento**: App Store Connect tarda de unos minutos a un rato en procesar la build. Si pide
   completar *Cumplimiento de exportación* (ver abajo), resuélvelo para que quede disponible.
7. **Testers internos**: *TestFlight → Probadores internos* (usuarios de tu equipo de App Store Connect,
   hasta 100). Crea un grupo, añádete, asigna la build y abre TestFlight en el iPhone. Las builds
   internas no pasan revisión de Apple; las externas sí.
8. **Privacidad**: el proyecto incluye `iosApp/iosApp/PrivacyInfo.xcprivacy`. Además, en App Store
   Connect hay que rellenar los «datos de privacidad de la app» y la URL de la política
   (`https://patrimonio-116ff.web.app/privacidad`, ver [web.md](web.md)) antes de publicar al público (para
   TestFlight interno no se exige). Apple exige poder borrar la cuenta dentro de la app: lo cubre Ajustes →
   Cuenta → «Borrar cuenta» (la página `https://patrimonio-116ff.web.app/borrar-cuenta` lo explica además
   fuera de la app).

### Cumplimiento de exportación (cifrado)

La app **usa cifrado**: HTTPS y, además, cifra la copia en la nube con AES-256-GCM y PBKDF2 en el
propio código. Por eso **no** se ha añadido `ITSAppUsesNonExemptEncryption` a `Info.plist`: la respuesta
es una declaración legal tuya, no algo que decida el repositorio.

App Store Connect preguntará en cada build si la app usa cifrado y si está exenta. Lee la guía de Apple
«Complying with Encryption Export Regulations» y decide con tu caso (algoritmos estándar, uso para
proteger los datos del propio usuario, distribución desde España/UE). Una vez decidida la respuesta
estable, puedes añadir la clave `ITSAppUsesNonExemptEncryption` a `Info.plist` (`true` o `false` según
corresponda) para que no la pregunte en cada subida; si la respuesta implica documentación, se aporta
en App Store Connect.

### Si Xcode no incluyó `PrivacyInfo.xcprivacy`

El archivo está referenciado en el proyecto y en la fase *Copy Bundle Resources*. Si no aparece en la
build: en Xcode, clic derecho en el grupo `iosApp` → *Add Files to "iosApp"…* → `PrivacyInfo.xcprivacy`
con el target `iosApp` marcado. Comprueba con *Product → Archive →* *Generate Privacy Report*.

## Lista de comprobación

**Una vez**
- [ ] Keystore de subida creado, copiado a un sitio seguro y contraseñas en el gestor.
- [ ] Cuatro secrets `RELEASE_*` en GitHub.
- [ ] Cuenta de Google Play, app creada y declaraciones de contenido rellenas.
- [ ] Web desplegada en Firebase Hosting ([web.md](web.md)); URLs de privacidad y de eliminación de cuenta en
  Play Console.
- [ ] Huellas de la firma de Play añadidas en Firebase.
- [ ] Cuenta del Apple Developer Program, equipo elegido en Xcode, app creada en App Store Connect.
- [ ] Decisión sobre el cumplimiento de exportación de Apple.

**En cada beta**
- [ ] `scripts/check.sh` en verde y todo mergeado en `main`.
- [ ] Tag `vX.Y.Z-beta.N` subido; workflow Release en verde.
- [ ] `.aab` subido a Pruebas internas; testers lo instalan desde Play.
- [ ] Sesión con Google probada en la build de Play.
- [ ] iOS: *Build* incrementado, archivado y subido; asignado en TestFlight.

## Qué no hace todavía

- Subir a Play automáticamente (hace falta una cuenta de servicio).
- Subir a TestFlight desde CI (necesita certificados y perfiles en un runner de macOS).
- Publicar al público: antes hay que resolver las fuentes de cotizaciones (Morningstar y Yahoo no son
  oficiales) y la prueba cerrada de 12 testers / 14 días de Play.
