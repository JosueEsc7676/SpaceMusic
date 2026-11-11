# SpaceMusic iPhone: React Native, Expo y TypeScript

- Trabajar en español y en `app-ios`. La app activa es esta carpeta. `../ios/` conserva la versión SwiftUI anterior como referencia.
- La interfaz es la web móvil real del servidor dentro de `react-native-webview`, salvo el reproductor (mini, completo, letras y cola), que ya es nativo en `src/player/`. Se migra por bloques completos; mantener la tabla “Migración a pantallas nativas” del README con su estado real. No describir la app como una reescritura completa de vistas nativas.
- El reproductor nativo no tiene su propia cola: lee el estado del motor y pide a la web (`__smCmd`, lista cerrada en `protocol.ts` y `web/bridge.ts`) las reglas de cola, Me gusta y preferencias.
- Expo Audio gestiona una cola nativa y los controles bloqueados; el puente conecta el reproductor de la web con ese motor y mantiene una única reproducción.
- Toda orden de la web se valida en `src/protocol.ts`. Solo aceptar el servidor HTTPS elegido, sin credenciales en URL; no exponer la sesión a enlaces externos.
- Sesiones en SecureStore; descargas y cola separadas por servidor y usuario. Nunca imprimir tokens ni guardarlos en git.
- No cambiar el servidor para adaptar la app sin avisar. La interfaz compartida debe conservar todas sus funciones; cuando cambie el contrato del reproductor web, actualizar `web/bridge.ts` y sus pruebas.
- `npm ci`, `npm run verify` y `npm run export:ios` antes de entregar. El puente se genera automáticamente y no se edita en `src/generated/`.
- GitHub Actions `App iPhone` usa macOS, genera el proyecto nativo, compila Release y publica `SpaceMusic-ipa`. No confundir exportar JavaScript con compilar un IPA.
- El IPA se firma con Sideloadly, como antes. No requiere cuenta de Expo ni certificados de Apple guardados en el repositorio.
- No declarar una compilación correcta sin comprobar la ejecución correspondiente. Las pruebas de Node usan un puerto nativo simulado; no sustituyen la prueba de audio en el iPhone.
