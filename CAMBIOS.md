## iPhone 3.30: portada estable y letra independiente en pantalla bloqueada
- Elimina por completo el cambio de video/imagen por verso en pantalla completa. La misma portada y la misma identidad de artwork permanecen durante la canción, tanto en Con letra como en Original.
- La letra de pantalla completa pasa a una tarjeta de Actividad en vivo (ActivityKit/WidgetKit), separada de la portada. Actualiza solo texto por verso y pausa, sin modificar los campos de artista/título ni el audio. El modo compacto conserva letra sobre portada y los estilos existentes.
- Requiere permitir Actividades en vivo y abrir la app una vez antes de bloquear. Estados reales para permiso desactivado, inicio pendiente y fallo. iOS puede limitar las actualizaciones; no se promete animación continua ni sincronía perfecta.
- Extensión nativa incluida en el IPA y firmada ad hoc con el mismo procedimiento existente, sin claves nuevas, APNs ni cambios de servidor. Pruebas y compilación no sustituyen comprobar el resultado visual en un teléfono.

## iPhone 3.29: pantalla completa con letra, sin cambiar la función del botón
- El botón Portada en toda la pantalla vuelve a agrandar la portada en Con letra y Original. Se conserva 3.28 como base; no se revierte a versiones anteriores.
- Publicación de clips ya codificados y previsualización coincidente con el primer cuadro, sin cuadro de entrada vacío. La portada de respaldo se mantiene estable; el clip anterior sigue disponible mientras se prepara el siguiente.
- Precarga del próximo verso, rechazo de respuestas antiguas, invalidación al buscar/pausar/reanudar/cambiar sincronía y generación de cuadros por secuencia para no acumular cientos de imágenes en memoria.
- Se mantienen las correcciones de palabras pendientes y los ajustes de estilo. La eliminación visual del parpadeo requiere comprobarse en un iPhone físico; las transiciones son del sistema.

## iPhone 3.28: letra de bloqueo estable
- Se elimina el video por verso que reiniciaba la superficie animada de iOS. Con letra se usa artwork estático de alta resolución, ampliable al tocar la portada; el fondo animado de pantalla completa queda para Original. La transición final sigue controlada por iOS.
- Las palabras limitadas por frecuencia se vuelven a intentar hasta dibujarse; reloj monotónico, precarga de la línea siguiente y deduplicación de dibujos pendientes. Versos repetidos conservan su índice y las imágenes de estilos antiguos se descartan.
- Ajustes aclaran el modo compatible y retiran el selector de entrada de video. Los estilos guardados siguen siendo legibles.
- Validación: pruebas Swift de política y compilación iOS en Actions; sin prueba física de pantalla bloqueada.

## iPhone 3.27: letra en la pantalla bloqueada sin parpadeos y más personalización
- Portada a pantalla completa con letra: un video por verso. El verso entra con su animación (aparecer, deslizar, crecer, escribir o ninguna) y cada palabra se enciende suave dentro del video, justo al cantarla. Ya no se cambia la imagen varias veces por verso (eso hacía parpadear la pantalla y se veía lento). El video del verso siguiente se prepara mientras suena el actual.
- Palabras largas: el tamaño baja hasta que la palabra más larga cabe entera (antes se partía y se veía cortada).
- Nuevas opciones: posición «Libre» (altura y lado), ancho de la letra, inclinación, transparencia, 4 colores nuevos, color de la palabra que suena, tamaño del verso siguiente, animación de entrada y viñeta.

## iPhone 3.26: chat sin trabarse, letra en pantalla completa y todo más ordenado
- Mensajes: con Liquid Glass el chat se trababa (al abrirlo se quitaba el mini reproductor, iOS rehacía todas las pestañas y el chat se volvía a abrir sin fin). El accesorio ya no cambia la estructura de la vista.
- Pantalla bloqueada a pantalla completa: ahora lleva la letra con su estilo, el desenfoque y la sombra; cada verso es un video corto y el siguiente se prepara antes de que suene.
- Botón «Continuar con Google» con el diseño oficial (blanco, «G» de colores). Los avisos de la vuelta ya no salen con «+» y la cuenta pendiente se muestra como aviso, no como error.
- Tus gustos: géneros como tarjetas de colores (sin etiquetas genéricas), contador y botón «Listo» fijo abajo.
- Tú, Cuenta, Administración y Soporte reorganizados en grupos (Tu música, Descubre, Ajustes, Administración; Música, Personas, Servidor; Estado ahora, Revisiones, Cuentas y seguridad, Herramientas).

## iPhone 3.24: Liquid Glass
- Diseño Liquid Glass de iOS 26 (`App/Cristal.swift`): barra de pestañas de vidrio que se encoge al desplazar, mini reproductor como accesorio de la barra (como Apple Music), botones secundarios y tarjetas de vidrio. Se apaga en Ajustes → Reproducción («Diseño Liquid Glass»); en iOS 17 y 18 sigue el diseño clásico.
- La compilación usa macOS 26 con Xcode 26.6.
- Buscar: sin el conteo de canciones en «Explorar todo».
- Editar canción: elegir si la portada nueva es solo de la canción, solo del álbum o de ambos.
- Soporte → Alertas: cada cuánto avisar por tipo de alerta.

## iPhone 3.23: letra viva en la pantalla bloqueada
- Más fluida: el reloj revisa cada 0,25 s (antes 0,5 s) y el verso cambia 0,25 s antes, porque el fundido del sistema tarda; el verso siguiente ya está dibujado (precarga con la clave correcta aunque haya pausas en la letra).
- «Iluminar cada palabra»: las palabras que ya sonaron brillan y las que faltan quedan atenuadas (con los tiempos de la letra si los trae; si no, repartidas en el verso). Como mucho unas 3 imágenes por segundo.
- Posición «Flotante»: cada verso aparece en otro lugar (8 lugares dentro de lo visible, nunca fuera) y, con «Mecer», se mueve suave mientras suena.
- «Portada a pantalla completa (alta resolución)»: imagen de 2048 px para verla nítida al tocar la portada en la pantalla bloqueada (como Apple Music).
- 5 tipografías nuevas: Futura condensada, Clarendon, Rockwell, Copperplate y Hecha a mano (Bradley Hand).

## iPhone 3.22: tu semana y descargas automáticas
- Tu resumen: nueva opción «Esta semana» (`/api/resumen?semana=true`); el aviso de los lunes abre directo «Tu semana».
- Descargas automáticas (`DescargasAutomaticas`): con Wi-Fi y el iPhone cargando, una vez al día se guardan tus más escuchadas, el Mix del día y lo nuevo de tus artistas (máximo 60). Se apaga en Ajustes → Reproducción.

## iPhone 3.21: más estilos para la pantalla bloqueada
- 7 opciones nuevas en «Personalizar la pantalla bloqueada»: alineación (centro, izquierda, derecha), efecto de la letra (sombra, resplandor de su color o ninguno), fondo de la letra (recuadro translúcido o banda), contorno en las letras, interlineado (80–140 %), espacio entre letras y filtro de la portada (normal, blanco y negro, sepia, vivo). Lo guardado antes se conserva (`EstiloBloqueo.init(from:)` acepta opciones que faltan).
- La vista previa queda fija arriba: al deslizar las opciones siempre se ve el resultado.

## iPhone 3.20: agregar al instante, botón Radio y pantalla bloqueada a tu gusto
- Agregar a una playlist o a Me gusta se ve al instante: `Biblioteca` avisa con `.bibliotecaCambio` y la playlist abierta (o Me gusta) se recarga sola, sin cambiar de pestaña.
- Artista: el botón «Radio» es una cápsula de tamaño fijo con el texto en una línea (antes se aplastaba y quedaba casi redondo).
- Pantalla bloqueada: la letra va dentro de la portada, abajo y centrada. Nueva pantalla «Personalizar la pantalla bloqueada» (menú ⋯ → Letra) con vista previa: posición (abajo/centro/arriba), 5 tipografías (manuscrita, moderna, elegante, máquina de escribir, gruesa), tamaño 70–150 %, 5 colores, desordenada (movida e inclinada un poco, siempre legible y sincronizada), verso siguiente, mayúsculas, desenfoque y sombra de la portada, y «Original» (solo la portada, sin letra ni sombra). `EstiloBloqueo` (se guarda en el iPhone) y `ArteBloqueo` dibuja con él.

## iPhone 3.15: iniciar sesión en la TV

## Versión 3.19
- Biblioteca: pide solo álbumes y playlists guardados, con permisos comprobados en el servidor.
- Álbumes y artistas: carga incremental de 48 elementos, búsqueda en todo el catálogo, recarga y reintento; conserva los endpoints anteriores para clientes instalados.
- Medición opcional del inicio real de audio, sin contenido musical ni datos personales en la muestra. Un fallo de medición no modifica sesión ni reproducción.
- API optimizada: rutas de audio en caché, playlists por lotes, cálculos de recomendaciones fuera de peticiones, estadísticas agregadas y tareas con prioridad del audio.


## Versión 3.18
- Sincronía compartida mediante offset LRC: ADMIN/COLABORADOR pueden guardar ±60 s, con revisión SHA-256 y bloqueo transaccional para evitar sobrescrituras. Conserva texto y marcas de palabras. Sin migración.
- Panel persistente de ajuste con vista previa y guardado explícito; usuarios conservan ajuste personal. Invalidación en vivo por el SSE existente en web, TV y iPhone.
- iPhone: ventana independiente con pasos de 0,1/0,5 s, deslizador, volver 5 s, pausa y recarga; no se cierra al ajustar ni guardar.
- Pruebas Java de permisos, validación, persistencia y concurrencia; pruebas de offsets de líneas/palabras en JavaScript y Swift.

- Dispositivos → «Iniciar sesión en una TV»: aparecen las TVs de tu misma red wifi que esperan para entrar (`/api/auth/tv/cercanas`); al tocar una, o al escribir el código que muestra la TV, entra con tu cuenta (`POST /api/auth/tv/vinculo/{código}/aprobar`). El QR de la TV también se puede escanear con la cámara (abre la web).
- Las TVs aparecen con su ícono en «Reproducir en».

## Actualización 3.14: arranque y conservación de la canción
- Inicio con 1 segundo continuo de audio; después vuelve al búfer de 5 s. SyncPlay conserva 30 s y su reloj.
- Se reanuda el arranque cuando llegan datos suficientes en lugar de esperar la estimación conservadora de AVPlayer.
- Solo el final normal avanza: los errores, las esperas agotadas y las pausas conservan la pista y la cola. Reintentar recarga la misma pista y conserva la posición.
- La vigilancia no confunde una red lenta con progreso con un fallo. Pruebas de progreso y espera sin datos.
- Se renueva la revisión de la URL de audio para usar las copias preparadas del servidor.

# Registro de cambios

## iPhone 3.13: notas de voz, foto en los avisos, perfil más claro
- Barra de pestañas siempre con fondo (en los álbumes quedaba transparente sobre la lista).
- Notificaciones de mensajes como conversación (`INSendMessageIntent`): la foto de quien escribe ocupa el lugar del ícono, a la izquierda. Permiso `com.apple.developer.usernotifications.communication` en `SpaceMusic.entitlements` (la compilación lo incrusta con firma ad hoc); si iOS no lo concede, la foto va como imagen del aviso. Los avisos en segundo plano (resumen) también traen la foto.
- Notas de voz: se escuchan antes de enviarlas (descartar / escuchar / enviar) y todas se reproducen, también las propias (`VozMensajes`: baja el audio y lo reproduce con `AVAudioPlayer`, barra para saltar).
- Perfil: vibras como insignias compactas en dos columnas con su ícono (antes salía el nombre del ícono, «sparkles», «fire»); géneros en lista con barras, el favorito arriba y sin etiquetas genéricas («Music»).
- Subir canciones: revisa antes cuáles ya están en el servidor y avisa en la campana al terminar (como la web).

## iPhone 3.12: mensajes con fotos y voz, inicio con Google/Facebook/Apple y perfil nuevo
- Mensajes: fotos (`PhotosPicker`) y notas de voz (`GrabadoraVoz`, AAC) cifradas en el servidor; borrar desde el menú de cada burbuja; bloquear o desbloquear desde el menú del chat. Las notificaciones de mensajes llevan la foto de quien escribe.
- `Entrada`: botones «Continuar con Google / Facebook / Apple» (solo los activos en el servidor) con `webAuthenticationSession` efímera y vuelta por `spacemusic://oauth`.
- Letra: botón de traducir con ícono, sin chip «Versos»; «Compartir versos a imagen» abre la selección por bloques desde la letra.
- Pantalla bloqueada: sombra pareja y suave sobre toda la portada, sin halo.
- Perfil: vibras en tarjetas con degradado (carrusel), géneros en gráfica de dona con leyenda; se quitó «escuchas».

## iPhone: pantalla bloqueada más fluida y legible
- Un solo cambio de imagen por verso (el sistema hace el fundido) y la del verso siguiente se dibuja antes (`precargadas`), para cambiar justo a tiempo; antes 6 cuadros se veían a saltos.
- `ArteBloqueo`: viñeta ovalada en las esquinas de la columna visible y halo oscuro detrás de la letra, más fuertes cuanto más clara es la portada en la zona de la letra (`luz`, medida en 8 × 8 píxeles); sombra de la letra más marcada.

## iPhone: pantalla bloqueada con el diseño de referencia
- `ArteBloqueo`: imagen cuadrada de 1500 px (iOS solo la pone a pantalla completa si es cuadrada; la vertical salía como tarjeta). Portada nítida con degradado oscuro abajo y la línea que suena centrada al 53 % del alto dentro de la columna que se ve (ancho ÷ alto de la pantalla), en letra manuscrita Savoye blanca con sombra suave; solo la línea actual. Misma transición entre versos.

## iPhone: pantalla bloqueada con la portada a la medida y la letra con transición
- **Portada vertical** (`ArteBloqueo.swift`): la imagen de la pantalla bloqueada tiene la proporción de la pantalla del iPhone (1000 px de ancho), así no queda mal colocada. Portadas de 900 px o más: nítidas a pantalla completa; más pequeñas: tarjeta redondeada con sombra sobre la misma portada desenfocada (no se ven pixeladas).
- **Letra**: al llegar, la portada se desenfoca (no desaparece) y la letra va en la franja central (35–63 % del alto), entre el reloj y los controles, con un velo más oscuro para que siempre se lea. La línea que suena grande y la siguiente atenuada.
- **Transición fluida** entre versos: 6 cuadros en unos 0,4 s (la línea anterior sube y se desvanece, la nueva entra desde abajo); los cuadros se dibujan fuera del hilo principal. Al irse la letra, la portada vuelve a verse nítida.

## iPhone: el mismo reproductor controla tus otros dispositivos, SyncPlay con código y letra sobre la portada
- **Como Spotify Connect, sin pantallas extra** (`Reproductor.remoto`, `reflejar`, `ordenRemota`; `ControlRemoto.destino`, `elegir`, `elegirEsteIPhone`): se quitaron la pantalla «Controlar…» y el mini reproductor aparte. Al elegir otro dispositivo en Dispositivos → «Reproducir en», el reproductor de siempre (mini y completo, letra incluida) muestra su canción, tiempo y cola, y reproducir, pausar, anterior/siguiente, mover, volumen, elegir canciones y la cola («cola», agregar, quitar, mover) le mandan la orden. Aquí no suena nada mientras tanto y se informa vacío. Se toma solo el dispositivo activo cuando aquí no suena nada; «Este iPhone» trae la música desde el mismo segundo. Las órdenes que traen música («reproducir», «transferir») salen de ese modo.
- **SyncPlay con código** (`JuntosVista`): código grande para copiar en la sala, «Unirme con código» y el código en el enlace compartido.
- **Letra en la pantalla bloqueada sobre la portada** (`Reproductor.portadaConLetra`): en vez de poner la línea en el título (se cortaba), se dibuja grande sobre la portada desenfocada, con la siguiente línea atenuada; el título y el artista quedan normales.

## iPhone: versos desde la letra, traductor, letra en la pantalla bloqueada y dedicatorias ordenadas
- **Versos desde la letra** (`LetraCompleta` en `ReproductorVista.swift`): botón «Versos» arriba de la letra; se tocan los versos (hasta 8) y «Crear imagen» abre la imagen con Compartir y Guardar en Fotos (`NSPhotoLibraryAddUsageDescription`). Ya no se abre otra ventana para elegir. «Compartir versos como imagen» del menú y de la letra ampliada usan lo mismo.
- **Traductor de la letra** (`TraducirLetra.swift`): traducción del propio iPhone (Apple Translation, sin mandar la letra a otros servicios). iOS 18: la traducción aparece debajo de cada línea; iOS 17.4: hoja de traducción del sistema; antes de 17.4 no aparece el botón. Las líneas que ya están en español no se repiten.
- **Letra menos saturada**: texto de 31 a 27 puntos, menos espacio entre líneas y letra en texto como versos separados.
- **Letra en la pantalla bloqueada** (`Reproductor.letraEnBloqueo`): la línea que suena aparece como título en la pantalla bloqueada, el Centro de control y la isla dinámica, con «Canción · Artista» debajo. Se apaga en el menú … → Letra. (Sin widget: un widget no se actualiza al ritmo de la canción; esto sí, sin consumir batería extra.)
- **Dedicatorias**: pestañas Recibidas/Enviadas, deslizar para borrar, «Borrar todas» (las guardadas y programadas se quedan) y filas más limpias.
- **Portada original (HD)** en Editar canción (`PortadaEnLineaHoja`): opciones de iTunes y Deezer para elegir.

## iPhone: logo de ondas en blanco y negro
- **Logo nuevo** (`App/Logo.swift`): círculo blanco con siete ondas negras, igual que la web y Android. Ícono de la app con el mismo logo.
- Al abrir la app aparece el círculo, las ondas crecen desde el centro y laten dos veces; en el inicio de sesión las ondas se mueven suave como un ecualizador (quieto con «Reducir movimiento»).
- Android 2.1 y TV 1.1: ícono y notificación con el logo nuevo.

## iPhone: SyncPlay sin cortes, inicio de sesión nuevo y logo animado
- **SyncPlay sin cortes para quien se une** (`AjusteSincronia.swift`, `EscuchaJuntos.swift`): antes, con una conexión lenta, cada salto para alcanzar a los demás tardaba en volver a sonar, quedaba otra vez atrasado y volvía a saltar (el audio se cortaba a cada rato). Ahora el salto apunta un poco adelante (aprendido midiendo cuánto tarda en volver a sonar), después de un salto se espera antes de corregir otra vez (más si se repiten) y los desfases medianos se corrigen solo con la velocidad. Solo un estado nuevo del servidor provoca un salto inmediato. Misma lógica que la web (`js/syncplay-ajuste.js`). Pruebas: `SyncPlayTests` (simulación con 0,2–3 s por salto).
- **Acelerar sin detenerse a cargar**: dentro de SyncPlay se guardan 30 s de audio por adelantado (antes 5 s) y solo se acelera (hasta 1,05) si hay audio de sobra cargado; antes AVPlayer podía pausarse a cargar al subir la velocidad.
- **Pausas locales**: una llamada, Siri, desconectar los audífonos, el temporizador o un fallo al cargar pausan solo este iPhone, no a todo el SyncPlay. Al tocar reproducir (o al terminar la llamada) vuelve al punto de los demás.
- **Inicio de sesión nuevo** (`Entrada.swift`): blanco y negro con el logo de la app, campos con ícono y borde al escribir, ver/ocultar contraseña, botón activo solo con los datos escritos, vibración y sacudida si algo falla. Se quitó el enlace del servidor: la app usa siempre el servidor de SpaceMusic.
- **Logo animado al abrir** (`App/Logo.swift`): las corcheas del ícono se dibujan, aparecen las cabezas y el nombre, y se desvanece (versión corta con «Reducir movimiento»).

## iPhone: SyncPlay compartido y barra «Escuchando en…»
- **SyncPlay con control compartido** (`EscuchaJuntos.swift`, `JuntosVista.swift`): sigue el estado del servidor (`ts`, `version`) con el reloj sincronizado; los botones del reproductor, la pantalla bloqueada y la pantalla de SyncPlay mandan la orden para todos (`/api/juntos/{id}/control`). Ajuste fino de velocidad (0,95–1,05) y salto si el desfase pasa de 0,6 s. Sin emojis y «SyncPlay» en vez de «Sala».
- `Reproductor`: enganche `controlExterno` (SyncPlay), `sinControlExterno`, `ajustarVelocidad` y `inicioUsuario` (el control entre dispositivos da el turno solo ante un inicio de la persona).
- **Barra «Escuchando en…»** (`MiniRemoto` en `Principal.swift`): si suena en otro dispositivo y aquí no, el mini reproductor lo controla (reproducir/pausar, siguiente, progreso) y abre el control completo. Dentro de SyncPlay el iPhone no compite por ser el dispositivo activo.

## iPhone: controlar otro dispositivo y accesos de Mensajes
- **Controlar otro dispositivo de la cuenta** (`Reproductor/ControlRemoto.swift`, `Vistas/ControlRemotoVista.swift`): el iPhone se anuncia en `POST /api/control/presencia` (al cambiar canción, pausa, salto o volumen, y cada 30 s), recibe órdenes por la conexión en vivo (`control-orden`) y ve los demás dispositivos (`control-dispositivos`). En **Dispositivos → Tus otros dispositivos**: reproducir/pausar, anterior/siguiente, adelantar, volumen, «Escuchar aquí» y «Pasar mi música». Punto en el botón Dispositivos del mini reproductor cuando suena música en otro dispositivo. Nombre del dispositivo editable. Mismo contrato que la web y Android (servidor 3.7).
- `Reproductor.fijarVolumen(_:)`: volumen propio de la app (iOS no permite mover el volumen del sistema).
- **Arreglo en Mensajes**: los accesos SyncPlay, Coincidimos y Dedicatorias eran tres `NavigationLink` en una sola fila de la lista; SwiftUI activaba los tres con un toque (siempre aparecía SyncPlay y «Atrás» pasaba por Dedicatorias y Coincidimos). Ahora son botones independientes (`.borderless`) que abren una sola pantalla con `Navegacion.ir`, y «Atrás» vuelve directo a Mensajes.

## iPhone: SyncPlay y ajustes de navegación
- Registro de descargas plegable con desplazamiento propio; claves de Spotify accesibles antes del registro.
- Corrección de la navegación de Gestionar usuarios a su ficha y regreso.
- Menú de canciones organizado por grupos; Dispositivos y SyncPlay antes del corazón del mini reproductor.
- SyncPlay desde Dispositivos y Mensajes, invitaciones por enlace y tarjetas de chat para abrir y unirse a la sala; accesos a Coincidimos y Dedicatorias en Mensajes.
- Letra ampliada con fondo de portada, cabecera y controles; desplazamiento suave y margen adaptable al espacio.

## iPhone: API 3.5 y actualizaciones descargables
- Publicado en el servidor como iPhone 1.21: /app/ios.html y /app/SpaceMusic-21.ipa. Rutas específicas de iPhone añadidas al servidor 3.5 conservando intacto el resto del JAR; respaldo, arranque sano y descarga pública verificados.
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
