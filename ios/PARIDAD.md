# Paridad de la app SwiftUI con la web móvil

Referencia: rutas de `src/main/resources/static/js/main.js` y sus vistas en `js/views/`. Estados:

- **pendiente**: falta o no funciona como la web.
- **implementado**: el código existe y usa la API real; **no** se ha comprobado en un iPhone.
- **compilado**: además pasó la compilación de GitHub Actions (App iPhone) del commit indicado.
- **probado**: verificado en un iPhone real (indicar modelo, iOS y fecha).
- **limitado**: funciona con una restricción conocida.

Hasta ahora **ninguna fila está probada en dispositivo**. La evidencia disponible es revisión de código frente a la web y el backend, pruebas XCTest de modelos y LRC (`swift test`) y la compilación sin firmar en GitHub.

| Pantalla o función | Web (referencia) | SwiftUI | Diferencias o errores conocidos | Evidencia | Estado |
|---|---|---|---|---|---|
| Inicio | `#/` homeView: saludo, destacado, accesos, mixes, tendencias, recomendaciones, amigos | `InicioVista` con `/api/inicio` | Diseño del destacado distinto; revisar en dispositivo | Código | implementado |
| Buscar | `#/buscar`: pestañas Todo/Canciones/Artistas/Álbumes/Playlists/Personas, «Resultado principal», 5 canciones con «Mostrar todo», bloque de estilo o ánimo, recientes y «Explorar todo» | `BuscarVista` con las mismas pestañas y reglas (artista principal si su nombre empieza por lo buscado), etiqueta LRC en las filas | — | Código | implementado |
| Tarjetas de género | `.genre`: color propio de `genreColor`, nombre arriba, portada girada en la esquina | `TarjetaGenero` con la misma paleta y hash; texto con margen fijo respecto a la portada y altura que crece con el tamaño de letra | Corregido: la portada ocupaba toda la tarjeta y el nombre quedaba recortado | Código | implementado |
| Género, álbum, artista, mix | `#/genero/:n`, `#/album/:n`, `#/artista/:n`, `#/mix/:id` | `ColeccionVistas` | — | Código | implementado |
| Biblioteca | `#/biblioteca`: segmentos, accesos, playlists, guardados | `BibliotecaVista` (Todo / Playlists / Álbumes) | Portadas en cuadros fijos de 56 pt con `Portada`/`Mosaico` (relleno y recorte, sin deformar) | Código | implementado |
| Favoritos e historial | `#/favoritos`, `#/historial` | Destinos `.favoritos`, `.historial` | — | Código | implementado |
| Playlists y gestión | crear, editar, pública, duplicar, agregar/quitar, reordenar, imagen | `ColeccionVistas`, `EditarPlaylistHoja`, `AgregarAPlaylistHoja` | — | Código | implementado |
| Descargas y sin conexión | service worker + `offline.js` | `Descargas` (por servidor y cuenta), `CopiaLocal` | — | Código | implementado |
| Perfil propio y público | `#/perfil`, `#/u/:u`: banner, seguir, compatibilidad, actividad | `PerfilVistas` | — | Código | implementado |
| Mensajes | `#/mensajes[/:u]`, SSE en vivo | `MensajesVistas`, `Eventos` (SSE) | — | Código | implementado |
| Notificaciones y novedades | panel, `#/novedades` | Destinos `.notificaciones`, `.novedades` | — | Código | implementado |
| Cuenta y estadísticas | `#/estadisticas`, cambiar contraseña/correo, colaborador | `CuentaVistas` | — | Código | implementado |
| Subir y administración | `#/subir`, `#/admin` (Resumen, Soporte, Géneros, Cuentas nuevas, Biblioteca, Usuarios, Colaboradores) y `aprobar.html` | `AdminVista`, `AdministracionDetalle`, `BibliotecaAdminVista`; los 25 endpoints `/api/admin/*` en uso; aprobar con el enlace del correo; géneros y canciones por mes en el resumen | — | Código | implementado |
| Mini reproductor y final de las listas | barra flotante; el contenido termina encima | `MiniReproductor` superpuesto; `contentMargins` con su alto medido en todas las listas de cada pestaña | Corregido: el último elemento quedaba debajo y había que arrastrar para tocarlo | Código | implementado |
| Reproductor completo | `nowplaying.js`: portada, letra, cola, controles, volumen | `ReproductorVista` rediseñada al estilo Apple Music: portada a todo el ancho fundida con el color de la canción, título/artista con Me gusta y «…», barra de tiempo, 3 controles, volumen del sistema, Letra · AirPlay · Cola. Temporizador y demás opciones en «…»; aleatorio, repetir y radio en la cola | — | Código | implementado |
| Letras | `/api/letras/{id}`, búsqueda, sincronizada, toque para buscar, interludios, desfase por canción | `Letras`, `LectorLRC` (offset, créditos, interludios y tiempos por palabra), vista estilo Apple Music con karaoke palabra a palabra si la letra trae tiempos | Desfase manual por canción de la web no implementado | XCTest de LRC | limitado |
| Cola | sonando ahora, siguientes, vaciar, quitar, arrastrar, radio automática | `ColaVista`: botones Aleatorio · Repetir · Radio, sonando ahora, arrastrar, borrar y menú por canción | — | Código | implementado |
| Aleatorio, repetir, radio, temporizador | `player.js` | `Reproductor` (AVQueuePlayer) | — | Código | implementado |
| Arranque de canciones | `_load` con un solo audio | `playImmediately`; la siguiente se precarga cuando la actual ya está lista | Mejora sin medir en dispositivo | Código | implementado |
| Pantalla bloqueada y Centro de control | Media Session | `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter` | — | Código | implementado |
| AirPlay y auriculares | — | `AVRoutePickerView` | — | Código | implementado |
| Entrada y cuenta nueva | `login.html`, `register.html`, `forgot-password.html`, `reset-password.html`, `setup.html` | `Entrada`: entrar, registro, olvidé, restablecer con el enlace del correo, reenviar verificación y crear el primer administrador cuando el servidor lo pide | Los enlaces del correo se pegan en la app (no hay enlaces universales) | Código | implementado |
| Mezclas | mixes y radio | Inicio: «Mezcla del día» y «Aleatorio de todo»; género: aleatorio con todo el género | — | Código | implementado |
| Ecualizador | Web Audio; la propia web lo oculta en iPhone | No existe | Sin implementación nativa | — | pendiente |
| Notificaciones con la app cerrada (APNs) | — | Solo locales con la app activa o en segundo plano | El servidor no envía push a iOS | — | limitado |
| Formatos OGG/Opus | El navegador decide | AVFoundation no los reproduce: se saltan con aviso | Requeriría transcodificar en el servidor | — | limitado |

## Pruebas pendientes en iPhone

1. Reproductor: portada, letra y cola con letra grande y en iPhone pequeño (SE) y grande (Pro Max); controles completos y sin cortes.
2. Buscar: tarjetas de géneros con nombres largos y tamaño de letra accesible.
3. Biblioteca y listas: último elemento visible encima del mini reproductor; nada asoma entre mini reproductor y pestañas.
4. Tiempo de inicio de canción con Wi-Fi y datos; indicador de espera.
5. Pantalla apagada, siguiente automática, llamada, auriculares, AirPlay, controles bloqueados.
6. Descargas, modo avión, borrar descargas y cambio de cuenta.

## Cobertura de la API

Inventario de las 148 rutas `/api` de los controladores Java (`@GetMapping`, `@PostMapping`…) cruzado con las cadenas de ruta del código Swift (11/11/2026):

- **Todas las rutas que usa la web móvil están usadas en iOS**, incluidas las 25 de `/api/admin/*`. La última tanda añadida: `auth/estado`, `auth/setup-admin`, `auth/reset-password`, `auth/resend-verification`, `auth/aprobacion` (GET y POST), `playlists/publicas`, `estadisticas/top-generos`, `estadisticas/por-mes`, `shuffle/diario`, `shuffle/cola`, `shuffle/genero/{g}`.
- `PUT /api/usuario/perfil` (nombre visible en la web): iOS usa el equivalente `PUT /api/auth/me`.
- **Sin pantalla propia, a propósito:**
  - archivos que se cargan como imagen o enlace: `auth/avatar/{f}`, `usuario/avatar/{f}`, `perfiles/banner/{f}`, `playlists/imagen/{f}`, `portada/db/{id}`, `portada/id/{id}`; enlaces del correo que abre el navegador: `auth/verify`, `auth/confirm-email`;
  - rutas de la versión anterior que la web ya no usa y tienen reemplazo usado en iOS: `canciones/album|albumes|artista|buscar|favoritos|recientes|top`, `canciones/{id}/favorito|letra|reproducir`, `usuario/favoritos/{id}/check`, `usuario/reproduccion/{id}`, `history`, `next/{id}`, `shuffle/similar/{id}` (equivale a la radio), `letras/subir/{id}` (iOS importa el archivo y lo guarda con `PUT /api/letras/{id}`), `playlists/con-imagen` (iOS crea y después sube la imagen).
