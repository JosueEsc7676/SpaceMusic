# Revisión de iOS frente a web y Android

La referencia funcional es `src/main/resources/static/js/`: Android abre esa misma web. Que una pantalla exista en iOS no implica que todas sus acciones estén completas ni probadas.

## Corregido en código, pendiente de compilar y probar en iPhone

- Imágenes limitadas al espacio que les asigna la vista: filas, mosaicos, géneros, perfiles y fondos.
- Reproductor limitado al ancho y alto disponibles; fondo fuera del cálculo de tamaño; panel de letras recortado dentro de su espacio.
- Miniaturas de 160 px en filas y 640 px en portadas, usando el parámetro `s` que ya admite el servidor.
- Arranque de audio con `playImmediately`, búfer solicitado de 5 segundos; pausa disponible también mientras espera audio. La reducción de latencia debe medirse con red real.
- Edición de información (incluye año y pista), portada y letra pegada/LRC para colaboradores y administradores. Usa las rutas existentes y actualiza la canción en la cola.
- Estado de carga de letras reiniciado al cambiar a una canción sin letra; respuestas antiguas de búsqueda de letras descartadas.

## Pendiente para paridad completa

- Editor: importar archivo LRC/TXT, borrar letra con confirmación y buscar letra desde el editor; refrescar datos editados en las listas que ya estaban abiertas.
- Perfil: ajustar la posición del banner.
- Administración: soporte y errores del servidor, detalle de usuario, gestión manual de géneros; comparar todas las acciones con la web.
- Reproductor: temporizador, restaurar cola y posición, fundido y preferencias de audio donde iOS lo permita.
- Sin conexión: guardar Inicio/Biblioteca/mensajes, errores visibles y reintentos de descarga; revisar cancelaciones y cambios de cuenta durante descargas.
- Revisar estados vacíos, errores y reintentos en cada pantalla; no convertir errores de red en listas vacías.

## Comprobaciones en el teléfono

1. Abrir portada, letra y cola; comprobar título, artista, tiempos, controles laterales y botón de cerrar con letra grande y orientación horizontal.
2. Buscar géneros y abrir Biblioteca: imágenes dentro de sus cuadros, nombres visibles y último elemento accesible sobre el mini reproductor.
3. Medir tiempo desde tocar canción hasta oír audio con Wi-Fi y datos; comparar primera reproducción y siguiente precargada. Confirmar pausa durante carga.
4. Apagar pantalla durante tres canciones, usar controles bloqueados, desconectar audífonos y atender una llamada.
5. Con rol autorizado, editar información, portada y letra; verificar resultado también en web. Con usuario normal, comprobar que el menú de edición no aparece.
6. Cambiar rápido entre canciones con y sin letras; verificar que no quede el indicador de carga ni se aplique una letra anterior.

No se ha comprobado visualmente esta revisión en simulador ni en iPhone desde Windows. Una compilación verde verifica el código, no sustituye estas pruebas.
