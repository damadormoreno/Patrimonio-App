# Bloqueo con PIN y biometría

Ajustes → **Seguridad**. Es opcional y va por dispositivo.

## Cómo funciona

- **PIN de 4 cifras.** Se pide al abrir la app y al volver a ella tras más del **bloqueo automático**
  (30 s, 1 min o 5 min) en segundo plano. El tiempo se mide con un reloj monótono: cambiar la hora del
  móvil no se salta el bloqueo.
- **Huella / cara (Android) o Face ID / Touch ID (iOS).** Se activa tras reconocerte una vez y abre la app
  en lugar del PIN; el PIN sigue valiendo siempre. Al bloquearse, el aviso del sistema sale solo.
- **PIN equivocado.** Los 4 primeros fallos son libres; desde el 5.º hay que esperar 30 s, y cada fallo
  más dobla la espera hasta 15 min. La cuenta sobrevive a cerrar la app. La biometría no espera.
- **¿PIN olvidado?** No se puede recuperar. «¿Has olvidado el PIN?» (tras un fallo) cierra la sesión, borra
  los datos del móvil y quita el bloqueo; se recuperan desde la copia en la nube o una exportación.
- **Selector de apps.** Con el bloqueo puesto, Android 13+ no muestra la miniatura de la app en recientes
  (las capturas de pantalla siguen funcionando) e iOS la tapa al salir.

## Qué guarda

`patrimonio-lock.preferences_pb`: el PIN como hash PBKDF2-HMAC-SHA256 (sal aleatoria, 100.000
iteraciones), si la biometría está activa, el bloqueo automático y los fallos. Entra en el backup
automático de Android junto con los datos que protege: un móvil restaurado se abre con el mismo PIN.

## Lo que no hace

Es un bloqueo de la interfaz: esconde la app a quien coja el móvil desbloqueado. La base de datos no se
cifra con el PIN (con 4 cifras tampoco protegería contra quien pueda leer el almacenamiento privado de la
app, que ya requiere un móvil rooteado o con jailbreak).

## Plataformas

- Android: `BiometricPrompt` (androidx.biometric, biometría débil: no descifra nada). `MainActivity` es
  una `FragmentActivity` porque el prompt la necesita.
- iOS: `LAContext` con `LAPolicyDeviceOwnerAuthenticationWithBiometrics`. `Info.plist` lleva
  `NSFaceIDUsageDescription`.
