# SpaceMusic: resumen para quien continúe el trabajo (Codex u otra persona)

Estado al 21 de noviembre de 2026, versión **3.14** (servidor y web), **Android 2.7** (código 18) e **iPhone 1.42** (rama `app-ios`, la compilación de GitHub numera la versión).
El detalle de cada versión está en `CAMBIOS.md` (servidor, web y Android) y en `CAMBIOS.md` de la rama `app-ios` (iPhone).

## 1. Qué es y dónde está cada parte

| Parte | Dónde | Tecnología |
|---|---|---|
| Servidor + API | `src/main/java/com/bpm/music/**` | Spring Boot 3, Java 21, MySQL (H2 en pruebas), JWT |
| Web | `src/main/resources/static/` (`js/views/*.js`, `css/noir.css`) | JavaScript sin framework (módulos ES), se sirve desde el mismo jar |
| Android (teléfono) | `android-movil/` | Vista web (`MainActivity`) + servicios nativos; se compila **sin Gradle** con `android-movil/compilar.sh` |
| Android TV | `android-tv/` | Igual que la de teléfono |
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

**3.13** (esta)
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
