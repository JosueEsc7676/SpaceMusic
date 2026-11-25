## 24/11/2026 · iPhone 3.31, compilación 67: recuperar diseño original, ensayo continuo

- El usuario rechazó la tarjeta de 65: se elimina su extensión, la capacidad NSSupportsLiveActivities y cualquier creación de tarjetas. Se conservan el tipo legado y un cierre único para terminar tarjetas anteriores. Vuelven la letra sobre la portada, posiciones Libre/Flotante, estilos y vista previa con texto.
- IPA experimental: https://spacemusic.tail8ccfdf.ts.net/app/SpaceMusic-67.ipa. Código cce5846; 4662964 bytes; SHA256 e39b9540d3f3efbd4d0b8615751c81b60806818ef3c07d1687aa0823cb06b4fb.
- Actions https://github.com/JosueEsc7676/SpaceMusic/actions/runs/45522195633 correcto: tests Swift (incluidos orden de versos, huecos y recorte), publicador, Xcode Release e IPA. Cuatro pruebas locales del publicador correctas. ZIP/plist correctos, CFBundleVersion67, mismo ID de app y ausencia de LetraBloqueoWidget.appex comprobados.
- Nuevo ensayo: un vídeo continuo contiene todos los versos hasta el fin de la canción; los cambios de línea y palabra son cuadros del archivo, no nuevos artworkID. Muestrea el reloj al solicitar el archivo y recorta el tiempo empleado en codificar. Reancla ante búsqueda, pausa/reanudación o entrada en segundo plano. Sin modificar audio ni API.
- Render progresivo, resolución de trabajo1024 aislada con TaskLocal, MP4 de1080x1440, límite de25s de preparación y15min de secuencia. Fallos conservan imagen con letra y se informan en ajustes. El límite incluye CPU por cuadro; no se detiene audio para generar arte.
- LIMITACIÓN ABIERTA: MediaPlayer no publica un reloj/seek de la animación que permita garantizar sincronización con el audio. iOS puede iniciar/repetir/fijar el vídeo. La exportación compensadora tampoco elimina su latencia de arranque. No hay prueba de teléfono físico. No afirmar que el parpadeo, pausa, bloqueo o sincronía estén solucionados visualmente: son pendientes del prototipo.
- Referencia primaria revisada: https://developer.apple.com/documentation/mediaplayer/providing-animated-artwork-for-media-items.md (sin límite técnico de tamaño, recomienda bucles cortos; solo URL local, timeout30s y posible previa fija en ahorro/temperatura/accesibilidad).
- Publicado como experimental con manifest67/cce5846: descarga200 completa con SHA idéntico, Range206 válido, API200 y spacemusic activo. Sin reinicio de Java ni cambio de claves/firma: proceso ad hoc existente. Backups .anterior65, IPA58 y65 conservados. Para activar diseño: Con letra + Portada en toda la pantalla; se conservan las preferencias de posición.

## 24/11/2026 · iPhone 3.30, compilación 65: texto independiente del fondo

- Publicado https://spacemusic.tail8ccfdf.ts.net/app/SpaceMusic-65.ipa. Código a3acc40; 4689278 bytes; SHA256 66a0431dbba3ac5570f7c2830545d90ed352aa821372b53d1a9d2a3980715cd7.
- Actions https://github.com/JosueEsc7676/SpaceMusic/actions/runs/45519785280 correcto: pruebas Swift, pruebas del publicador, compilación Release, firma ad hoc de extensión y creación del IPA. Cuatro pruebas locales del publicador correctas.
- La prueba del usuario confirmó que 58 aún parpadeaba. Se abandona el reemplazo de video por verso: en pantalla completa un verso ya no vuelve a publicar Now Playing ni cambia imagen, video o artworkID. La identidad depende únicamente de canción, fuente de portada y estilo. Se conserva el botón para Con letra y Original.
- Cambio de presentación explícito: los versos de pantalla completa pasan a una tarjeta de ActivityKit/WidgetKit; ya no se incrustan sobre el fondo. En modo compacto se conserva el diseño anterior sobre la portada. La tarjeta actualiza solo texto y pausa, con altura fija156, sin alertas, APNs ni servidores adicionales. Título/artista de Now Playing siguen siendo reales.
- Se verificaron en el IPA los dos bundles: com.bpm.spacemusic.ios (65, NSSupportsLiveActivities=true) y PlugIns/LetraBloqueoWidget.appex (65, com.bpm.spacemusic.ios.letra, punto de extensión WidgetKit y ejecutable presente). La firma sigue el procedimiento ad hoc existente; al instalar deben conservarse y volver a firmarse las extensiones con la herramienta habitual. No se crearon certificados ni claves.
- Uso: permitir Actividades en vivo para SpaceMusic y comenzar la escucha con la app abierta antes de bloquear el teléfono. ActivityKit puede limitar la frecuencia y solo permite iniciar localmente en primer plano; el estado se muestra en Personalizar pantalla bloqueada. Sin permiso/extensión, la portada se conserva pero la tarjeta no puede mostrarse. El sistema decide la disposición de las tarjetas.
- Descarga completa pública200 con SHA idéntico, Range206 ZIP correcto, manifiesto65/a3acc40, API200 y servicio activo. Publicado con publicar-ios.py sin reiniciar ni cambiar Java. Backups .anterior (58) y IPA58 numerado conservados.
- Pendiente imprescindible: validación visual de pantalla bloqueada en iPhone físico. La compilación y los tests no demuestran la ausencia de todo parpadeo del compositor ni garantizan sincronía continua de ActivityKit.

## 24/11/2026 · iPhone 3.29, compilación 58: botón de pantalla completa conservado

- IPA publicado: https://spacemusic.tail8ccfdf.ts.net/app/SpaceMusic-58.ipa. Commit 0307f38; 4650202 bytes; SHA256 c891802ec00a60d8d1fe9ec33705000e3d8c2105b6401cbd9e4bf8e171f697b9.
- Actions https://github.com/JosueEsc7676/SpaceMusic/actions/runs/45516213673 correcto: pruebas Swift, publicador, Xcode Release e IPA. Cuatro pruebas locales del publicador correctas.
- Corrige la desviación funcional de 55: Portada en toda la pantalla vuelve a funcionar con letra y Original, con la misma preferencia guardada. Continúa sobre 3.28, conservando palabras pendientes, precarga y controles de tareas antiguas.
- La imagen de respaldo no cambia entre versos en pantalla completa. El clip se entrega después de finalizar su MP4, con previa idéntica al primer cuadro visible. El anterior se conserva hasta tener el siguiente. Palabras dentro del clip, secuencia de cuadros perezosa (sin acumular cientos de CGImage), invalidación al buscar/pausar/reanudar/cambiar sincronía y caché por estilo/contenido/portada.
- Publicado sin reiniciar ni sustituir Java, con el publicador verificado. API200, manifiesto58/0307f38, IPA200 con SHA íntegro y Range206 correcto. Archivos anteriores conservados como .anterior (55); IPA55 sigue disponible.
- Limitación: no hay prueba visual en iPhone físico. Las pruebas y Xcode no demuestran por sí solas la ausencia de parpadeo del compositor de iOS ni sincronía perfecta. No se cambian claves ni identidad de app; el IPA usa el procedimiento ad hoc existente para instalación con las herramientas de firma habituales.

## 24/11/2026 · iPhone 3.28, compilación 55

- Código e75075f; Actions https://github.com/JosueEsc7676/SpaceMusic/actions/runs/45513662255 correcto: pruebas Swift, publicador, Xcode Release, IPA y publicación GitHub.
- Descarga: https://spacemusic.tail8ccfdf.ts.net/app/SpaceMusic-55.ipa (4633032 bytes). SHA256 787c8717501efb01e8aee45be8c7498c825df949133330ae6dca7a51393cda57.
- Se revisó la versión anterior 3.27/54 (ed26b57): reemplazaba un video por verso. Ahora la letra usa artwork estático HD ampliable al tocarlo; el fondo animado queda para Original. Esto evita reiniciar la superficie de video entre versos, pero no promete animación continua de letra ni el mismo fondo animado con letra. Las transiciones las decide iOS.
- Las palabras limitadas se reintentan comparando con lo realmente dibujado. Se usa reloj monotónico y se deduplican dibujos pendientes. Se conserva la precarga y los estilos, se retira el selector de entradas de video incompatible con esta vía.
- Publicado mediante publicar-ios.py en app/ios-staging-e75075f-55. Manifiesto 55/e75075f; descarga pública200 con SHA idéntico, Range206 ZIP correcto, página de instalación200, API200 y servicio activo. No se reinició ni sustituyó Java. Los archivos anteriores se conservan como .anterior; IPA54 sigue disponible.
- IPA creado con la misma identidad com.bpm.spacemusic.ios y el procedimiento existente (ad hoc, para volver a firmar con SideStore/AltStore/Sideloadly). No se generaron claves ni se cambió la firma de distribución.
- Pendiente: comprobar visualmente en iPhone físico con pantalla bloqueada, al tocar para ampliar, pausa/reanudación, salto y cambio de canción. Las pruebas automáticas no verifican la animación del sistema.

# Publicación de iPhone: 13/11/2026

## Actualización 1.25: controlar otro dispositivo y accesos de Mensajes

- App compilada desde `5753835`, compilación **25**.
- [GitHub Actions correcto](https://github.com/JosueEsc7676/SpaceMusic/actions/runs/43677310117): pruebas, Xcode Release e IPA.
- Artefacto descargado por el dueño con su sesión de GitHub; SHA-256 del ZIP igual al de GitHub (`e381a753…`). IPA: compilación 25, `com.bpm.spacemusic.ios`, ZIP íntegro.
- [IPA publicado](https://spacemusic.tail8ccfdf.ts.net/app/SpaceMusic-25.ipa). SHA-256: `b0bcbb3477a4c9e3d3b52f46afa62c7b44389498a2de816dd6a2a34261db9b7f`.
- Publicado con `publicar-ios.py` sobre el servidor 3.7 (que ya incluye `IosAppsController` en master). Manifiesto 25, página HTTP 200, IPA parcial 206 y descarga completa con SHA-256 idéntico.
- Pendiente en iPhone: control remoto con otro dispositivo real y los tres accesos de Mensajes con «Atrás».

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
