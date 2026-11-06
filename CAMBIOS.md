# Registro de cambios

## Versión 2.4 (app Android 1.3)
- **Géneros automáticos:** cada artista se consulta una vez en MusicBrainz y su género pasa a las canciones que no lo tienen (tabla `artista_genero`, se revisa cada 30 min).
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
