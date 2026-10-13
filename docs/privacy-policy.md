# Política de privacidad de Patrimonio

> **BORRADOR para revisar.** Está escrito a partir de lo que hace la app hoy. Antes de publicarlo, revisa cada
> punto, completa los datos entre corchetes y decide dónde alojarlo (Play Console y App Store Connect piden
> una URL pública). Si la app cambia lo que hace con los datos, hay que actualizarlo. No es asesoramiento
> legal.

**Última actualización:** [fecha]
**Responsable:** [nombre o razón social], [dirección o país de contacto]
**Contacto de privacidad:** [correo de contacto]

Patrimonio es una app para llevar el seguimiento de tu patrimonio: cuentas, deudas, inversiones, metas y
movimientos. Esta política explica qué datos trata, para qué y qué derechos tienes.

## En resumen

- Tus datos viven **en tu móvil**. Crear una cuenta es opcional.
- La app **no tiene anuncios, ni analítica, ni rastreo**, y no vende datos.
- Si activas la copia en la nube, se sube **cifrada en tu móvil** con una frase que solo tú conoces:
  ni el desarrollador ni el proveedor de la nube pueden leerla.
- Para actualizar precios, la app consulta servicios públicos y solo les envía **identificadores de
  instrumentos**, no tus datos personales ni tus cantidades.

## Datos que se guardan en tu dispositivo

Todo lo que introduces: cuentas, deudas, grupos, metas, posiciones de inversión, movimientos, comisiones y
ajustes (moneda, preferencias). Se guarda en una base de datos local de la app. Nada de esto sale del móvil
salvo lo que se explica en las secciones siguientes.

Si activas el bloqueo con PIN, se guarda en el dispositivo el PIN como huella irreversible (hash) y tus
ajustes de bloqueo. La biometría (huella, cara, Face ID) la gestiona el sistema operativo; la app solo
recibe «reconocido» o «no reconocido» y nunca accede a tus datos biométricos.

**Copia de seguridad del sistema.** En Android, la copia automática del sistema (Google) puede incluir los
datos locales de la app en tu cuenta de Google, según tu configuración del móvil. En iOS, las copias de
iCloud o del ordenador pueden hacer lo mismo. Eso lo controlas tú en los ajustes del sistema. La sesión
de la cuenta y el vínculo con la copia en la nube se excluyen de la copia automática de Android.

## Cuenta en la nube (opcional)

Puedes crear una cuenta para guardar una copia de tus datos y recuperarla en otro dispositivo. Si lo haces:

- **Autenticación.** Usamos Firebase Authentication (Google). Se tratan tu **correo electrónico**, un
  **identificador de usuario** y, si usas la contraseña, un hash de ella gestionado por Firebase. Si inicias
  sesión con Google (en Android), Google nos facilita tu correo y, si los tienes, tu nombre y tu foto de perfil.
  Finalidad: identificarte y que sólo tú accedas a tu copia.
- **Copia de seguridad.** Se guarda en Cloud Firestore (Google), en una sola copia por cuenta. El contenido
  se **cifra en tu dispositivo** (AES-256-GCM) con una clave que se protege con una frase de cifrado que tú
  eliges. La frase no se envía a ningún sitio. El desarrollador y Google solo ven datos cifrados, más el
  momento de la última subida y el número de registros. **Si olvidas la frase y no tienes otro dispositivo con
  la sesión abierta, la copia no se puede recuperar.**
- **Dónde.** Los servidores de Firebase/Google se encuentran en [región, p. ej. Madrid (europe-southwest1) o
  la UE; confirmar según la base de datos creada]. Google actúa como encargado del tratamiento.
- **Base jurídica (RGPD).** La ejecución del servicio que pides al crear la cuenta (art. 6.1.b).

Si no creas una cuenta, nada de esto ocurre.

## Consultas de precios y cambios de divisa

Para mostrar el valor de tus inversiones, la app consulta, desde tu dispositivo, estos servicios de terceros:

| Servicio | Para qué | Qué se envía |
| --- | --- | --- |
| Morningstar | Precios de fondos y otros instrumentos | Identificadores del instrumento (p. ej. ISIN) |
| Yahoo Finance | Búsqueda y precios de acciones y ETF | Ticker o texto de búsqueda |
| CoinGecko | Precios de criptomonedas | Identificador de la moneda |
| Frankfurter (BCE) | Tipos de cambio | Divisas |

No se envían tus cantidades, saldos, correo ni identificador de cuenta. Como en cualquier conexión de
internet, estos servicios ven la **dirección IP** del dispositivo y tienen sus propias políticas de
privacidad. [Revisar y enlazar las de cada uno.]

## Lo que no hacemos

- No usamos publicidad ni SDK de publicidad.
- No usamos analítica ni informes de uso propios. [Si se añade Crashlytics u otro servicio, actualizar.]
- No rastreamos entre apps ni sitios web.
- No vendemos ni cedemos tus datos a terceros para sus fines.

## Cuánto tiempo se guardan

- **En el dispositivo**: hasta que borres los datos desde la app o la desinstales.
- **En la nube**: mientras la cuenta exista. Al borrar la cuenta se elimina la copia y la cuenta.

## Tus derechos y cómo ejercerlos

Puedes acceder a tus datos, rectificarlos, suprimirlos, limitar u oponerte a su tratamiento, y pedir su
portabilidad. Además:

- **Exportar**: Ajustes → exportar copia, en un archivo con tus datos.
- **Borrar los datos del móvil**: Ajustes → borrar todos los datos.
- **Borrar tu cuenta y la copia en la nube**: dentro de la app, en Ajustes → Cuenta → *Borrar cuenta*.
  Primero se elimina la copia en la nube y después la cuenta.
- **Por correo**: si no puedes entrar en la app, escribe a [correo de contacto] desde el correo de la cuenta
  y la eliminaremos en un plazo razonable [indicar, p. ej. 30 días].

Si consideras que tus derechos no se han atendido, puedes reclamar ante la autoridad de protección de datos
de tu país (en España, la Agencia Española de Protección de Datos, aepd.es).

## Menores

La app no está dirigida a menores de [edad, p. ej. 16] años.

## Cambios en esta política

Si cambia, publicaremos aquí la nueva versión con su fecha y, si el cambio es relevante, lo avisaremos
en la app.

## Contacto

[nombre o razón social] · [correo de contacto]
