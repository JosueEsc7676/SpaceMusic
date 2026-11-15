# Corrección de arranque 3.6.2 — 14 de noviembre de 2026

- Se conserva la API y la autenticación. No hay cambios de esquema ni cambios nativos Android.
- Se aplazan y reintentan preparaciones solicitadas mientras hay transferencias de audio; máximo 8 pendientes y un trabajador.
- Los originales pendientes de preparación se revalidan (private, no-cache). Copias definitivas conservan caché larga.
- Streaming con revisión v=4. Rangos abiertos acotados a 256 KB para limitar el buffering; descargas completas intactas.
- Cancelación de precargas HTML e iOS incompletas al elegir pista, incluida la misma pista.
- Consulta a CacheStorage limitada a 150 ms cuando hay conexión; después se usa red. Sin red se conserva la lectura local sin límite. Se cancelan los flujos tardíos.
- Reproductor ampliado muestra el estado de carga y etiqueta accesible.
- Tiempos observables mediante atributos de playerBar, sin token, URL autenticada ni datos de cuenta.

## Validación
- AudioStreamingTest: 6 pruebas aprobadas; AudioPreparacionTest: 2 aprobadas.
- ExperienciasTest: 11 y ComunidadTest: 4 aprobadas durante este cambio, antes del ajuste exclusivo de audio/caché.
- Service worker: 9 pruebas aprobadas, incluyendo almacenamiento bloqueado y conservación del modo offline.
- Comprobación sintáctica de módulos JS modificados.
- Pista 2681: flujo comprimido de la copia preparada idéntico al original (SHA256 4ebeeb28ac891e8b17f4ff995167d5f45d84e92afff8c2f07c6be9ba5a5f6010). Original intacto.
- Antes del último ajuste de caché, medición en navegador integrado: evento playing 11222 ms, primer timeupdate 11269 ms y metadatos 11007 ms para Demasiadas Mujeres. Son eventos del navegador, no una medición acústica.

## Despliegue
- Servicio: spacemusic. Jar MD5: e0fc08c16c6784b31ef892ffd96fc178.
- Respaldo previo al conjunto de cambios: ~/spacemusic/spacemusic.jar.antes-audio-3.6.2-20261114 (3.6.1).
- Último respaldo intermedio: ~/spacemusic/spacemusic.jar.antes-audio-3.6.2-bloques-20261114.
- El script de despliegue confirmó arranque y API 200, con reversión automática ante fallo.
- Reversión manual: restaurar el jar 3.6.1 y reiniciar únicamente spacemusic. No se requiere restauración de base de datos.
- Android: cambios web compartidos; no se generó APK ni se probó teléfono físico.

- Antes de aplicar bloques, otra muestra registró 23884 ms hasta playing y 24113 ms al primer avance. No se considera una mejora. Se retiró el parámetro único por carga.
- La prueba de rangos abiertos reconstruye el archivo completo uniendo los bloques y compara todos sus bytes.
