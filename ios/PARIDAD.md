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
| Buscar | `#/buscar`: búsqueda con estilos/ánimos, recientes, géneros | `BuscarVista` (`/api/buscar?q=`, `/api/generos`), descarta respuestas antiguas | — | Código | implementado |
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
| Subir y administración | `#/subir`, `#/admin` | `AdminVista`, `AdministracionDetalle`, `BibliotecaAdminVista` | — | Código | implementado |
| Mini reproductor | barra flotante sobre la navegación | `MiniReproductor` en `safeAreaInset` de cada pila | Corregido: fondo degradado hasta las pestañas (antes asomaban filas entre ambos); espera solo tras 0,6 s | Código | implementado |
| Reproductor completo | `nowplaying.js`: portada, letra, cola, controles, volumen | `ReproductorVista` | Corregido: cabecera compacta con letra/cola, paneles recortados con bordes desvanecidos, controles en 5 columnas | Código | implementado |
| Letras | `/api/letras/{id}`, búsqueda, sincronizada, toque para buscar, desfase por canción | `Letras`, `LectorLRC` | Desfase manual por canción de la web no implementado | XCTest de LRC | limitado |
| Cola | sonando ahora, siguientes, vaciar, quitar, arrastrar, radio automática | `ColaVista` (`onMove`, `onDelete`) | — | Código | implementado |
| Aleatorio, repetir, radio, temporizador | `player.js` | `Reproductor` (AVQueuePlayer) | — | Código | implementado |
| Arranque de canciones | `_load` con un solo audio | `playImmediately`; la siguiente se precarga cuando la actual ya está lista | Mejora sin medir en dispositivo | Código | implementado |
| Pantalla bloqueada y Centro de control | Media Session | `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter` | — | Código | implementado |
| AirPlay y auriculares | — | `AVRoutePickerView` | — | Código | implementado |
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
