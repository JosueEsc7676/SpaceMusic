# SpaceMusic para iPhone · Expo

App hecha con **React Native, Expo SDK 58 y TypeScript**. Sustituye la interfaz SwiftUI que se mantiene en `../ios/` como versión anterior.

## Cómo se comparte el diseño y las funciones

La interfaz visible es la **misma web móvil** del servidor, presentada en una WebView. Se comparten directamente Inicio, búsqueda y filtros, biblioteca, playlists, recomendaciones, perfiles, mensajes, cuenta, estadísticas, subida y administración. No se copian parcialmente sus formularios a controles estándar de iOS.

Es una arquitectura **híbrida**: React Native presenta la app; Expo Audio mantiene la cola en el sistema; SecureStore protege la sesión; FileSystem y Sharing guardan y exportan archivos. El servidor no se modifica ni necesita otra API.

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
