# Inicio de audio: 3.6.1

## Estado verificado antes del cambio

- Producción y repositorio: versión 3.6, commit 2b88cd4. El jar local coincidía con el MD5 del servidor.
- El servicio ya usaba rangos HTTP, ETag, una cola de versiones AAC ligeras y precarga.
- En ocho M4A reales inspeccionados, el primer paquete de sonido empezaba entre aproximadamente 79 y 159 KB después del principio del archivo. Las imágenes y etiquetas también viajaban dentro del audio.
- El service worker convertía la canción completa a Blob antes de responder a cualquier rango, incluso a una petición de dos bytes.
- «Iniciar radio» esperaba la respuesta de recomendaciones antes de asignar la pista.

## Cambios

- Se reutiliza AudioLigeroService para preparar una copia MP3/M4A/MP4 sin carátulas adjuntas y metadatos incrustados. `-c:a copy` conserva el flujo de audio; `+faststart` coloca los índices MP4 delante.
- La petición no espera a ffmpeg. Si no hay copia preparada, devuelve el original. La descarga conserva el original. No hay cambios de esquema, claves o permisos.
- Cola máxima de ocho, una tarea a la vez, prioridad nice 19, AAC con un hilo. Cede ante audio activo o CPU mayor de 70 %. Trabajos incrementales de cuatro canciones por minuto y solicitudes bajo demanda. Archivos temporales y publicación atómica; mínimo de espacio libre.
- Rangos del audio guardado mediante ReadableStream, incluyendo sufijos y cancelación. Las copias antiguas sin tamaño mantienen la ruta compatible. Un archivo truncado produce un error, no un audio silenciosamente incompleto.
- La precarga responde exactamente a bytes=0-1 y respeta If-Range.
- La prioridad se anuncia antes de asignar src. Iniciar radio reproduce inmediatamente y la respuesta de una radio antigua no modifica otra selección.

## Medición reproducible

Archivo M4A real copiado temporalmente desde el servidor, sin alterar su original ni incorporarlo al repositorio. Se comprobó con ffmpeg que el SHA-256 del flujo de audio comprimido es idéntico antes y después:

`7ae337103b9abd1fa10179a8d29064069fe81f4a732f3d988ac235fb8b6b6c39`

El comienzo de los datos de sonido pasó de 86 545 a 28 585 bytes. El tamaño de archivo pasó de 2 663 692 a 2 605 732 bytes.

`pruebas/audio-comparar.mjs`: Edge sin ventana, HTTP en localhost, ancho de banda 1,6 Mbps, latencia 120 ms, caché HTTP deshabilitada y cinco muestras por caso. Se mide hasta el evento playing. Resultados brutos en `resultados/audio-3.6.1.json`:

| Caso | Antes p50 / p95 | Después p50 / p95 |
|---|---:|---:|
| Sin audio guardado | 5598 / 6245 ms | 422 / 711 ms |
| Copia guardada, cero solicitudes de audio a red | 67 / 74 ms | 74 / 121 ms |

La mejora importante de esta muestra es en frío. No se afirma una mejora medible del caso ya guardado, donde ambos son rápidos. La prueba de flujo demuestra que ahora no se necesita cargar la canción entera para entregar sus primeros bytes.

Son medidas de una canción y red simulada, no una prueba en un teléfono físico ni de todas las canciones a través del acceso público. Las copias se preparan gradualmente; una pista que todavía no tenga copia seguirá usando el original.

## Validación

- `node --test pruebas/audio-cache.test.mjs`: siete pruebas, todas correctas (flujo inmediato, rangos, sufijos, 416, truncamiento, compatibilidad y precarga exacta).
- Maven: AudioStreamingTest (cinco), ComunidadTest (cuatro) y ExperienciasTest (once), todas correctas. Verifican autenticación, rangos, ETag, respuesta 304, fallback, calidad, original intacto y regresiones de playlists/experiencias.
- Suite del navegador: once pruebas correctas. Incluye radio sin espera, descarte de recomendaciones antiguas, reproducción en segundo plano simulado, crossfade, descargas offline y recuperación de mantenimiento.
- La prueba de API incluye continuar el original con If-Range cuando ya hay una copia rápida preparada, evitando combinar sus bytes.

## Referencias de la preparación

- [FFmpeg: copia de flujos sin recodificación](https://ffmpeg.org/ffmpeg.html#Streamcopy).
- [FFmpeg: formato MP4 y faststart](https://ffmpeg.org/ffmpeg-formats.html#mov_002c-mp4_002c-ismv).

## Reversión

El despliegue conserva el jar anterior y solo reinicia spacemusic. Para volver, detener el servicio, sustituir el jar por su copia de antes de 3.6.1 y volver a iniciarlo. Las copias de reproducción en ligero/inicio son derivadas; los originales y la base siguen sirviendo con el jar anterior. No hay migraciones de base de datos.
