# Cuenta en la nube (Firebase)

La cuenta es **opcional**: sin ella la app funciona entera en el móvil. Sirve para guardar los datos
en la nube y recuperarlos en otro dispositivo.

La app habla con Firebase por su **API REST** (Ktor, código común): no enlaza ningún SDK de Firebase
ni necesita `google-services.json`. El proyecto y la API key están en
`composeApp/src/commonMain/.../data/auth/FirebaseConfig.kt`. La key no es secreta: el acceso lo
deciden el token del usuario y las reglas de seguridad.

## Configuración en Firebase (una vez)

1. [Consola de Firebase](https://console.firebase.google.com) → proyecto `patrimonio-116ff` →
   **Authentication** → **Comenzar**.
2. Pestaña **Método de inicio de sesión** → **Correo electrónico/contraseña** → **Habilitar**. Solo el
   primer interruptor; el «vínculo por correo» no hace falta.
3. Opcional: **Authentication → Plantillas** → cambia el idioma a español para que el correo de
   «restablecer contraseña» llegue en español.

Mientras no se haga el paso 1, Firebase responde `CONFIGURATION_NOT_FOUND` y la app muestra «No se ha
podido completar».

### Iniciar sesión con Google (Android)

1. **Authentication → Método de inicio de sesión → Agregar proveedor → Google** → Habilitar, elegir el
   correo de asistencia y Guardar. Firebase crea a la vez el cliente OAuth «Web client» en Google Cloud.
2. Su ID va en `FirebaseConfig.GOOGLE_WEB_CLIENT_ID`. Se puede leer sin entrar en Google Cloud:
   `https://www.googleapis.com/identitytoolkit/v3/relyingparty/getProjectConfig?key=<API_KEY>` →
   `idpConfig[].clientId` del proveedor `google.com`. Mientras esté vacío, la app no enseña el botón.
3. **Configuración del proyecto → General → Tus apps** → en cada app Android, **Agregar huella digital**
   con la SHA-1 y la SHA-256 del certificado que firma esa build:
   - `com.denebapps.patrimonio.preview`: el keystore de preview. El workflow de preview las escribe en el
     resumen de cada ejecución (Actions → la ejecución → Summary).
   - `com.denebapps.patrimonio` (Android Studio): `keytool -list -v -keystore ~/.android/debug.keystore
     -alias androiddebugkey -storepass android`.
   - Play Store, cuando llegue: la del certificado de firma de apps de Play Console, no la de subida.

   Sin la huella, el selector de cuentas falla o se cierra sin decir por qué («No se pudo iniciar sesión
   con Google»).

El selector es el de Credential Manager (`GetSignInWithGoogleOption`); su ID token se cambia por la sesión
de Firebase en `accounts:signInWithIdp`. La primera vez rellena el perfil vacío con el nombre y la foto
de Google; un nombre o una foto que ya tengas no se tocan. Si el correo ya tiene cuenta con contraseña,
Firebase (una cuenta por correo) la une a Google y mantiene el mismo uid, así que la copia en la nube
sigue siendo la misma. Ojo: si ese correo no estaba verificado, Firebase quita la contraseña y desde
entonces solo se entra con Google.

**iOS no lo ofrece todavía**: la App Store obliga a añadir «Iniciar sesión con Apple» si hay login con
Google (norma 4.8), así que llegarán juntos.

### Copia en la nube (Firestore)

4. **Firestore Database** → **Crear base de datos** → edición **Standard**, ubicación
   `europe-southwest1` (Madrid) o `eur3`, **modo producción**. La ubicación no se puede cambiar después.
5. Pestaña **Reglas** → sustituir por estas y **Publicar**:

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /users/{uid}/{document=**} {
         allow read, write: if request.auth != null && request.auth.uid == uid;
       }
     }
   }
   ```

Sin la base de datos o sin las reglas, Firestore responde 403/404 y Ajustes → Cuenta muestra «La copia
en la nube no está disponible ahora mismo».

## Qué guarda la app

- **Sesión**: uid, email, proveedor (contraseña o Google) y tokens en su propio DataStore (`patrimonio-auth.preferences_pb`). Ese
  archivo queda fuera del backup automático de Android, así que al restaurar en otro móvil hay que
  volver a iniciar sesión.
- **Token**: dura una hora y se renueva solo unos minutos antes de caducar. Si Firebase rechaza la
  renovación (contraseña cambiada, cuenta borrada en otro sitio), la app cierra la sesión.
- **Borrar cuenta**: Firebase exige un inicio de sesión reciente, por eso pide la contraseña (o, en una
  cuenta de Google, volver a elegir la misma cuenta de Google). Primero
  borra la copia en la nube y después la cuenta; si la copia no se puede borrar, la cuenta se queda. Los
  datos del móvil no se tocan.

## Copia en la nube

Una copia por cuenta en `users/{uid}/backup/latest`, con el mismo JSON que «Exportar copia» (sin
sangrías), **cifrado en el móvil** (ver [Cifrado](#cifrado)):

| Campo | Tipo | Contenido |
| --- | --- | --- |
| `revision` | string | UUID de la subida que escribió la copia |
| `savedAt` | timestamp | cuándo se subió |
| `records` | integer | cuentas + deudas + grupos + metas + suscripciones |
| `data` | array de strings | el JSON cifrado (Base64) en trozos de ≤ 1.400 bytes |
| `keyId` | string | UUID de la clave de datos con la que se cifró |
| `kdfSalt`, `kdfIterations` | string, integer | sal (Base64) e iteraciones de PBKDF2 |
| `wrappedKey` | string | la clave de datos cifrada con la clave derivada de la frase (Base64) |

Las copias anteriores al cifrado no tienen `keyId` y su `data` es el JSON en claro: la app las sigue
leyendo y la siguiente subida ya va cifrada.

El JSON va troceado porque Firestore indexa todos los campos y limita los strings indexados a 1.500
bytes. Un documento admite 1 MiB; la app rechaza datos de más de ~900 KB («ocupan demasiado»).

**Cuándo sube** (`CloudBackupSync`, mientras la app está abierta):

1. Al iniciar sesión y al abrir la app, lee solo los metadatos de la nube (todo menos `data`).
   - Si este móvil no tiene la clave: con copia cifrada pide la frase para abrirla (o empezar de cero);
     si no, pide crear una. No sube nada hasta tenerla.
   - Sin copia: sube la de este móvil.
   - Con la revisión que este móvil subió la última vez: sigue sin preguntar. Al cerrar sesión se olvida,
     así que volver a iniciar sesión siempre pregunta (los datos pueden haber cambiado mientras tanto).
   - Con otra revisión (otro móvil, otra cuenta en este móvil, móvil restaurado): **pregunta** qué
     datos usar, la copia de la nube o los de este móvil. No sube nada hasta que se elige.
2. Cada cambio en los datos (Room, 5 s de margen) sube la copia entera con una revisión nueva. Antes
   comprueba que la nube sigue con la revisión de este móvil; si otro móvil subió entretanto, vuelve a
   preguntar en lugar de pisarla. Tampoco sube nunca sin preguntar un móvil vacío (`records` = 0, p. ej.
   tras «Borrar todos los datos») encima de una copia con datos.
3. Los fallos (sin conexión, Firestore caído) se reintentan solos, de 30 s a 15 min, y con «Reintentar» /
   «Guardar ahora».

La última revisión subida y la clave de datos se guardan en `patrimonio-cloud.preferences_pb`, también
fuera del backup automático de Android: un móvil restaurado vuelve a pedir la frase y a preguntar antes de
subir. Cerrar sesión borra ambas.

## Cifrado

Cifrado de extremo a extremo con [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin)
0.5.0 (`BackupCrypto`): JDK en Android, CryptoKit + CommonCrypto en iOS. Ni Firebase ni el dueño del
proyecto pueden leer las copias.

- **Clave de datos**: 256 bits aleatorios por cuenta. Cifra el JSON con **AES-256-GCM**, ligado a
  `uid` y `keyId` como datos asociados (una copia no se puede mover a otra cuenta).
- **Frase de cifrado**: la elige el usuario (≥ 8 caracteres) y es independiente de la contraseña de la
  cuenta (sirve igual con Google). **PBKDF2-HMAC-SHA256, 600.000 iteraciones** (OWASP), sal aleatoria de
  16 bytes → clave que cifra la clave de datos (AES-GCM). La frase nunca sale del móvil.
- **En el móvil** se guarda la clave de datos sin cifrar, con la misma protección que la base de datos
  local, que tiene los mismos datos. Así la frase solo se pide al iniciar sesión.
- **Cambiar la frase** vuelve a cifrar solo la clave de datos, con sal nueva; no hace falta la anterior
  porque el móvil ya tiene la clave.
- **Frase olvidada**: con otro móvil con la sesión abierta, basta con cambiarla allí. Si no, «Empezar de
  cero» genera una clave nueva y sustituye la copia de la nube por los datos de este móvil.
- `cryptography-kotlin` 0.6.x está compilada con Kotlin 2.3; hasta subir de Kotlin se queda en 0.5.0.
