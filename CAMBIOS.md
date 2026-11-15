# Registro de cambios


## Versión 3.7
- **Controlar otro dispositivo de la cuenta** (como «Spotify Connect»), en la web, la app Android (misma web, sin APK nueva) y iPhone (rama `app-ios`).
  - Servidor (`ControlRemotoService`, `/api/control/*`): `POST presencia` (estado de cada dispositivo: canción, segundo, pausa, volumen; vence a los 75 s), `GET dispositivos`, `POST {id}/orden` (play, pause, toggle, next, prev, seek, volumen, reproducir, transferir) y `DELETE presencia/{id}`. Solo entre dispositivos de la misma cuenta (el usuario sale del token); ids y órdenes validados; máximo 12 dispositivos por cuenta. Las órdenes viajan por la conexión en vivo (`control-orden`) y la lista cambia en vivo (`control-dispositivos`).
  - Web (`js/remoto.js`): identificador aleatorio por dispositivo, nombre editable, informe al cambiar algo y cada 30 s, despedida al cerrar sesión o la pestaña; con música sonando la conexión en vivo sigue abierta aunque la pestaña esté oculta. Panel **Dispositivos → Tus otros dispositivos** y pantalla de control (tiempo, botones, volumen, «Escuchar aquí», «Pasar mi música»); punto en el botón Dispositivos.
  - Pruebas: `ControlRemotoTest` (aislamiento por cuenta y validación) y `pruebas/control-remoto.mjs` (dos navegadores con la misma cuenta: 9 de 9; la pausa llega en ~20 ms en local).

## Versión 3.6.2
- Rangos abiertos de streaming limitados a bloques de 256 KB; se conserva ETag, If-Range, posición y continuidad byte por byte. Las descargas completas siguen entregándose completas.
- Caché de audio: en línea, límite de 150 ms para buscar copias antes de iniciar streaming de red. Sin conexión no se aplica el límite. Las respuestas tardías se cancelan para liberar su flujo.
- La preparación solicitada durante una transferencia se aplaza y reintenta (antes se descartaba); cola limitada a 8 tareas.
- Los originales pendientes se revalidan en cada uso; las copias definitivas conservan su caché larga.
- Renueva la URL de streaming (`v=4`) para evitar reutilizar durante siete días representaciones anteriores a la preparación de audio. No invalida ni elimina descargas offline.
- Cancela la precarga incompleta de iOS incluso cuando corresponde a la pista elegida y detiene la precarga HTML de offline.js antes de iniciar otra canción.
- El reproductor ampliado muestra carga y etiqueta accesible mientras espera audio.
- El elemento multimedia expone tiempos de metadatos, evento playing y primer avance en atributos DOM, sin credenciales ni URLs de autenticación en los registros.


## Versión 3.6.1
- Inicio de audio: copias sin imágenes ni etiquetas incrustadas con ffmpeg `-c:a copy` y `+faststart` para M4A/MP4. Originales intactos; `/descargar` conserva el original; `/audio` usa la copia preparada o vuelve al original sin esperar.
- Se reutiliza AudioLigeroService: una sola cola, máximo 8 pendientes, menor prioridad, una conversión a la vez, un hilo para AAC, espera ante transferencias de audio o CPU >70 %. Preparación gradual de 4 canciones/minuto y bajo demanda; espacio mínimo de 200 MB.
- Service worker: rangos de las copias completas por ReadableStream; evita leer la canción entera con blob(). Compatibilidad con copias antiguas y fallos explícitos ante truncamiento. Precarga respeta bytes=0-1 e If-Range.
- Prioridad antes de asignar src; se detiene el precargador anterior. Iniciar radio reproduce inmediatamente y descarta respuestas de recomendaciones si cambió la canción.
- Evidencias: `pruebas/audio-cache.test.mjs`, `pruebas/audio-comparar.mjs`, `pruebas/resultados/audio-3.6.1.json` y `pruebas/INFORME-AUDIO-3.6.1.md`.

## Versión 3.6 (web y app Android 2.0, código 11)
Paridad con los cambios de iPhone (`app-ios` 65241aa y 597d344), reutilizando las API existentes.

**SyncPlay y Dispositivos**
- **SyncPlay** es el nuevo nombre visible de «Escuchar juntos» (`js/juntos.js`; `/api/juntos` sin cambios). Se quita del menú de la cuenta y se entra desde **Dispositivos** y **Mensajes**. Crear sala, **Compartir enlace** (hoja de Android, `navigator.share` o copiar) e **Invitar a un amigo**: invita con `POST /api/juntos {username}` y envía un mensaje real con `POST /api/mensajes/{usuario}` que lleva `https://servidor/#/juntos/ID`.
- **Enlaces de invitación** (`js/syncplay-enlace.js`): solo se reconocen enlaces del servidor de la cuenta (mismo esquema, host y puerto, sin usuario/contraseña, ruta `/`, fragmento exacto `#/juntos/N`). El servidor sigue validando la pertenencia a la sala. Prueba: `node --test pruebas/syncplay-enlace.test.mjs`.
- **Dispositivos** (`js/dispositivos.js`), botón antes del corazón en el mini reproductor y en la pantalla completa: salida actual y cambio según la plataforma. Android: puente `SpaceMusicApp.salidaAudio()`/`elegirSalida()` (`Salidas.java`): `MediaRouter2.showSystemOutputSwitcher()` en Android 14+, cuadro «Salida de medios» de SystemUI en 12–13, panel `MEDIA_OUTPUT` en 10–11 y, de respaldo, panel de volumen o ajustes de Bluetooth; avisa a la web cuando cambia la salida (`window.smSalida`). Web: `selectAudioOutput`/`setSinkId` (también en el ecualizador vía `AudioContext.setSinkId`) y transmitir (Remote Playback) donde existan; si no, explica que la salida la elige el sistema.

**Mensajes**
- Accesos visibles a SyncPlay, Coincidimos y Dedicatorias. En el chat: botón de auriculares (Iniciar SyncPlay · Mis salas) y botón «+» (enviar o dedicar la canción que suena a esa persona, iniciar Coincidimos con ella, mis dedicatorias). Las invitaciones se ven como tarjetas «Abrir sala y unirme»; la lista y los avisos muestran «Invitación a SyncPlay» en vez del enlace.

**Menú de canciones, letra y administración**
- Menú agrupado (`showMenu` admite grupos plegables): Me gusta y Agregar a playlist arriba; **Cola y radio**, **Compartir** (nuevo: Enviar a un amigo, Compartir enlace) y **Más acciones**. Se conservan todas las acciones.
- **Letra a pantalla completa**: portada desenfocada de fondo, cabecera compacta con canción, artista y botón para salir, letra grande y controles abajo (con Dispositivos). Las líneas ya no se escalan ni los interludios cambian de alto (sin rebotes en el avance automático); curva de desplazamiento simétrica y salto directo con `prefers-reduced-motion`.
- **Descargas**: registro en vivo plegado por defecto, en un panel de altura limitada con desplazamiento propio (no se pinta mientras está plegado); claves de Spotify antes del registro y accesos directos arriba.
- **Usuarios**: tocar cualquier parte de la fila abre la ficha en el acto (con los datos de la lista) y se completa al llegar el detalle; la lista no se recarga y la ventana queda encima de todo.

**Inicio de reproducción (smart buffering)**
- Prioridad para la canción actual: al cambiar de canción el service worker cancela las descargas automáticas en curso y las que esperan; las descargas a mano esperan hasta que la nueva arranca.
- Precarga según la red y la memoria (`navigator.connection`, `deviceMemory`): con datos móviles, red lenta o ≤ 2 GB solo se precarga el principio de la siguiente (640 KB por rangos, caché `sm-inicio`, que responde a la primera petición `bytes=0-`; el resto llega de la red); con WiFi, la canción entera. Todo espera a que la actual tenga margen en memoria.
- Sin descargas duplicadas: ya no se precarga además con un segundo `<audio>` cuando hay service worker; con datos móviles no se vuelve a bajar entera la canción que suena para guardarla; el audio que no está en el dispositivo va directo a la red sin pasar por el service worker (índice en memoria).
- Calidad automática: también la versión ligera con red lenta (`downlink` < 1,5 Mbps o `rtt` > 500 ms).
- Servidor (`CancionController`): ETag fuerte en `/api/canciones/{id}/audio` y cabecera `X-SM-Calidad`; si se pidió la ligera y aún no está, el original se cachea 10 min (no 7 días). Prueba: `AudioStreamingTest` (206, `Content-Range`, 304 con `If-None-Match`, 416, caché corta).
- Medición (`pruebas/arranque-audio.mjs`, Android simulado, canciones WAV de 90 s, sin ffmpeg local): con datos móviles simulados (1,6 Mbps, 120 ms) la siguiente canción pasó de p95 7,7 s a 2,1–3,2 s; tocar→sonar quedó igual (≈1,5 s, limitado por el tamaño del WAV: sin la versión ligera no hay menos que bajar). Con WiFi todo quedó igual (≈0,25–0,3 s al tocar, ≈0,1 s la siguiente). Resultados en `pruebas/resultados/arranque-*.json`.

**Pruebas**: `pruebas/syncplay-dos-cuentas.mjs` (dos navegadores con dos cuentas reales: invitación por mensaje, tarjeta, unirse, misma canción con desfase < 2 s, pausa sincronizada, Dispositivos, menú, letra, registro largo y ficha de usuario, a 390×844).

## Versión 3.5
**Optimización**
- **Service worker por huella de contenido** (`ServiceWorkerController`, `sw/sw.js`): la versión ya no cambia en cada reinicio del servidor; cada archivo lleva su huella y al instalar se copia de la versión anterior lo que no cambió (solo se descarga lo nuevo).
- **Pantallas bajo demanda** (`main.js`): perfil público, mensajes, novedades, experiencias, dedicatorias y escuchar juntos se cargan al visitarlas. Inicio visible en celular simulado: 1,7 s (antes 2,2–3,7 s).
- **Audio ligero** (`AudioLigeroService`): AAC 128 kbps con ffmpeg (nice 19, una a la vez, ~4 cada 3 min) en `music.light-path` (`./ligero`); `?calidad=ligera` entrega la ligera si está lista, si no la original; las de ≤160 kbps no se convierten. Ajustes «Calidad al escuchar» (automática según `navigator.connection`) y «Calidad de las descargas».
- **Instaladores desde el disco** (`AppsController`): `/app/*.apk|json` se copian del jar a `music.apps-path` (`./app`) al arrancar y se entregan desde ahí (se usa la más reciente); una APK nueva se publica copiándola, sin reiniciar. Evita «ZipFile closed».
- **MySQL** (`deploy/optimizar-mysql.sh`, con `sudo`): `innodb_buffer_pool_size=1G` y escucha solo en 127.0.0.1; `--revertir` para deshacer.

**Ideas nuevas**
- **Escuchar juntos** (`JuntosService`, `js/juntos.js`, `/api/juntos/**`): salas en memoria (máx. 8 personas, se cierran tras 30 min sin actividad), solo se invita a quien te sigue; el anfitrión controla y los invitados se sincronizan (eventos en vivo `juntos`, corrección cada 20 s si se desvían más de 2 s); cola compartida y reacciones.
- **Resumen**: `cambioPorcentaje` respecto al período anterior, `descubrimiento` (artista nunca escuchado antes con más minutos) y aviso el día 1 de cada mes (10:00 El Salvador).
- **Dedicatorias programadas**: `programadaPara`/`entregada`; el destinatario no la ve antes; entrega cada minuto; hasta 1 año y 30 pendientes; `DELETE /api/dedicatorias/{id}/programada`.
- **Despertador** (`js/alarma.js`) y app Android 1.9 (`Despertador.java`: `setAlarmClock`, enciende la pantalla, se reprograma tras reiniciar). Temporizador para dormir: baja el volumen los últimos 30 s.
- **Letra**: pantalla completa también en el celular y «Compartir versos como imagen» (hasta 8 versos).

## Versión 3.4.2 (urgente)
- **Android: «sin conexión» con internet.** Un solo fallo al abrir dejaba la app en la pantalla sin conexión (y tras reinstalar no hay copia guardada). Ahora:
  - Web (`offline.js`): un fallo de petición con el teléfono en línea se confirma preguntando a `/api/auth/estado` (2 intentos) antes de pasar a modo sin conexión; estando sin conexión se vuelve a probar cada 15 s.
  - App 1.8 (código 9): 3 reintentos con espera al abrir; `network_security_config` con las raíces de Let's Encrypt (ISRG Root X1, X2 y Root YE: el certificado del servidor ahora es YE2 → Root YE → X2 → X1); la pantalla sin conexión muestra el motivo y reintenta sola cada 15 s si hay internet (sin cortar la música). Nunca se aceptan certificados no válidos.

## Versión 3.4.1
- **Arreglo visual:** la portada en la ventana "Dedicar" no tenía tamaño y ocupaba toda la pantalla (web y APK, que carga las mismas páginas). Regla general para portadas en filas de ventanas.
- **Dedicatorias con letra** (`js/dedicatorias.js`): 3 pasos (para quién → qué parte → mensaje). Lista de seguidores con buscador (`GET /api/dedicatorias/personas`, mutuos primero); bloques de la letra (LRC o texto, sin marcas de tiempo por palabra) con inicio y fin, o "Toda la canción"; tarjeta 1080×1350 con portada, título, artista y letra, para compartir o descargar.
- **Servidor:** `Dedicatoria` con `completa`, `segundoFin` y `letra`; el fragmento se valida contra la letra real (líneas seguidas, máximo 16).
- **Detalle rediseñado:** fondo con la portada, rango del fragmento, letra destacada o "Te dedicaron esta canción completa", mensaje, escuchar, guardar y compartir tarjeta.

## Versión 3.4
- **Recuperado el trabajo de Codex** (desplegado el 10/11/2026 y sobrescrito por 3.3/3.3.1): administración unificada (oculta la música dentro del panel), portadas consultadas por huella (sin leer el LONGBLOB), bloqueo de escrituras en playlists (`findForUpdate`). Unido con historial en la rama `codex-20261110`.
- **A. Buscar en YouTube Music** (Administración → Descargar música): adaptador `ytmusicapi` (biblioteca no oficial) por `scripts/ytm-proveedor.py`, con 20 s de espera, una consulta a la vez, caché de 30 min, 1 reintento y pausa automática; estados por resultado; encolar por id sin duplicados (sincronizado); álbumes completos o por canción; la descarga sigue en la cola de siempre.
- **B. Recomendaciones con YouTube Music**: vínculo persistente (`cancion_externa`) primero por la etiqueta "comment" del archivo, si no por coincidencia exacta (título base, versión, artista, duración ±4 s); relacionadas (`cancion_similitud`) solo con canciones del servidor; la radio las suma sin llamadas externas.
- **C. Completar datos** (`cambio_metadato`): letras LRCLIB, portada y año MusicBrainz + Cover Art Archive; solo campos vacíos, nunca sobre correcciones manuales; lo dudoso queda pendiente; todo con fuente, método y reversión; miniaturas regeneradas.
- **D. Organizador** (`trabajo_organizador`): diario y semanal en hora de El Salvador, una ejecución a la vez, pausa/cancelar/reanudar tras reinicio, espera si hay reproducción o CPU alta; análisis de sonido con ffmpeg (`caracteristica_audio`); duplicados e inconsistencias por reglas; IA local Ollama con esquema JSON y validación, **apagada** porque `qwen3:0.6b` acertó 1 de 20 (5 %) a ~14 s por canción.
- **E. Experiencias**: rutas musicales, descubrir con control, coincidimos, dedicatorias y preparar sin internet (paquete `experiencias`).
- **F. Rendimiento**: cabecera `Server-Timing` (aplicación y base de datos) y métricas p50/p95 por ruta; expresiones regulares precompiladas y memorizadas en `normalizar`, `familia`, `principal` y `primerArtista` (búsqueda "amor" 221 → 53 ms, Mix del día 248 → 31 ms, radio 218 → 35 ms en p50).
- **G. Administración → Organización**: trabajos, proveedores, IA, cambios, vínculos, etiquetas, ajustes y rendimiento.
- **Pruebas**: 51 del servidor y 10 de navegador (`pruebas/navegador.mjs`), más `pruebas/rendimiento.mjs` con resultados en `pruebas/resultados/`.

## Versión 3.3.1
- **Suena otra canción al tocar una (sobre todo en Android):** en el navegador la lista era correcta; lo que cambia en el teléfono son las copias guardadas (`sm-audio` y descargas), que se reutilizaban para siempre sin comprobar. Ahora, si una canción con copia en el dispositivo dura muy distinto de lo esperado (más de 5 s o 5 %), se borra esa copia (`audio-equivocado` en el service worker), se vuelve a pedir al servidor en el mismo punto y, si era una descarga, se vuelve a bajar. Una vez por canción y por sesión.
- **Sin conexión:** tocar una canción no descargada ya no reproduce la primera descargada de la lista; avisa.
- Prueba nueva en `pruebas/navegador.mjs`: copia equivocada en el dispositivo → se corrige y suena la correcta.

## Versión 3.3
- **IA local:** el servidor tiene Ollama con `qwen3:0.6b`. Se probó para clasificar artistas por estilo: tardó ~1 minuto en 10 artistas y respondió mal (repitió la lista de estilos). Con 2 núcleos y sin tarjeta gráfica, un modelo más grande sería aún más lento. Por eso la organización se hace con reglas, con los géneros de MusicBrainz y lo que se escucha.
- **Estilos concretos** (`EstilosService`): cada canción recibe un estilo (bachata, reggaetón, trap latino, regional, corridos, pop latino, hip hop, trap, R&B, rock, electrónica, infantil, cristiana…): por su género si es específico, si no por los géneros del artista (MusicBrainz) y si no por sus otras canciones. "Latino" (1200 canciones) ya no es una sola categoría.
- **Explorar** (`/api/generos`) lista estilos. **Página de género** (`/api/generos/{nombre}`): secciones cortas (lo esencial con variedad, lo nuevo, clásicos, recién llegadas, aún no escuchadas) y artistas destacados; todas las canciones por páginas en `/api/generos/{nombre}/todas`, en orden variado. Se mantiene `canciones` para versiones anteriores.
- **Mixes:** Mix del día con los 3 estilos favoritos de cada persona (en proporción a lo que escucha) y por bloques; Descubrir con sus estilos y parecidos; mixes de género por estilo; mix de artista con su estilo real; radio puntúa por estilo y penaliza saltos sin relación. Todo con orden "fluido" (sin artistas seguidos ni saltos bruscos).

## Versión 3.2
- **Recomendadas en una playlist:** "Agregar" agregaba nada y reproducía la canción. La escucha global de filas (`main.js`, fase de captura) se comía el clic; ahora los botones y enlaces propios de una fila manejan su clic.
- **Pantalla apagada (iPhone y Android):** al terminar una canción no seguía la siguiente y el reproductor se cerraba. Causa: con la página en segundo plano iOS corta sus peticiones y la app lo tomaba como "sin conexión", así que saltaba todo lo no descargado. Ahora esos fallos no cuentan con la página oculta, si el sistema dice que hay red se intenta la siguiente igual, la radio se pide antes (quedan 2 canciones, al 20 %) y se reintenta hasta 3 veces.
- **Se quita "Pedir una canción"** (pantallas, rutas y API); los colaboradores suben la música. La tabla `solicitud_cancion` queda en la base de datos sin uso (no se borran datos).
- **Se quita la notificación a todos** cada vez que llegan canciones nuevas. Quien sube sigue recibiendo su resumen.
- **Soporte técnico en su propia página** (`#/soporte`): menú lateral por grupos (Estado, Revisar, Personas y seguridad, Herramientas), botones "Volver al inicio" y "Administración"; en celular, un selector de sección. Los enlaces viejos `#/admin?tab=soporte` llevan ahí.
- **Administración → Descargar música** (paquete `descargas`): las funciones de `Descargar musica.bat` en la web. Pegar enlaces sin límite con vista previa y validación, cola con lo que se baja ahora, progreso en vivo (cada 3 s), detener/reanudar, quitar listas que no empezaron, fallidas con reintentar/olvidar, registro en vivo con filtro, aviso de límite de Spotify y claves de Spotify (se guardan y nunca se devuelven). Usa el programa `descargar` del servidor; los enlaces van por la entrada estándar (sin línea de comandos) y se rechazan líneas que empiezan con "-".
- **Service worker:** guarda los archivos de la app uno por uno; si uno falla, la app igual abre sin internet.
- **Mantenimiento:** se reutiliza el último resultado de la prueba de internet (antes una copia guardada podía tapar el aviso).
- **Pruebas en navegador de celular** (`pruebas/navegador.mjs`): Android simulado, sin internet, servidor caído y pantalla apagada. 8 pruebas: abrir la app, agregar recomendada, transición, volumen parejo con pantalla apagada, siguiente canción con pantalla apagada, reportar un problema, abrir sin internet y sonar lo descargado, aviso de mantenimiento.
- 22 pruebas del servidor (5 nuevas del panel de descargas).

## Versión 3.1
- **Centro de soporte técnico** (Administración → Soporte, solo administradores, paquete `soporte`): resumen, monitor del servidor (muestra por minuto), diagnóstico, incidencias agrupadas por huella, revisión de la biblioteca, base de datos, usuarios y sesiones, alertas, seguridad y auditoría, mantenimiento e informes con CSV. Detalle completo en el informe de la entrega.
- **Copias de seguridad automáticas** de la base de datos: todos los días a las 4:30 y a pedido, en `./respaldos` (`.sql.gz`), se conservan 7. La restauración es manual (se explica en el panel).
- **Álbumes con el mismo nombre** (Utopia de Romeo Santos y de Travis Scott): se separan por artista principal (`AlbumDTO.clave`, `#/album/X?artista=Y`).
- **Transición entre canciones** (`mezcla` en `player.js`): segundo `<audio>` que baja mientras sube el siguiente, curva de igual potencia, 0–12 s. No se usa en iPhone.
- **Volumen parejo** (`nivelador`): mide el RMS con Web Audio, lleva cada canción a ≈ −18 dBFS (−10 a +8 dB) con un limitador y recuerda el ajuste por canción.
- **Sesión sin internet:** con el token vencido y sin conexión con el servidor se entra igual a las descargas; al volver la conexión el servidor renueva o pide iniciar sesión.
- **"SpaceMusic está en mantenimiento":** el servidor avisa por SSE antes de apagarse y, si no responde pero hay internet, la app lo distingue de "sin internet".
- **Gustos al crear la cuenta** (`usuario_gusto`): géneros y artistas que alimentan las recomendaciones; se pueden cambiar en Ajustes.
- **Pedir una canción** (`solicitud_cancion`): desde el buscador y el menú de la cuenta; Administración → Pedidos y reportes; cada 10 minutos se revisa si ya llegó a la biblioteca y se avisa.
- **Reportar un problema** (`reporte_cancion`) en el menú de cada canción; al resolverlo se avisa a quien reportó.
- **Playlists colaborativas** (`playlist_colaborador`): el dueño invita a amigos (seguidos o seguidores); los invitados agregan, quitan y ordenan canciones, no cambian nombre, portada ni visibilidad.
- **Tu año / tu mes en SpaceMusic** (`#/resumen`): minutos, artistas, canciones, géneros, racha, momento del día y tarjeta para compartir.
- **Pruebas automáticas** (`src/test`): 17 pruebas (soporte, permisos, copias, colaborativas, pedidos y resumen).

## Versión 3.0.2
- **Pantallas sin conexión:** las secciones que necesitan internet muestran "Sin conexión a internet" con un mensaje amable y los botones "Ir a mis descargas" y "Reintentar" (`setGuardiaSinRed` en el router):
  - historial, artista, género, mezclas, perfiles de otros, mensajes, novedades, estadísticas, administración y subir;
  - también una playlist o un álbum que no está descargado.
  Al volver la conexión, la sección se abre sola.
- **Ajustes sin conexión:** se abren con los datos guardados de la cuenta. Los de reproducción y color funcionan; los cambios de la cuenta avisan que necesitan internet.
- **Errores de red:** el mensaje es "Sin conexión a internet. Inténtalo cuando vuelvas a estar en línea."

## Versión 3.0.1
- **Descargas como Spotify:**
  - una sola copia dentro de la app: ya no se guarda el MP3 en Música ni salen notificaciones de Android;
  - círculo de progreso en cada canción mientras baja, y el ícono de descargada cuando el service worker confirma que llegó completa;
  - cada descarga vive en su colección (sin pestaña "Descargadas" ni descarga de canciones sueltas); una canción está una sola vez aunque esté en varias listas.
- **Descargas perdidas o a medias:** al abrir con conexión se revisan todas las canciones de cada colección descargada y se baja lo que falte (las versiones anteriores borraban de la lista las que fallaban).
- **Falso "sin conexión":** una respuesta lenta de la red (por ejemplo, mientras se descarga mucho) ya no cuenta como sin conexión. El service worker marca `X-SM-Copia: sin-red` o `lenta`.
- **Inicio sin conexión:** "Sin conexión a internet · puedes seguir navegando entre lo que descargaste".
- **Se quitan** la portada difuminada y el dato de escuchas del perfil.
- Probado en un Android real con el Wi-Fi apagado: Me gusta (10 canciones) se abrió y sonó desde el dispositivo.

## Versión 3.0
- **Modo sin conexión como Spotify** (`offline.js`):
  - se descargan colecciones completas (`sm_colecciones`: playlist, álbum o Me gusta), con portadas y letra, incluida la sincronizada;
  - sin conexión cada colección se abre desde su copia, solo con sus canciones;
  - el inicio, la biblioteca y el buscador muestran solo lo descargado; lo demás se ve apagado y el reproductor lo salta;
  - Me gusta descargado baja solo lo que marques después; quitar una descarga conserva lo que esté en otra.
- **Detectar sin conexión:** el service worker marca con `X-SM-Copia` las respuestas que vienen de la copia guardada y avisa qué canciones tiene (`ids-audio`).
- **Portada difuminada** en el reproductor: sin marco, se funde con el fondo. Se puede apagar en Ajustes → Reproducción (`prefs.portadaDifuminada`). En PC, la portada se achica si no cabe con la letra en miniatura.
- **Amigos en vivo:** al cambiar de canción, el servidor avisa a los seguidores conectados (evento `actividad`) y se actualizan su perfil y "Lo que escuchan tus amigos".
- **Música aparte** (cristiana e infantil, `GeneroService.APARTE`): solo se recomienda a quien la escucha (3 canciones o el 10 % de lo que escucha y le gusta). Aplica al inicio, las mezclas, Descubrir y la radio; sigue en el buscador y en Géneros, al final.
- **"Ver mi perfil"** arriba en Perfil y en el menú de la foto.
- **Menú de canción:** ya no se cierra al desplazarse dentro de él ni cuando la barra del navegador cambia de tamaño.

## Versión 2.9 (app Android 1.7)
- **Pantalla negra sin internet (Android):** la hoja de estilos de Google Fonts en el HTML bloqueaba el código de la app mientras la petición quedaba colgada sin red. Ahora se carga desde `js/fuentes.js`, sin bloquear y solo con conexión (`index.html` y `tv.html`).
- **Causa principal, en el service worker:** el servidor responde con `Vary: Origin` y los módulos JS se piden con Origin, así que `caches.match` no encontraba el código guardado y, sin internet, la app quedaba en negro. Ahora busca con `ignoreVary: true`. Probado en un Android real con el Wi-Fi apagado: abre el inicio completo y reproduce las canciones descargadas.
- **Red de seguridad en la app:** si sin internet la web no avisa que arrancó (`SpaceMusicApp.lista()`) en 12 s, se muestran las canciones guardadas en el teléfono.
- **Subir canciones sin repetidas:**
  - antes de subir, `POST /api/canciones/subir/revisar` (nombre y tamaño exacto) evita subir otra vez el mismo archivo;
  - al recibir, el servidor descarta la canción si ya existe (título, artista y duración, o el mismo contenido con otro nombre).
- **Portadas en teléfonos angostos:** la cuadrícula ya no cae a una sola columna gigante (2 columnas en pantallas de menos de 520 px).

## Versión 2.8 (app Android 1.6)
- **App Android sin internet:**
  - sin conexión abre la copia guardada de SpaceMusic (caché `LOAD_CACHE_ELSE_NETWORK`), con el modo sin conexión y las descargas de la app;
  - si tampoco hay copia, muestra una pantalla de respaldo con las canciones de Música/SpaceMusic (`SinConexion.java`, `res/raw/sin_conexion.html`). El audio se lee del teléfono con soporte para adelantar y se controla desde la notificación;
  - al volver internet se reconecta sola;
  - nuevo permiso READ_MEDIA_AUDIO.
- **Subidas en segundo plano** (`js/subidas.js`):
  - siguen aunque cambies de pantalla, con el indicador "Subiendo N de M";
  - al terminar llega una notificación a la campana y al teléfono con cuántas se subieron, el tamaño total y los géneros (`POST /api/notificaciones/subida`);
  - si el artista ya se conoce, el género se pone al subir; si no, se busca enseguida.
- **Notificaciones al instante:** el panel muestra la última lista guardada y se actualiza en segundo plano; la lista se trae antes, cuando llega algo nuevo.

## Versión 2.7 (app Android 1.5)
- **Buscar por género o ánimo:** "banda", "bachata", "reggaetón", "música relajante", "para el gym"… muestran esa música lista para escuchar (`GeneroService.buscarPorEstilo`, campo `estilo` en `/api/buscar`).
- **Letra en miniatura** en el reproductor del celular, debajo del artista (línea actual y siguiente). Al tocarla se abre la letra completa.
- **Mensajes con datos móviles:** la pantalla ya no espera a que lleguen la lista y el chat (antes, con señal lenta, parecía no abrir nunca). Las peticiones se cortan a los 8–20 s y se reintentan; se muestra la última copia guardada de la conversación, y si falla aparece "Reintentar".
- **iPhone con la pantalla apagada:** la siguiente canción se descarga antes a la memoria (hasta 30 MB) y el cambio es instantáneo. La radio se pide con tiempo. En iOS ya no se usa el segundo `<audio>` para precargar.
- **Diseño:**
  - la barra de estado del teléfono vuelve a negro al cerrar el reproductor;
  - las portadas de las filas ya no se ven cortadas;
  - la canción que suena se marca con un punto en lugar del contorno blanco;
  - el panel de notificaciones ocupa el ancho del celular;
  - Subir canciones tiene un diseño nuevo y sube varias a la vez (dos en paralelo, con resumen);
  - en el buscador ya no salen títulos vacíos.
- **Administración:**
  - **Soporte:** estado del servidor (memoria, disco, versión, tiempo encendido), quién está conectado, errores y advertencias recientes (`RegistroErrores`), aviso para todos y recargar la biblioteca;
  - ficha de cada usuario (actividad, últimas canciones), correo de contraseña y "Cerrar sus sesiones";
  - **Géneros:** cuántas canciones y artistas hay por género y familia, artistas sin género, buscar ahora y poner el género a mano.
- **Seguridad:**
  - el rol y el estado de la cuenta se leen de la base de datos en cada petición (con caché de 20 s), no del token: desactivar una cuenta o quitarle un rol surte efecto al instante;
  - cambiar o recuperar la contraseña cierra la sesión en los demás dispositivos (`sesionesDesde`);
  - máximo 6 conexiones en vivo por cuenta;
  - los avisos solo aceptan enlaces internos.
- **App Android 1.5:**
  - sin depuración remota ni copias de seguridad (protege la sesión guardada);
  - solo conexiones seguras (HTTPS);
  - la web no puede leer archivos del teléfono;
  - descargas y actualizaciones solo desde el servidor de SpaceMusic;
  - las notificaciones solo abren pantallas de la app.

## Versión 2.6
- **Notificaciones (campana)**, guardadas hasta que el usuario las borre (una por una o "Vaciar"):
  - ACTUALIZACION: cada versión nueva, se envía al arrancar el servidor y abre `#/novedades`;
  - CANCIONES: canciones nuevas en el servidor, una por escaneo;
  - PLAYLIST: alguien que sigues publicó una playlist con canciones;
  - SEGUIDOR, AMIGO y COLABORADOR (solicitud aprobada).
  - Nuevo `NotificacionesService` y `DELETE /api/notificaciones[/{id}]`.
- **Novedades:** pantalla con el historial de versiones (`#/novedades`, `app/novedades.json` con "anteriores"). La ventana emergente de novedades se reemplazó por la notificación.
- **Portada del perfil:** franja de altura fija con la foto a lo ancho. El botón "Ajustar" permite arrastrar la foto para elegir qué parte se ve (`PUT /api/perfiles/yo/banner/posicion`).

## Versión 2.5.2
- **Mensajes cargan rápido en PC:** el dominio público usa HTTP/1.1 (máximo 6 conexiones a la vez), y la música, las descargas en segundo plano y la conexión en vivo de cada pestaña las ocupaban. Ahora:
  - las descargas en segundo plano van de una en una;
  - la conexión en vivo se cierra en pestañas ocultas (en la app Android se mantiene);
  - la lista y el chat se cargan a la vez, y la última lista se muestra al instante.
- **Chat en el celular:** pantalla completa sin barra superior, reproductor ni menú; se ajusta al teclado (visualViewport).
- **Buscador en el celular:** se ocultan los íconos de mensajes y notificaciones para dar espacio a la barra.
- **Portada:** se ve completa en PC y celular, sin recortes. La altura sigue la forma de la foto, con un límite, y los lados se rellenan con la misma foto difuminada.

## Versión 2.5.1
- **Mensajes en tiempo real:** conexión en vivo (`/api/eventos`, Server-Sent Events) que entrega al instante los mensajes nuevos, el "Visto" y las notificaciones. Se reabre sola al volver a la app. Si se corta, la app revisa cada 15 s.
- **Mensajes en una sola sección:** sin ventanas flotantes. En el teléfono el chat ocupa toda la pantalla, con la barra para escribir fija abajo. La lista de conversaciones se actualiza sola.
- **Archivos viejos en el iPhone:**
  - la interfaz se sirve con `Cache-Control: no-cache` (el navegador siempre comprueba si cambió);
  - las actualizaciones se aplican solas cuando no está sonando música.
  - Esto corrige los íconos que salían como notas musicales.

## Versión 2.5 (app Android 1.4)
- **Mensajes entre amigos** (solo si se siguen los dos):
  - lista de conversaciones y chat con "Visto";
  - compartir la canción que suena;
  - nuevas tablas `mensaje` y API `/api/mensajes`.
- **Notificaciones:**
  - campana con contador: nuevo seguidor, ahora son amigos (tabla `notificacion`);
  - mensajes nuevos con aviso dentro de la app;
  - notificación del sistema en Android (app 1.4) y en el navegador si se da permiso;
  - se consulta cada 20 s (`/api/notificaciones/resumen`).
- **Foto de portada del perfil:** subir y quitar desde el propio perfil (`/api/perfiles/yo/banner`).
- **Canción suelta:** desde el buscador, el inicio, el historial o un perfil suena esa canción y después la radio sigue con otras del mismo estilo. Los álbumes y playlists respetan su orden.
- **"Lo que escuchan tus amigos":** nuevo diseño en tarjetas horizontales. Antes la foto del amigo se estiraba sobre toda la tarjeta.

## Versión 2.4 (app Android 1.3)
- **Géneros automáticos:** cada artista se consulta una vez en MusicBrainz (y en Deezer si no aparece) y su género pasa a las canciones que no lo tienen (tabla `artista_genero`, se revisa cada 30 min). Con colaboraciones ("Bad Bunny, ABRA") se usa el artista principal. Resultado: 533 de 543 canciones con género.
- **Radio más inteligente:** al terminar la lista sigue con canciones parecidas. Usa:
  - mismo artista y artistas con géneros parecidos;
  - "quienes escuchan a X también escuchan a Y", entre todos los usuarios;
  - género o familia de género y época;
  - tus gustos;
  - las últimas canciones que sonaron, no solo la actual (`/api/radio/{id}?anteriores=`).
- **Precarga:** a los 8 s de cada canción se baja la siguiente y su portada; con ahorro de datos solo el inicio.
- **Perfiles públicos** (`#/u/usuario`):
  - seguir, seguidores, siguiendo y amigos (cuando se siguen los dos);
  - playlists públicas (las privadas nunca aparecen);
  - lo más escuchado, géneros favoritos, vibras (Perreo, Noctámbulo, Explorador…);
  - "escuchando ahora" y compatibilidad musical en %.
- **Privacidad:** en *Perfil y ajustes → Tu perfil público* cada dato se puede mostrar u ocultar, y se puede escribir una frase sobre ti.
- **Buscador:** nueva pestaña *Personas*.
- **Inicio:** sección *Lo que escuchan tus amigos*.
- **Playlists:** el nombre del autor de una playlist lleva a su perfil.
- Nuevas tablas: `seguimiento` y `artista_genero`. Nuevas columnas en `usuario`: biografía y privacidad.

## Versión 2.3 (app Android 1.2)
- **Cada cuenta por separado:**
  - lo que se escuchaba, la cola, las búsquedas recientes y la lista de descargas son de cada cuenta;
  - al cerrar sesión se para la música;
  - otra cuenta en el mismo equipo ya no ve lo de la anterior.
- **Biblioteca:**
  - pestañas *Playlists*, *Álbumes* y *Descargadas*; se quitan *Artistas* y *Comunidad*;
  - *Álbumes* muestra solo los guardados con el botón **Guardar en biblioteca** de cada álbum;
  - las playlists públicas de otros se guardan con el mismo botón y aparecen en *Playlists* marcadas como "Pública · de …";
  - nueva tabla `usuario_guardado` y API `/api/usuario/guardados`.
- **iPhone:**
  - sin ecualizador ni visualizador (iOS cortaba la música al bloquear la pantalla o salir de Safari);
  - la sesión de audio se marca como reproducción de música;
  - la barra de volumen se oculta, porque Safari no deja cambiarlo desde la web: se usan los botones del teléfono.
- **Volumen:** comprobado en computadora y Android. La barra usa una curva suave a propósito.
- **Aviso "Novedades":** se muestra una vez a cada usuario después de la actualización (`/app/novedades.json`).
- **App Android 1.2:** la app avisa e instala la actualización.
- **Biblioteca sin repetidas:** se omitieron 15 canciones repetidas que se habían subido desde spotdown.

## Versión 2.2
- **Descargador más estable** (`scripts/descargar`, instalado en el servidor como `descargar`, y `Descargar musica.bat`):
  - no repite canciones: lleva un historial de lo ya descargado, salta lo que ya existe y la biblioteca ignora una canción repetida (mismo título, artista principal y duración);
  - reintenta solo lo que falló, hasta 3 rondas con espera entre ellas;
  - acepta varios enlaces a la vez y enlaces de YouTube, YouTube Music, SoundCloud y otros (además de Spotify);
  - opción C del .bat: credenciales propias de Spotify, para que Spotify no bloquee las descargas (error 429).
- **Recuperar contraseña por correo:** botón visible en el inicio de sesión (web y app Android).
- **Descargas:**
  - "Descargar" en el menú de una canción y "Descargar todo" en álbumes y playlists;
  - las canciones quedan para escuchar sin conexión (no se borran solas);
  - en Android también se guardan como MP3 en Música/SpaceMusic;
  - pestaña *Biblioteca → Descargadas*.
- **App Android 1.1:** avisa cuando hay una versión nueva y la instala con un toque (`/app/version.json`).

## Versión 2.1
- **Cerrar app** (Android y TV): para la música, quita la notificación y cierra la app por completo, sin dejarla en segundo plano.
  - En el celular está en el menú del usuario y en *Perfil → Sesión*.
  - En la TV está en el menú lateral (icono de apagado).
- **Volver al reproductor en la TV** después de minimizarlo, de tres maneras:
  - con → al final de una fila, o con ↓ al final de la pantalla, se llega al mini reproductor (muestra "OK · abrir");
  - con el elemento "Reproduciendo" del menú lateral;
  - con la tecla Menú del control.
- **Sonido de inicio en la web:**
  - suena al pulsar "Entrar" en el inicio de sesión;
  - si la app se abre con la sesión ya iniciada y el navegador bloquea el sonido, suena con el primer toque o tecla.
- **TV:** portada y letra centradas con los controles ocultos, y se ocultan más créditos en las letras.
- Las APK también se copian a la carpeta del Escritorio *SpaceMusic APK*.

## Versión 2.0 — Identidad "Noir", aprobación de cuentas, modo sin conexión y app Android

### Pantalla de inicio
- El logo oficial (cuadro negro con la nota blanca) se dibuja al abrir la web, la app Android y la TV, en unos 1,1 segundos.
- **Sonido de inicio:** `src/main/resources/static/sonidos/inicio.mp3` (≈3 s). Para cambiarlo, reemplaza ese archivo y vuelve a publicar.
  - En la app Android y en la TV siempre suena.
  - En el navegador puede quedar en silencio, porque los navegadores bloquean el sonido antes del primer toque.
  - Se desactiva en *Perfil → Sonido de inicio*.
- Si la app ya se abrió en la misma sesión (por ejemplo al recargar), la animación se salta y la carga es instantánea.

### Nueva identidad visual ("Noir")
- **Paleta:** se toma del logo y reemplaza el verde.
  - Negro profundo (`#000`) como fondo.
  - Blanco para las acciones principales.
  - Un brillo iridiscente, *aurora* (`#b4a5ff → #7cc8ff → #ffa3c8`), usado con moderación: indicador de sección, "Me gusta" y el podio de tendencias.
- **Tipografía:** *Manrope* en títulos y *Inter* en textos.
- **Acentos:** Perla (por defecto), Iris, Cielo, Rosa, Coral y Ámbar. Quien tenía el acento "Verde" pasa a Perla.
- **Inicio rediseñado:**
  - Fecha y saludo.
  - Tarjeta destacada con la mezcla del día.
  - Accesos directos.
  - "Continúa escuchando".
  - Tendencias en una lista numerada de dos columnas.
  - Secciones con subtítulo.
- **Biblioteca rediseñada:**
  - Encabezado con resumen.
  - Selector de secciones.
  - Búsqueda.
  - Orden.
  - Vista en cuadrícula o en lista.
  - Tarjetas grandes para "Me gusta" y el historial.
- **Estructura:**
  - Barra lateral sin paneles grises.
  - Reproductor como barra flotante.
  - Tarjetas sin fondo gris, con portadas protagonistas.
  - Géneros con colores propios.
  - Portadas generadas en grafito.
  - Correos con la nueva imagen.
- **Lo que no cambia:** las letras sincronizadas y la pantalla "Reproduciendo ahora".
- Equipos modestos (poca memoria o pocos núcleos) usan un modo ligero sin desenfoques costosos.

### Registro y aprobación de cuentas
- Quien se registra queda guardado al instante, **sin correo de verificación**.
- Todos los administradores activos reciben un correo con el botón **Revisar y aprobar**. El botón abre `/aprobar.html`, donde se aprueba o rechaza con un clic. El enlace sirve una sola vez.
- También se puede aprobar desde *Administración → Cuentas nuevas*, que muestra un contador de pendientes.
- Mientras la cuenta no está aprobada, el inicio de sesión muestra "esperando aprobación".
- Al aprobar o rechazar, el usuario recibe un correo de aviso. Los usuarios siguen pudiendo recibir otros correos, como la recuperación de contraseña.
- Las cuentas que ya existían se consideran aprobadas: nadie pierde el acceso.
- La solicitud para ser colaborador se mantiene en la pestaña *Colaboradores*.

### Base de datos y API
- **Índices nuevos**, creados solos al arrancar:
  - ruta del archivo
  - fecha agregada
  - playlists por usuario y por visibilidad
  - reproducciones por usuario y fecha
  - estado y enlace de aprobación de las cuentas
- **Portadas:**
  - La miniatura se sirve directo del disco, sin leer la imagen original de la base de datos.
  - Se marcan como inmutables por un año, porque la URL lleva la huella de la imagen.
  - Los mosaicos piden miniaturas más pequeñas.
- **ETag en las respuestas JSON** de lectura (inicio, búsqueda, álbumes, artistas, letras, playlists…): si nada cambió, el navegador recibe un `304` vacío.

### Modo sin conexión (web y Android)
- El service worker está ahora en `src/main/resources/sw/sw.js`.
- **Interfaz:** se guarda completa y abre al instante.
- **Datos de la API:** primero la red; sin conexión, o si tarda más de 4 s, se usa la última copia.
- **Portadas:** se guardan en el dispositivo (hasta 1500).
- **Canciones:**
  - Cada canción escuchada al menos 25 s se guarda sola en el dispositivo, igual que la siguiente de la cola.
  - Así el cambio de canción es inmediato y se pueden escuchar sin red, incluso adelantando.
  - Hasta 150 canciones; se borran las más antiguas.
  - No se guarda nada si el dispositivo tiene activado el ahorro de datos.
- Al cerrar sesión se borran los datos y canciones guardados de esa cuenta.
- Avisos de "Sin conexión" y "Conexión recuperada".
- *Perfil* muestra cuántas canciones hay guardadas.

### App de TV
- **Desplazamiento rehecho:**
  - Animación propia y estable.
  - Respeta los límites de la pantalla.
  - En listas largas (Me gusta, álbumes) sigue a la canción seleccionada en lugar de saltar al principio de la lista.
  - Arriba/abajo en canciones e izquierda/derecha en filas van directo al vecino.
  - Las listas largas solo dibujan las filas visibles.
- **Adelantar y retroceder:**
  - La barra de progreso se selecciona con el control: ◀ ▶ saltan 10 s, y más si se pulsa seguido.
  - Funciona aunque el navegador aún no conozca la duración del audio.
  - Las teclas ⏪ ⏩ del control también funcionan.
- **Reproductor:**
  - La portada queda fija y centrada (ya no se encoge en pausa).
  - Controles en dos filas: anterior/reproducir/siguiente, y debajo aleatorio/repetir/me gusta.
- **Nuevo diseño Noir:** tarjeta destacada en Inicio, aro de foco en la portada y textos más legibles.

### App Android para teléfonos (`android-movil/`)
- Muestra la versión móvil de la web, con el mismo diseño.
- **Reproducción en segundo plano** con la pantalla bloqueada, mediante un servicio en primer plano.
- **Controles del sistema:** notificación, pantalla de bloqueo, auriculares y Bluetooth (anterior, reproducir/pausar, siguiente y barra de posición).
- Abre sin conexión con lo guardado.
- Permite subir archivos (canciones, portadas, foto de perfil).
- El botón Atrás cierra pantallas, y en el inicio deja la música sonando.
- Para compilar: `bash android-movil/compilar.sh` (usa la misma llave de firma que la TV). La APK queda en `android-movil/SpaceMusic.apk` y se publica en `/app/SpaceMusic.apk`.
- **Descarga:** *Perfil → Apps*.
- **iPhone:** Safari → Compartir → *Agregar a inicio*. Usa el mismo diseño y la caché sin conexión.

### Archivos nuevos
- `css/noir.css` — identidad visual
- `css/splash.css`, `js/splash.js` — pantalla de inicio
- `js/offline.js`, `sw/sw.js` — modo sin conexión
- `js/nativo.js` — puente con la app Android
- `aprobar.html` — aprobación de cuentas
- `config/RendimientoConfig.java` — ETag
- `android-movil/` — app para teléfonos

### Propuestas para más adelante
- Pasar las portadas a WebP o AVIF (menos peso).
- Buscador con índice de texto completo en la base de datos cuando la biblioteca supere unas 20 000 canciones.
- Guardar manualmente una playlist completa para escucharla sin conexión.
- Modo "Coche" con botones grandes en la app Android.
