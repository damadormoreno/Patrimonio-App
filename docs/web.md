# Web pública (política de privacidad y borrado de cuenta)

Las tiendas piden dos URLs públicas: la política de privacidad y una página donde explicar cómo borrar la
cuenta sin tener la app. Son HTML estático, sin JavaScript ni servicios externos, y se publican en Firebase
Hosting del proyecto `patrimonio-116ff` (gratis, mismo proyecto que la cuenta en la nube).

## Qué se publica y dónde

| Página | Fichero | URL |
| --- | --- | --- |
| Inicio | `hosting/public/index.html` | `https://patrimonio-116ff.web.app/` |
| Política de privacidad | `hosting/public/privacidad.html` | `https://patrimonio-116ff.web.app/privacidad` |
| Borrar cuenta y datos | `hosting/public/borrar-cuenta.html` | `https://patrimonio-116ff.web.app/borrar-cuenta` |

- `hosting/public/styles.css` y `hosting/public/fonts/` (Instrument Serif y Manrope, licencia OFL, copiadas de
  los recursos de la app) dan estilo a las tres. `404.html` y `favicon.svg` completan el sitio.
- `firebase.json` (solo `hosting`) activa `cleanUrls` (sin `.html`), desactiva la barra final y añade cabeceras de
  seguridad: `Content-Security-Policy` estricta (sin scripts, ni estilos o fuentes que no sean propios),
  `X-Content-Type-Options` y `Referrer-Policy`. Por eso las páginas no pueden llevar estilos ni scripts en
  línea ni cargar nada de fuera: si algo lo necesita, hay que cambiar la CSP a propósito.
- `.firebaserc` fija el proyecto por defecto.
- Las URLs de `/privacidad` y `/borrar-cuenta` son las que se pegan en Play Console (política de privacidad y URL de
  eliminación de la cuenta en *Seguridad de los datos*) y en App Store Connect (política). Ver
  [release.md](release.md).
- La app enlaza la política desde «Acerca de» con `PRIVACY_POLICY_URL` y el correo con `CONTACT_EMAIL`, ambos en
  `composeApp/src/commonMain/kotlin/com/denebapps/patrimonio/ui/screens/about/AboutConfig.kt`.

## Probar en local

Sin instalar nada, abre los ficheros de `hosting/public/` en el navegador (los enlaces internos usan URLs
limpias como `/privacidad`, que solo resuelven bien con un servidor). Para verlo tal cual se servirá, con
`cleanUrls` y las cabeceras:

```
npx --yes firebase-tools emulators:start --only hosting
```

y abre `http://localhost:5000/privacidad`.

## Desplegar

Hace falta una cuenta de Google con acceso al proyecto `patrimonio-116ff`. La primera vez:

```
npx --yes firebase-tools login
```

y, desde la raíz del repositorio, cada vez que cambie algo en `hosting/public/`:

```
npx --yes firebase-tools deploy --only hosting
```

El despliegue no está automatizado en CI: lo haces tú a mano. Comprueba después las dos URLs de la tabla.

## Mantener la política al día

La política describe lo que la app hace de verdad. Si la app empieza a recoger, enviar o guardar algo nuevo
(analítica, informes de fallos, otro proveedor de cotizaciones, nuevos datos de la cuenta, otra región de la base
de datos...), hay que actualizar `privacidad.html` **antes** de publicar esa versión de la app, cambiar la fecha de
«Última actualización» y volver a desplegar. Revisa también los textos de [release.md](release.md) sobre *Data
safety*. Esto no es asesoramiento legal.
