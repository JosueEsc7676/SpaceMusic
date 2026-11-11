# Revisión de iOS frente a web y Android

Referencia: Android abre la web de `src/main/resources/static/js/`. Se mantienen las rutas existentes del servidor.

## Funciones incorporadas

| Apartado | Implementación nativa |
|---|---|
| Canciones | Editar título, artista, álbum, género, año y pista; cambiar portada; reflejar las ediciones en cola y listas abiertas |
| Letras | Pegar texto o LRC, importar archivo, buscar, borrar con confirmación y refrescar la canción actual |
| Perfil | Ajustar posición vertical del banner con vista previa; biografía y privacidad sin solicitudes al cargar valores iniciales |
| Administración | Estadísticas globales, tendencias, artistas, soporte, recursos, usuarios conectados, registro de errores con detalles y vaciado |
| Usuarios | Detalle y actividad, últimas canciones, recuperación y cierre de sesiones con confirmación, respuestas a solicitudes |
| Géneros | Totales, familias, artistas sin género, búsqueda y asignación manual |
| Mantenimiento | Estado y progreso del escáner, completar datos, corregir portadas y limpiar archivos ausentes con confirmaciones |
| Playlists y mixes | Recomendaciones, añadir sugerencias, guardar y renovar mix; mixes por artista y género |
| Listas | Filtrado sin distinguir acentos, agregar en grupo a favoritos/cola/playlist; búsqueda sin recorte a 20 canciones |
| Reproductor | Temporizador, detener al finalizar canción, restaurar cola/orden/posición por cuenta sin iniciar audio automáticamente, favorito remoto |
| Sin conexión | Copias por servidor y cuenta de Inicio, playlists, álbumes, favoritos, guardados, mensajes y letras; aviso de copia local |
| Descargas | Cola serial, cancelación, errores por canción, reintento y protección contra resultados de cuentas anteriores |
| Subidas | Reintento de archivos fallidos y protección contra doble inicio |
| Notificaciones | Errores y reintentos visibles; borrado tras respuesta correcta del servidor |

## Diseño y rendimiento

- Las imágenes y el fondo del reproductor respetan el espacio asignado; dejan de imponer el tamaño de la imagen original.
- Reproductor limitado al área disponible, letras recortadas dentro del panel y distribución horizontal con controles desplazables.
- Miniaturas de 160 px en filas y 640 px en portadas; ImageIO reduce las imágenes antes de crear el bitmap completo; caché limitada a 64 MB.
- Mini reproductor colocado mediante área segura, sin separaciones fijas sobre las pestañas.
- Arranque con `playImmediately`, búfer de 5 segundos y pausa durante la espera. La latencia necesita medirse en el teléfono.
- Biblioteca evita cargas duplicadas; playlists guardadas se solicitan con hasta cuatro peticiones en paralelo, sin el recorte anterior a 40.
- Se descartan respuestas antiguas de búsqueda, letras, radio y sesión. Un error 401 antiguo no cierra una sesión nueva.
- La radio puede continuar si la respuesta llega después de terminar el audio; respeta la intención de pausa.

## Verificación

- Revisión de sintaxis con Tree-sitter de todos los archivos Swift, incluido el paquete de pruebas.
- Revisión del diff y contraste de rutas con controladores Java y acciones de la web.
- Seis pruebas XCTest de modelos y LRC preparadas. En Mac: `cd ios && swift test`. App iPhone también las ejecutará antes de compilar.
- No se ejecutaron Swift/Xcode ni XCTest desde Windows. Por indicación del dueño, la compilación se deja para el final.
- La revisión de sintaxis no comprueba tipos ni sustituye compilar y probar en el teléfono.

## Límites

Esta revisión amplía las funciones de la API; no certifica que toda la app esté libre de errores. No incorpora ecualizador nativo de cinco bandas, motor de fundido entre dos audios, CarPlay, widgets ni Siri. Los formatos que AVFoundation no decodifique necesitan una solución de audio específica; no se modifica el servidor para transcodificarlos.

## Comprobación final en iPhone

1. Portada, letra y cola en vertical y horizontal, con letra grande: títulos, tiempos, controles laterales y cierre.
2. Biblioteca y géneros: imágenes y textos dentro de sus cuadros, último elemento accesible sobre el mini reproductor.
3. Latencia con Wi-Fi y datos; pausa al cargar, pantalla apagada, siguiente canción, llamada y desconexión de audífonos.
4. Editar información, portada y letra como colaborador; verificar en web. El usuario normal no debe ver edición.
5. Guardar mix, añadir recomendaciones, ajustar banner y probar las nuevas pantallas de Administración.
6. Cargar datos y reabrir en modo avión; canciones descargadas y mensajes guardados. Cancelar/reintentar descargas y cambiar de cuenta.
