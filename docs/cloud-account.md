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

- **Sesión**: uid, email y tokens en su propio DataStore (`patrimonio-auth.preferences_pb`). Ese
  archivo queda fuera del backup automático de Android, así que al restaurar en otro móvil hay que
  volver a iniciar sesión.
- **Token**: dura una hora y se renueva solo unos minutos antes de caducar. Si Firebase rechaza la
  renovación (contraseña cambiada, cuenta borrada en otro sitio), la app cierra la sesión.
- **Borrar cuenta**: Firebase exige un inicio de sesión reciente, por eso pide la contraseña. Primero
  borra la copia en la nube y después la cuenta; si la copia no se puede borrar, la cuenta se queda. Los
  datos del móvil no se tocan.

## Copia en la nube

Una copia por cuenta en `users/{uid}/backup/latest`, con el mismo JSON que «Exportar copia» (sin
sangrías):

| Campo | Tipo | Contenido |
| --- | --- | --- |
| `revision` | string | UUID de la subida que escribió la copia |
| `savedAt` | timestamp | cuándo se subió |
| `data` | array de strings | el JSON en trozos de ≤ 1.400 bytes UTF-8 |

El JSON va troceado porque Firestore indexa todos los campos y limita los strings indexados a 1.500
bytes. Un documento admite 1 MiB; la app rechaza datos de más de ~900 KB («ocupan demasiado»).

**Cuándo sube** (`CloudBackupSync`, mientras la app está abierta):

1. Al iniciar sesión y al abrir la app, lee solo `revision` y `savedAt` de la nube.
   - Sin copia: sube la de este móvil.
   - Con la revisión que este móvil subió la última vez: sigue sin preguntar.
   - Con otra revisión (otro móvil, otra cuenta en este móvil, móvil restaurado): **pregunta** qué
     datos usar, la copia de la nube o los de este móvil. No sube nada hasta que se elige.
2. Cada cambio en los datos (Room, 5 s de margen) sube la copia entera con una revisión nueva. Antes
   comprueba que la nube sigue con la revisión de este móvil; si otro móvil subió entretanto, vuelve a
   preguntar en lugar de pisarla.
3. Los fallos (sin conexión, Firestore caído) se reintentan solos, de 30 s a 15 min, y con «Reintentar» /
   «Guardar ahora».

La última revisión subida se guarda en `patrimonio-cloud.preferences_pb`, también fuera del backup
automático de Android: un móvil restaurado vuelve a preguntar antes de subir.
