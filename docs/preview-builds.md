# Builds de prueba en el móvil (Firebase App Distribution)

El workflow `.github/workflows/preview.yml` genera la variante `preview` de Android y la sube a
Firebase App Distribution:

- **Cada push a `main`** (cada PR mergeada).
- **Cada push a una PR con la etiqueta `preview`**, para probar un cambio antes de mergearlo.
- **A mano**: Actions → Preview → *Run workflow*.

## Qué corre en Actions

- **CI** (`ci.yml`) en cada PR que toca código (no en `docs/` ni en `.md`): Android (ktlint, tests y la
  build minificada). No se repite al mergear: lo mergeado es lo que ya pasó en la PR, y la preview de
  `main` lo vuelve a compilar.
- **iOS** (compilación Kotlin/Native) solo a mano mientras no se trabaja en iOS: Actions → CI → *Run
  workflow* sobre la rama. Los runners de macOS tardan en arrancar.
- **En local**, antes de hacer push: `scripts/check.sh` (lo mismo que CI; iOS solo en un Mac) o
  `scripts/check.sh --fast` (ktlint y tests).

La app de preview se instala **al lado** de la de Android Studio: id `com.denebapps.patrimonio.preview`,
nombre «Patrimonio β» y datos propios. Cada build actualiza la anterior sin perder datos, porque
siempre se firma con la misma clave. Las notas de la versión llevan el título de la PR, el número de
build y el commit.

Mientras falten secrets, el workflow solo deja un aviso y no hace nada más (`main` sigue en verde).

## Minificada como la de tienda (R8)

La variante `preview` parte de `release`: **R8 activado** (código y recursos) y no depurable. R8 falla en
tiempo de ejecución, no al compilar, así que lo que se prueba en el móvil es lo mismo que irá a la tienda.

- Reglas propias en `composeApp/proguard-rules.pro`: solo las que haya pedido un fallo real, con comentario.
- El CI compila la variante minificada en cada PR. Si R8 echa en falta clases, el check muestra las reglas
  que sugiere en una anotación «R8 missing rules».
- Cada build sube su `mapping.txt` como artefacto (`r8-mapping-build-N` en *Preview*, 90 días) para
  desofuscar trazas con `retrace`.

## Configuración (una vez)

### 1. Firebase

1. En [console.firebase.google.com](https://console.firebase.google.com) crea un proyecto (Analytics no
   hace falta).
2. Añade una app **Android** con el paquete `com.denebapps.patrimonio.preview`. No hace falta
   descargar `google-services.json`: la app no usa el SDK de Firebase.
3. En *Configuración del proyecto → General*, copia el **ID de la app** (`1:…:android:…`).
4. Abre *App Distribution* en el menú y pulsa *Comenzar*.

### 2. Cuenta de servicio para el CI

1. En [Google Cloud → IAM → Cuentas de servicio](https://console.cloud.google.com/iam-admin/serviceaccounts),
   con el proyecto de Firebase seleccionado, crea una cuenta (por ejemplo `github-preview`).
2. Dale el rol **Firebase App Distribution Admin**.
3. En *Claves → Agregar clave → JSON*, descarga el archivo. Su contenido es el secret
   `FIREBASE_SERVICE_ACCOUNT`. Después borra el archivo local.

### 3. Clave de firma de preview

Genérala en tu máquina y guárdala también fuera del repo: si se pierde, la siguiente build no se
podrá instalar encima y tendrás que desinstalar la app de preview.

```sh
keytool -genkeypair -v -keystore preview.jks -alias preview \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -i preview.jks | tr -d '\n'    # el resultado es PREVIEW_KEYSTORE_BASE64
```

### 4. Secrets del repositorio

En GitHub, *Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Valor |
| --- | --- |
| `PREVIEW_KEYSTORE_BASE64` | El `preview.jks` en base64 (paso 3) |
| `PREVIEW_KEYSTORE_PASSWORD` | La contraseña del keystore |
| `PREVIEW_KEY_ALIAS` | `preview` (o el alias que hayas usado) |
| `PREVIEW_KEY_PASSWORD` | La contraseña de la clave; con `keytool` suele ser la misma |
| `FIREBASE_APP_ID` | El ID de la app del paso 1 |
| `FIREBASE_SERVICE_ACCOUNT` | El JSON completo del paso 2 |
| `FIREBASE_TESTERS` | Tu email (varios, separados por comas) |

### 5. En el móvil

1. Con la primera build te llega un email de invitación de Firebase. Acéptalo desde el móvil e
   instala **App Tester** cuando te lo pida.
2. Permite «Instalar apps desconocidas» para App Tester.
3. A partir de ahí, cada build nueva te llega como notificación y se actualiza desde App Tester.

### 6. Etiqueta `preview`

Para recibir una PR antes de mergearla, ponle la etiqueta `preview`. GitHub la crea la primera vez
que la escribes en el selector de etiquetas. Cada push posterior a esa PR genera una build nueva.
