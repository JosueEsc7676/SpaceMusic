# SpaceMusic para iPhone · Expo

App hecha con **React Native, Expo SDK 58 y TypeScript**. Sustituye la interfaz SwiftUI que se mantiene en `../ios/` como versión anterior.

## Cómo se comparte el diseño y las funciones

La interfaz visible es la **misma web móvil** del servidor, presentada en una WebView. Se comparten directamente Inicio, búsqueda y filtros, biblioteca, playlists, recomendaciones, perfiles, mensajes, cuenta, estadísticas, subida y administración. No se copian parcialmente sus formularios a controles estándar de iOS.

Es una arquitectura **híbrida**: React Native presenta la app; Expo Audio mantiene la cola en el sistema; SecureStore protege la sesión; FileSystem y Sharing guardan y exportan archivos. El servidor no se modifica ni necesita otra API.

## Migración a pantallas nativas (estado real)

Se migra por bloques completos. El primer bloque nativo es el **reproductor**; el resto sigue siendo la web en la WebView.

| Bloque | Estado | Notas |
|---|---|---|
| Mini reproductor | implementado | React Native (`src/player/MiniPlayer.tsx`). Ocupa exactamente el hueco que la web reserva para su barra, así las listas terminan por encima de él y de la barra inferior. Sin conexión aparece abajo en la pantalla de descargas. |
| Reproductor completo | implementado | `FullPlayer.tsx`: hoja nativa de iOS (se cierra deslizando), portada con tamaño calculado según el espacio, título, artista, Me gusta, posición, controles y barra inferior. Nada se superpone: portada, letra y cola viven en un panel recortado y los controles tienen su propia zona. |
| Letras | implementado | `/api/letras/{id}` y búsqueda automática como en la web; LRC sincronizado con desplazamiento automático, tocar una línea busca esa posición, letra corta bajo el título. El desfase manual por canción de la web no se aplica todavía (limitado). |
| Cola | implementado | Seguir con canciones parecidas, sonando ahora, vaciar, quitar, tocar para saltar y mantener pulsado para mover. Las reglas de la cola siguen en la web (una sola cola). |
| Aleatorio, repetir, temporizador, radio, ir al artista/álbum, descargar | implementado | Órdenes validadas (`__smCmd`) que ejecuta el reproductor de la web. Sin conexión se desactivan. |
| Agregar a playlist, enviar a un amigo, editar canción | limitado | “Agregar a playlist, enviar y más…” abre el menú de la web. |
| AirPlay | limitado | Desde el Centro de control o la pantalla bloqueada; Expo Audio no ofrece selector de salida. |
| Inicio, Buscar, Géneros, Artistas, Álbumes, Biblioteca, Playlists, Perfil, Mensajes, Cuenta | pendiente | Siguen en la WebView. |
| Ecualizador, APNs | pendiente | Sin implementación nativa. |

Nada de lo anterior está **probado en un iPhone** todavía: las pruebas de Node cubren el contrato, el puente, las letras y el motor con un puerto simulado.

El puente oculta la barra y la pantalla “Reproduciendo ahora” de la web (`#playerBar`, `.np`, `.drawer`) solo dentro de la app, informa del hueco de la barra, Me gusta, aleatorio, repetir y radio (`ui`), y envía `openPlayer` cuando la web pediría abrir su reproductor. Si algo falla al conectar la presentación nativa, la web conserva su propio reproductor.

## Reproducción y archivos

- La web envía listas, acciones y preferencias al motor de audio nativo. Una sola fuente reproduce el audio.
- La cola nativa permite avanzar entre canciones con la pantalla apagada. Se muestran los datos y controles en la pantalla bloqueada.
- Pausa, saltos, posición, volumen, repetir, radio y temporizador se sincronizan con los controles de la web.
- El registro de escuchas se hace desde el motor nativo, evitando contar el mismo tiempo en ambos motores.
- Las descargas se guardan por cuenta y servidor; pueden abrirse incluso si el servidor no está accesible. Al cerrar sesión se borran los archivos locales de esa cuenta.
- En Cuenta aparece **Este iPhone → Servidor y descargas del dispositivo**. Desde allí se cambia el servidor, se abren descargas o se borran los archivos locales.
- Las notificaciones locales reflejan los avisos que recibe la interfaz en vivo. No se implementa APNs: el servidor actual no entrega notificaciones push a iOS con la app cerrada.

## Desarrollo y comprobación

```sh
cd ios-expo
npm ci
npm run verify
npm run export:ios
```

`verify` comprueba TypeScript y pruebas del contrato, el puente y el motor de audio con un puerto simulado. `export:ios` verifica el paquete de JavaScript/Hermes. **Ninguna de esas órdenes equivale a compilar iOS.**

En macOS:

```sh
npm run prebuild:ios
pod install --project-directory=ios
```

El flujo `.github/workflows/ios.yml` ejecuta estas comprobaciones, genera el proyecto de Expo, compila con Xcode 26.6 para iPhone sin firmar y empaqueta `SpaceMusic.ipa` dentro del artefacto **SpaceMusic-ipa**. Usa el mismo identificador `com.bpm.spacemusic.ios` de la app anterior.

## Instalar

En GitHub, abrir **Actions → App iPhone → ejecución verde → SpaceMusic-ipa**. Descomprimir el ZIP y abrir `SpaceMusic.ipa` en Sideloadly con el mismo Apple ID utilizado anteriormente. La interfaz nueva puede pedir iniciar sesión de nuevo.

## Límites y pruebas necesarias

- La interfaz completa necesita acceso al servidor; cuando no puede cargarse, se ofrece el reproductor de canciones descargadas. No se afirma que todas las páginas administrativas funcionen sin red.
- Se conserva el comportamiento y las restricciones de la web en iPhone, incluido el ecualizador Web Audio que actualmente la propia web desactiva en iOS. No se presenta ese ecualizador como nativo ni operativo.
- Usar Tailscale si el servidor solo es accesible a través de esa red.
- El puente depende del contrato actual de `/js/player.js`, `/js/util.js` y `/js/offline.js`; muestra un error si no puede conectarlos. Sus pruebas deben actualizarse junto con esos módulos.
- Antes de dar la app por validada: instalar en un iPhone y comprobar inicio de sesión, formularios, selección de fotos, mensajes, descargas, cambio de cuenta, tres canciones con pantalla apagada, auriculares, AirPlay y controles bloqueados.
- Una compilación verde no garantiza ausencia de fallos. No se han ejecutado pruebas visuales en un simulador iOS desde Windows.
