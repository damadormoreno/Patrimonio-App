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

## Qué guarda la app

- **Sesión**: uid, email y tokens en su propio DataStore (`patrimonio-auth.preferences_pb`). Ese
  archivo queda fuera del backup automático de Android, así que al restaurar en otro móvil hay que
  volver a iniciar sesión.
- **Token**: dura una hora y se renueva solo unos minutos antes de caducar. Si Firebase rechaza la
  renovación (contraseña cambiada, cuenta borrada en otro sitio), la app cierra la sesión.
- **Borrar cuenta**: Firebase exige un inicio de sesión reciente, por eso pide la contraseña. Los datos
  del móvil no se tocan.
