# Publicación de iPhone: 13/11/2026

## Actualización 1.23: SyncPlay y navegación

- App compilada desde `597d344`, compilación **23**.
- [GitHub Actions correcto](https://github.com/JosueEsc7676/SpaceMusic/actions/runs/43524578230): 15 pruebas XCTest, pruebas del publicador y Xcode Release.
- [IPA publicado](https://spacemusic.tail8ccfdf.ts.net/app/SpaceMusic-23.ipa).
- SHA-256: `d1a32eaf63ff7e29246cae311782eb4f5f8ec7e0ace78f18663526f13d48b455`.
- Registro de descargas plegable, desplazamiento propio y claves de Spotify accesibles desde la barra superior; detalle de usuarios mediante navegación directa; menú de canciones agrupado.
- Dispositivos antes del corazón en el mini reproductor; selector de salida nativo y SyncPlay; accesos sociales en Mensajes, creación e invitación por chat y tarjeta para abrir la sala.
- Letra ampliada con cabecera, fondo de portada, controles propios y desplazamiento sin rebote.
- Publicado con `publicar-ios.py` sin cambiar ni reiniciar el backend 3.5. Página HTTP 200, IPA parcial HTTP 206, manifiesto 23 y descarga completa con SHA-256 idéntico al de GitHub. `/api/auth/estado` sigue respondiendo correctamente.
- Pendiente en iPhone: navegación táctil, salidas Bluetooth/AirPlay, dos cuentas en SyncPlay y seguimiento visual de letras. Las pruebas de enlaces no sustituyen estas comprobaciones.

## Publicación anterior 1.21

- App: **1.21**, compilación **21**, commit **676dbab**.
- GitHub Actions: <https://github.com/JosueEsc7676/SpaceMusic/actions/runs/43443743135> (pruebas Swift, pruebas del publicador y Xcode correctos).
- Instalación: <https://spacemusic.tail8ccfdf.ts.net/app/ios.html>.
- IPA fijo: <https://spacemusic.tail8ccfdf.ts.net/app/SpaceMusic-21.ipa>.
- Manifiesto: `/app/version-ios.json`.
- SHA-256: `5b8c97258f5d1d981f0b61677058cc320c484d62c5d25c1918e5f012ef87d1a4`.

## Qué se publicó

El servidor en producción es 3.5 y el código Java de esta rama sigue siendo anterior. **No compilar esta rama completa y sustituir con ella el servidor de producción.** Se añadió únicamente `IosAppsController`, con rutas exactas para IPA, HTML, CSS y JS; el controlador de apps existente solo admitía APK y JSON.

`agregar-rutas-ios.py` compila esa clase contra las librerías del JAR instalado y prepara `spacemusic.jar.new`. Comprueba que todas las entradas originales permanezcan idénticas y que la única entrada nueva sea el controlador. Se activó con el script del servidor `desplegar-con-reversion.sh`, que conserva el respaldo `spacemusic.jar.antes-ios-descargas-676dbab-20261113` y revierte si no arranca sano.

Los archivos públicos se subieron a `/home/admin123/spacemusic/app/ios-staging-676dbab` y se publicaron con `publicar-ios.py`. Este valida ZIP, identificador de aplicación, número de compilación y SHA-256 antes de publicar el manifiesto. La copia numerada es inmutable; `SpaceMusic.ipa` es el enlace compatible para descargar la última.

## Verificación real

- 12 pruebas XCTest en GitHub y compilación Release con Xcode correctas.
- 4 pruebas del publicador correctas.
- 2 pruebas MockMvc del controlador correctas (HTML/MIME, rango 206, rutas no autorizadas y archivo ausente).
- Servicio `spacemusic` activo; `/api/auth/estado` responde y `/app/novedades.json` sigue mostrando 3.5.
- Página de instalación pública: HTTP 200 con `text/html`.
- Petición `Range: bytes=0-3` al IPA: HTTP 206, cuatro bytes y firma ZIP correcta.
- IPA completo descargado desde el servidor: SHA-256 idéntico al de GitHub.
- JavaScript y CSS servidos públicamente; JavaScript coincide con el del repositorio.

Las salas con dos cuentas, la instalación en iPhone, el toque de los avisos y la reproducción del despertador siguen pendientes de probar en dispositivo. Con iOS suspendido, el despertador muestra una notificación que se toca para iniciar música.
