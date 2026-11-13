# Registro de cambios

## iPhone: API 3.5 y actualizaciones descargables
- Salas para escuchar juntos (eventos en vivo, cola compartida, invitaciones y reacciones), dedicatorias programadas y cancelación, calidad automática/alta/ligera, comparación y descubrimiento en el resumen.
- Despertador con subida gradual (con la app cerrada se toca la notificación para iniciar música); temporizador con bajada durante los últimos 30 segundos; letra ampliada y compartir versos como imagen.
- Actualización descargada dentro de la app, progreso, cancelación, errores visibles y guardado en Archivos; validación de firma ZIP y SHA-256, manifiesto del servidor con respaldo en GitHub.
- Página /app/ios.html y publicación del IPA sin reemplazar la API de producción. Pruebas del contrato 3.5 y de la publicación.


## App de iPhone 1.1 (SwiftUI, de nuevo la app activa)
- Funciones de la web 3.x: Rutas musicales, Descubrir, Coincidimos (sesiones en pareja), Dedicatorias, Preparar para escuchar sin conexión, «Tu resumen», elegir gustos, reportar un problema y playlists colaborativas; géneros por secciones y «Todas» de 50 en 50.
- Administración completa como la web: Soporte técnico (resumen, monitor con gráficas, diagnóstico, incidencias, revisión de biblioteca, base de datos, sesiones, alertas y umbrales, auditoría, mantenimiento con copias de seguridad y cachés, informes y CSV), Descargar música (cola, YouTube Music, Spotify), Reportes de la comunidad con contador y Organización (organizador, IA, cambios, vínculos, etiquetas, proveedores, ajustes, rendimiento).
- Letra sincronizada: las líneas ya no quedan pegadas al borde izquierdo al avanzar la canción.
- Reproductor a pantalla completa con los botones Letra · AirPlay · Cola siempre visibles, sin barra de volumen y con la línea de la letra bajo el título; letra palabra a palabra y ajuste de sincronía por canción.
- Si una canción no carga en 12 s se reintenta una vez y luego se explica el motivo (permiso, archivo, conexión, formato) en vez de girar en 0:00.
- Notificaciones del iPhone para mensajes y avisos nuevos (también de actualización), y revisión en segundo plano con la app cerrada.
- Actualizaciones desde el iPhone: aviso de versión nueva e instalación con SideStore o AltStore (o descarga del .ipa).
- Guardar lo que escuchas para oírlo sin conexión, playlist con un álbum, compartir enlaces, deshacer, inicio con destacado y tendencias numeradas, color de la app, visualizador y ecualizador de 5 bandas.
- Se vuelve a SwiftUI como app principal: el flujo **App iPhone** compila `ios/` en cada cambio; Expo queda archivado (flujo manual aparte).
- Reproductor: cabecera compacta con letra o cola abiertas, bordes desvanecidos (ya no parecen montarse sobre el título), controles en cinco columnas que no se salen de la pantalla e indicador de espera solo si la carga dura más de 0,6 s.
- Arranque: la siguiente canción se precarga cuando la actual ya está lista, en vez de descargarse a la vez (mejora pendiente de medir en el iPhone).
- Mini reproductor con fondo hasta la barra de pestañas: las listas ya no asoman entre ambos.
- Géneros con el color de la web y portada girada en la esquina, sin recortes del nombre; botón Atrás solo con la flecha.
- Matriz de paridad con la web en `ios/PARIDAD.md`.
- Reproductor rediseñado al estilo Apple Music: portada a todo el ancho fundida con el color de la canción, tres controles, volumen del sistema y abajo Letra · AirPlay · Cola; temporizador y opciones en «…»; aleatorio, repetir y radio dentro de la cola.
- Letra sincronizada estilo Apple Music: líneas grandes a la izquierda, la actual iluminada (palabra por palabra si la letra trae tiempos), interludios y desfase como en la web.
- Las listas de todas las pestañas terminan encima del mini reproductor: el último elemento ya se puede tocar sin arrastrar.
- Buscador como la web: pestañas Todo/Canciones/Artistas/Álbumes/Playlists/Personas, «Resultado principal» y «Mostrar todo»; etiqueta LRC.
- Entrada: restablecer contraseña con el enlace del correo, reenviar verificación y crear el primer administrador. Administración: aprobar cuentas con el enlace del correo, géneros y canciones por mes. Inicio: mezcla del día y aleatorio de todo; género: aleatorio con todo el género.
- Playlists guardadas con una sola petición a las públicas (antes una por playlist).

## App de iPhone 2.0 (React Native + Expo, archivada)
- Nueva app en TypeScript que comparte la interfaz web móvil real, con cola de audio nativa de Expo, controles bloqueados, radio, temporizador, sesión protegida y descargas persistentes por cuenta. GitHub Actions genera el nuevo IPA; se conserva SwiftUI como versión anterior. Arquitectura híbrida y límites documentados en ios-expo/README.md.


## App de iPhone 1.0 (nativa)
- Ampliación de iOS: soporte, géneros, mantenimiento y detalle de usuarios; letras con archivos y borrado, ajuste del banner, recomendaciones y guardado de mixes; temporizador y restauración de cola; copias sin conexión y descargas con cancelación/reintento; mejoras de memoria, carga y protección de sesiones. Pruebas de modelos y LRC preparadas para la compilación final.
- Revisión de desbordamientos de imágenes y reproductor, miniaturas ligeras, arranque de audio con menor búfer, correcciones de carga de letras y editor de información, portada y letra para colaboradores y administradores. Pendiente de comprobar en iPhone.
- Mini reproductor colocado sobre las pestañas mediante el área segura, sin separación fija ni relleno duplicado; mensajes anteriores sin salto al final, protección contra envíos repetidos y conservación del borrador al enviar; limpieza de navegación y biblioteca al salir de la cuenta.
- Correcciones de iOS: portadas cuadradas y adaptadas al espacio del reproductor, búsqueda e imágenes protegidas contra respuestas antiguas, radio que respeta los cambios de cola y barra de tiempo reiniciada al cambiar de canción.
- Nueva app nativa para iPhone en `ios/` (SwiftUI, iOS 17 o más nuevo), con las mismas funciones que la web: inicio, buscar (también por ánimo), biblioteca, playlists, álbumes, artistas, géneros, mixes, perfiles, mensajes en tiempo real, notificaciones, estadísticas, cuenta, subir canciones y administración.
- Reproductor del sistema: pantalla bloqueada, Centro de control, AirPlay, siguiente canción precargada y radio automática al terminar la lista.
- Letra sincronizada (completa y en miniatura) y descargas para escuchar sin conexión.
- Instrucciones para instalarla en `ios/LEEME.md`.
- GitHub Actions compila la app en cada cambio de `ios/` y deja `SpaceMusic.ipa` para descargar (flujo "App iPhone"), así se puede trabajar desde Windows.

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
