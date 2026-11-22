# SpaceMusic: resumen para quien continúe el trabajo (Codex u otra persona)

Estado al 21 de noviembre de 2026, versión **3.20** (servidor y web), **Android 2.8** (código 19), **Android TV 1.2** (código 3) e **iPhone 1.48** (rama `app-ios`, la compilación de GitHub numera la versión).
El detalle de cada versión está en `CAMBIOS.md` (servidor, web y Android) y en `CAMBIOS.md` de la rama `app-ios` (iPhone).

## 1. Qué es y dónde está cada parte

| Parte | Dónde | Tecnología |
|---|---|---|
| Servidor + API | `src/main/java/com/bpm/music/**` | Spring Boot 3, Java 21, MySQL (H2 en pruebas), JWT |
| Web | `src/main/resources/static/` (`js/views/*.js`, `css/noir.css`) | JavaScript sin framework (módulos ES), se sirve desde el mismo jar |
| Android (teléfono) | `android-movil/` | Vista web (`MainActivity`) + servicios nativos; se compila **sin Gradle** con `android-movil/compilar.sh` |
| Android TV | `android-tv/` (nativo) + `static/tv.html`, `js/tv.js`, `css/tv.css` (interfaz) | Vista web a pantalla completa que carga `/tv.html` del servidor y le pasa las teclas del control; se compila con `android-tv/compilar.sh`. Casi todo cambio de la TV es web: se publica con el jar, sin reinstalar la APK |
| iPhone | Repositorio aparte `untitled-ios`, rama `app-ios` (remoto `Espacemusic` → GitHub `JosueEsc7676/SpaceMusic`) | SwiftUI, sin firmar; GitHub Actions (`.github/workflows/ios.yml`) compila el `.ipa` en cada push |
| Descargas de música | `scripts/descargar`, `scripts/convertir-enlace.py`, `scripts/buscar-audio.py` | bash + spotdl + yt-dlp (en el servidor, en `~/spacemusic/spotdl-env`) |

Android carga la web: **toda función nueva de la web ya está en Android**. El iPhone es nativo: cada función nueva de la API hay que llevarla a mano a SwiftUI.

## 2. Compilar, probar y publicar

- **Java**: Liberica 21 (`C:/Users/Josue/.jdks/liberica-full-21.0.8`). **Maven**: el que trae IntelliJ (`.../plugins/maven/lib/maven3/bin/mvn`).
- **Jar**: `mvn -DskipTests package` → `target/spacemusic.jar`. Si una prueba falla, `mvn package` **no** rehace el jar.
- **Pruebas del servidor**: `mvn test`. `SoporteSeguridadTest.copiaDeSeguridadSeCrea` falla si el disco C: del PC está casi lleno (no es un error del código).
- **Servidor local**: `java -jar target/spacemusic.jar --spring.profiles.active=dev --server.port=8090 "--spring.web.resources.static-locations=file:src/main/resources/static/"` (usuario `admin`, contraseña de prueba `prueba123`). Si está encendido bloquea el jar: apagarlo antes de compilar.
- **Pruebas en navegador** (con el servidor local encendido):
  - `SM_USUARIO=admin SM_CLAVE=prueba123 node pruebas/navegador.mjs http://localhost:8090` → 11/11.
  - `SM_USUARIO=admin SM_CLAVE=prueba123 node pruebas/control-remoto.mjs http://localhost:8090` → 14/14.
  - También están `pruebas/syncplay-sincronia.mjs` (8/8).
- **APK de Android**: `VERSION_CODE=N VERSION_NAME=X bash android-movil/compilar.sh`. Después:
  1. copiar `android-movil/SpaceMusic.apk` a `src/main/resources/static/app/SpaceMusic.apk`;
  2. subir `movil.code` y `movil.nombre` en `static/app/version.json`;
  3. comprobar con `aapt2 dump badging`: si la compilación falla, queda la APK vieja.
- **Publicar el servidor** (solo con permiso del dueño):
  1. `scp target/spacemusic.jar servidor:spacemusic/spacemusic.jar.new` y comprobar el sha256;
  2. `ssh servidor "bash ~/spacemusic/desplegar-con-reversion.sh <etiqueta>"` (vuelve solo a la versión anterior si la API no responde).
- **Publicar el iPhone**:
  1. al hacer push a `app-ios` se compila y se sube al release `ios-latest` de GitHub;
  2. para ofrecerlo desde el servidor: bajar `SpaceMusic.ipa` del release y usar `publicar-ios.py` en `~/spacemusic/app` (ver `ios-staging-*`);
  3. queda en `https://<servidor>/app/ios.html`.
- **Notas de la versión**:
  - `CAMBIOS.md` (técnico);
  - `src/main/resources/static/app/novedades.json` (lo que ve la gente: version, titulo, puntos, anteriores).

## 3. Lo que se hizo en las últimas versiones

**3.20** (esta): modo fiesta (TV + `fiesta.html` sin cuenta), «Tu semana», letra traducida (MyMemory, caché en disco) y karaoke en la TV, avisos al instante en Android con UnifiedPush/ntfy (`Push.java`, `PushService`), descargas automáticas (wifi y cargando) y aviso de canciones nuevas de tus artistas. Detalle en `CAMBIOS.md`.

**3.18 y 3.19** (Codex): ver `CAMBIOS.md`.

**3.17**: Órbita con los colores reales de la portada (`paleta()`), opciones de letra nuevas (mostrar varias líneas / solo el verso / verso flotante, color, separación, alineación, brillo), letra centrada, barra flotante y acceso rápido al reproductor (↑ y «Reproduciendo» primero en el menú).

**3.16**: reproductor «Órbita» en la TV (dinámico, con los colores de la portada), modo «solo letra» a pantalla completa y letra configurable (normal/cursiva/elegante, tamaño, ordenada/desordenada). Todo en `tv.js` / `tv.css`; preferencias en `localStorage` `sm_tv_estilo`.

**3.15**: la app de TV
- **Entrar en la TV con el teléfono**: QR (generador propio `js/qr.js`), código de 6 caracteres o «misma red wifi». Servidor: `TvVinculoService` / `TvVinculoController` (`/api/auth/tv/*`, en memoria, 10 min, la TV recibe su propia sesión una sola vez). Teléfono: `#/tv/CÓDIGO` (`views/tv-vincular.js`) y aviso automático si hay una TV esperando en el mismo wifi; iPhone: Dispositivos → «Iniciar sesión en una TV». La contraseña con el control sigue disponible.
- **TV en «Dispositivos»** (como Spotify Connect): la TV aparece como «TV · nombre del televisor» y obedece órdenes; su pantalla Dispositivos controla a los demás (reproducir/pausar, anterior/siguiente, volumen, escuchar en la TV, pasar la música).
- **SyncPlay en la TV**: iniciar (con lo que sonaba), unirse con código o a una invitación, controles, QR y código en pantalla.
- Reutilización: el motor de SyncPlay está en `js/juntos-motor.js` (sin interfaz) y `remoto.js` avisa por el bus en vez de importar `ui.js`.
- Para probar en la TV real: `adb mdns services` encuentra el Mi TV (IP cambia por DHCP); `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` permite evaluar JS en su vista web. Para apuntarla a un servidor de prueba se usa el diálogo «Servidor» (sliders en el menú) y luego «Por defecto» para volver.

**3.14** (Codex): ver la sección «Continuación de audio» al final.

**3.13**
- **Notas de voz**:
  - Web: reproductor propio y vista previa antes de enviar (descartar / escuchar / enviar).
  - iPhone: lo mismo, con `VozMensajes` y `AVAudioPlayer`.
  - El servidor convierte WebM/Ogg a `.m4a` con ffmpeg (`MensajesService.aM4a`) y sirve los adjuntos con `Range`.
- **Fotos en los mensajes de la web**: sin la burbuja blanca (`.bubble.solo-foto`).
- **Android 2.7**:
  - `Revisar.java` (JobScheduler, cada ~15 min) avisa de mensajes con la app cerrada, usando el token guardado al salir (`MainActivity.guardarSesion`).
  - Notificación de conversación (`MessagingStyle` + atajo con `Person`): la foto de quien escribe va a la izquierda.
- **iPhone**:
  - avisos de mensajes como conversación (`INSendMessageIntent`, permiso `com.apple.developer.usernotifications.communication` en `SpaceMusic.entitlements`; la compilación lo incrusta con `codesign -s -`);
  - barra de pestañas siempre con fondo;
  - perfil: vibras compactas con símbolos y géneros en barras, sin «Music»;
  - al subir canciones se revisan las repetidas.
- **Descargas**:
  - `buscar-audio.py` saca el nombre por oEmbed (sirve aunque YouTube pida edad o sesión) y busca en Audius e Internet Archive;
  - `descargar` rota más clientes de YouTube por ronda y rescata fuera de YouTube lo que Spotify no pudo bajar.

**3.12**
- **Mensajes**:
  - fotos y voz cifradas (AES-256-GCM, `CifradoMensajes.cifrarBytes`);
  - eliminar (el propio para los dos, el recibido solo para mí);
  - bloquear (`BloqueoMensajes`);
  - foto de perfil en las notificaciones.
- **Inicio de sesión con Google, Facebook y Apple** (`OAuthService`, `OAuthController`). Los botones solo aparecen si el servidor tiene las claves; las cuentas nuevas quedan pendientes de aprobación.
- **Los mix automáticos usan los estilos de la IA** (`EstilosService.estilosDeIa`).
- **Letras**: LRCLIB exacta, luego aproximada, luego NetEase, luego syncedlyrics (`LetrasService.buscarTodas`).
- **Descargas de YouTube**: se completan solas con letra, portada HD, álbum y año (`CompletarDescargasService`, cada 10 min).

**3.8 a 3.11.2**
- Logo y animación de entrada.
- SyncPlay estable y con código para unirse.
- Control de otros dispositivos al estilo Spotify Connect (`remoto.js`, `ControlRemotoService`, WebSocket `/api/control/ws`).
- Portada original en HD (iTunes/Deezer).
- Dedicatorias con borrar.
- Letra en la pantalla bloqueada (Android `ReproductorService`, iPhone `ArteBloqueo.swift`): portada nítida, letra manuscrita centrada y sombra pareja.

## 4. Pendiente y límites conocidos

1. **Mensajes al instante con la app cerrada**:
   - Android revisa cada ~15 min (el mínimo del sistema sin servicios de Google). Para que sea al instante hace falta push: Firebase Cloud Messaging (necesita proyecto de Firebase y su biblioteca; hoy la APK se compila sin Gradle) o UnifiedPush/ntfy.
   - iPhone: sin cuenta de desarrollador de pago no hay APNs. Hoy se usa la actualización en segundo plano de iOS (`SegundoPlano.swift`), que el sistema ejecuta cuando quiere, y la conexión en vivo mientras suena música.
2. **Foto a la izquierda en los avisos del iPhone**: depende de que el instalador (SideStore, AltStore, Sideloadly) conserve el permiso de notificaciones de comunicación. Si no lo hace, la foto sale como imagen a la derecha (respaldo automático).
3. **OAuth**: faltan las credenciales reales.
   - Variables: `GOOGLE_CLIENT_ID/SECRET`, `auth.facebook.app-id/app-secret`, `auth.apple.client-id/team-id/key-id/key-file`, `APP_BASE_URL`.
   - Direcciones de vuelta: `https://<servidor>/api/auth/oauth/{google|facebook|apple}/vuelta`.
   - Apple exige cuenta de desarrollador de pago.
4. **Descargas**: lo más efectivo contra los bloqueos de YouTube es poner cookies de una cuenta en `~/.config/spacemusic/youtube-cookies.txt` (formato Netscape). Las réplicas públicas (Invidious/Piped) están caídas o piden verificación; no se usan. JioSaavn no tiene el catálogo latino/internacional fuera de India.
5. **Gráfica de géneros de la web**: sigue mostrando etiquetas genéricas como «Music»; el iPhone ya las oculta (`GraficaGeneros.genericos`). Conviene hacerlo en el servidor (`PerfilService`) para todas las apps.
6. **Disco del PC de desarrollo casi lleno** (C:); afecta a una prueba.
7. **`pruebas/navegador.mjs` da 6–7 de 11 desde la 3.14**: fallan «Iniciar radio…» (`responder is not a function`), «Volumen parejo con la pantalla apagada», «Con la pantalla apagada pasa a la siguiente…» y «Si la copia guardada es de otra canción…». Ya fallaban con la web de la 3.14 sin los cambios de la TV (comprobado el 21 de noviembre); probablemente la prueba no se actualizó al nuevo comportamiento de «no saltar por error». `syncplay-sincronia.mjs` da 7/8 (red lenta: desfase de 1–3 s) también antes de la 3.15. `control-remoto.mjs` sigue 14/14.
8. **TV**: falta que la TV pueda ser «controlada» desde su barra como en la web (aquí se controla desde la pantalla Dispositivos) y aviso de versión nueva de la APK de TV (hoy la APK casi no cambia porque la interfaz es web).

## 5. Convenciones

- **Idioma**: todo en español: textos de la app, comentarios, nombres de variables, mensajes de commit y respuestas al dueño.
- **Commits**: terminan con la línea `Co-Authored-By` que use la herramienta. Nunca subir `src/main/resources/static/audio-diagnostico.html` ni `js/audio-diagnostico.js` (son locales).
- **Saltos de línea**: hay archivos con CRLF y otros con LF; al editar con scripts, conservar el que tenga cada archivo.
- **Swift Package de pruebas**: compila solo los archivos de `Modelos/` listados en `Package.swift`. No usar tipos de la app (como `Sesion`) dentro de `Modelos.swift`.
- **Seguridad**:
  - los adjuntos de mensajes se guardan cifrados;
  - `JwtFilter` acepta `?t=` solo en rutas permitidas (adjuntos, audio);
  - las fotos de los avisos solo se bajan del mismo servidor.
- **Publicar** (servidor, APK o IPA en el servidor) solo con el visto bueno explícito del dueño.

## Continuación de audio: 21 de noviembre de 2026 (Codex)

- iPhone 1.42 publicado en el servidor y verificado: commit app-ios 113ff10, CI 44941962398 correcto. Conserva la canción ante fallos, inicia con un segundo continuo y vuelve a cinco; SyncPlay mantiene 30 segundos y decide sus cambios.
- Web 3.14 DESPLEGADA y verificada el 21 de noviembre: API pública 200, audio sin sesión 401, módulos nuevos publicados. SHA256 del jar confirmado en el servidor. Respaldo: ~/spacemusic/spacemusic.jar.antes-audio-3.14-20261121-codex.
- Web: no salta por error ni por falta de copia offline; reintento en la misma posición; metadatos atrasados no mueven otra selección. Las descargas manuales ceden la conexión y retoman íntegras.
- Validación: seis pruebas del motor controlado y diez del service worker aprobadas; pruebas de modelos y compilación iOS en CI aprobadas. La suite Java completa no se terminó por recursos del PC; AudioPreparacionTest agotó su espera. Su código se dejó intacto.
- Jar SHA256: 5b02d8629ffd14c96569d52822a2d42f46ecb75166d9213113ebb1eb1efc7b4f. Los diagnósticos locales están excluidos del jar en pom.xml.
- Despliegue cerrado: /app/SpaceMusic-42.ipa conserva HTTP 200 y 4 112 592 bytes; la revisión de audio publicada es v=314 y el salto automático antiguo fue retirado. No se probó teléfono físico.
- Evidencias: pruebas/resultados/audio-3.14.json.

## Actualización 3.18 — sincronía compartida (21/11/2026)
- Servidor/web 3.18 publicado y comprobado: novedades y estado HTTP 200; PUT /api/letras/1/sincronia sin sesión HTTP 401. Respaldo: /home/admin123/spacemusic/spacemusic.jar.antes-3.18-sincronia-20261121-1630. Sin cambios de esquema ni claves.
- GET /api/letras/{id} devuelve sincroniaGlobalSegundos y revisionSincronia; PUT /api/letras/{id}/sincronia recibe segundos y revisión. Solo ADMIN/COLABORADOR, ±60 s, revisión SHA-256 y bloqueo de fila; conflicto 409. Se guarda un offset LRC, se conserva texto y tiempos originales; historial manual existente y aviso SSE letra después de confirmar la transacción. Web, Android WebView, TV y lector nativo reconocen el offset.
- iPhone 1.44, commit 324a76a, Actions 44956404792 correcto: pruebas Swift, compilación Xcode y IPA. Publicado /app/SpaceMusic-44.ipa (4165825 bytes), SHA-256 33e7153decbd82eb3eb60eb60f1c9ab15ee28b8472cddc17985c71a28f26a757. Se instala con la firma/herramienta habitual; no se creó otra llave. Archivos anteriores .anterior en app/.
- Panel de sincronía persistente: vista previa, pasos 0,1/0,5 s, pausa, retroceso y guardar para todos sin cerrar; usuario normal mantiene ajuste personal. Las correcciones de otro editor requieren recargar.
- Verificación local: 10 pruebas Java (validación, roles, persistencia y dos editores concurrentes) y 19 JavaScript (incluidas caché/reproducción anteriores) correctas. Comando JS: node --experimental-vm-modules --test pruebas/letras-sincronia.test.mjs pruebas/audio-reproductor.test.mjs pruebas/audio-cache.test.mjs. No se probó un iPhone físico.
- No agregar al commit ni al jar los diagnósticos locales audio-diagnostico.html y js/audio-diagnostico.js. Se comprobó su ausencia del jar publicado.

## Actualización 3.19 — eficiencia de API (21/11/2026)
- Publicada en servidor/web/Android WebView. IPA nativo iPhone 1.45, Actions 44958437353 correcto, commit app-ios ab0052e. Manifiesto y /app/SpaceMusic-45.ipa verificados públicamente (4177758 bytes). Las firmas existentes no cambiaron; Android no requiere APK nueva.
- Diez mejoras implementadas: rutas de audio en caché; resúmenes de playlists por lotes; coescucha/similitudes fuera de peticiones; refresco automático de biblioteca sin bloquear lectores; ETag anticipado del catálogo común; paginación y búsqueda filtrada; estadísticas SQL agregadas; cálculo único de caché por clave; prioridad de audio en descargador; límites de proveedores y métricas separadas de SQL/pool/cliente.
- Consultar pruebas/resultados/eficiencia-3.19.json: checklist, resultados antes/después y límites de medición. 48 pruebas Java, 19 JavaScript y 11 de navegador simulado correctas. No hay prueba en iPhone/Android físico. Comparación local: 12 muestras por operación, H2 de desarrollo; la carga del PC no estaba aislada. No prometer porcentajes de velocidad en producción. Test de preparación de audio falló una vez bajo carga y pasó aislado sin modificarlo.
- Endpoints nuevos: GET /api/playlists/guardadas, /api/playlists/publicas/pagina, /api/albumes/guardados, /api/albumes/pagina, /api/artistas/pagina; POST /api/usuario/rendimiento (duraciones y nombres de métricas limitados, sin contenido; 20/min/cuenta); GET /api/admin/soporte/monitor/rendimiento (privado). Se mantienen rutas anteriores. Soporte → Monitor muestra p50/p95 y espera de conexión. iPhone busca en todo el catálogo antes de paginar 48 elementos.
- Descargador privado de la API: /home/admin123/spacemusic/descargar-api, SHA-256 23c83e5bc7002a024891a2c1c8f999058d03af6a9db7e3d1f0dc98884f80450a. Se elige en DescargasService si la configuración usa el valor por defecto y el archivo es ejecutable. /usr/local/bin/descargar queda intacto. Procesos nuevos heredan nice/ionice, dos canciones y un fragmento concurrentes; esperan si el marcador audio-activo está vigente. No se interrumpen descargas ya iniciadas.
- Reversión: copiar /home/admin123/spacemusic/spacemusic.jar.antes-3.19-eficiencia-20261121 sobre spacemusic.jar y reiniciar solo spacemusic. El jar anterior no utiliza descargar-api. Publicador de IPA conserva .anterior. SHA-256 del jar nuevo: 4d253b29aa9830eb7f62d1e6d3321788ebd4be2499d77083c016f597280c8c3d.
- Sin migraciones, nuevas dependencias, cambios de claves, puertos o firmas. Se conserva el respaldo existente: no se cambió su implementación. No agregar los dos diagnósticos locales al git/jar.

## Descargador corregido (22/11/2026)
- Activo /home/admin123/spacemusic/descargar-api y módulo spotify-sin-api.py. Respaldo del ejecutable anterior: descargar-api.antes-20261122. No se reinició API ni se cambió /usr/local/bin/descargar (root). BAT del repositorio y C:/Users/Josue/Desktop/SpaceMusic APK/Descargar musica.bat apuntan al privado; respaldo local .antes-20261122.
- Nuevo progreso: ~/.config/spacemusic/spotify-catalogo-progreso.json; parciales en spotify-parciales/ (fuera de música); resultados de listas en resultados/<id>.json. Reintentar desde el panel conserva completadas verificadas y no vuelve a bajar esos archivos. Los archivos antiguos sin historial verificable requieren revisión; no se sobrescriben.
- Nuevo módulo usa exclusivamente metadatos públicos para Spotify y búsquedas de ytmusicapi (no oficial), valida versiones e integridad; ante restricciones conserva pendientes. No obtiene audio de Spotify, no usa las credenciales Premium ni aplica evasión de acceso. No garantiza disponibilidad de terceros.
- ytmusicapi instalado 1.12.4 no admite requests_timeout: se usa requests_session con timeout de conexión/lectura. Dependencias existentes: yt-dlp 2026.10.26, mutagen 1.48.1; no se instalaron paquetes.
- Pruebas: python -m unittest discover -s scripts -p test_spotify_sin_api.py; bash scripts/test-descargar.sh <descargar>; python scripts/test-descargar-audio.py <spotify-sin-api.py> (Linux con herramientas existentes, audio generado, sin canciones externas). Nueve unitarias, tres verificaciones de cola y flujo con etiquetas reales correctos. Prueba pública: Hot Pink 12 metadatos y tres coincidencias únicas. No se encoló ni descargó el álbum de las notas adjuntas.
- Mejoras siguientes propuestas: panel de pendientes por canción con selección manual ante ambigüedad; reintentos programados con retroceso solo para fallos transitorios; revisión previa de duplicados y metadatos antiguos. No hacer migración automática de archivos reales.
