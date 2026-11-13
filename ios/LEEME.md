# SpaceMusic para iPhone (SwiftUI)

Esta es la app activa de iPhone (SwiftUI). El flujo **App iPhone** de GitHub la compila en cada cambio de `ios/` y deja **SpaceMusic-ipa**. El intento con React Native y Expo de `../ios-expo/` está archivado: su flujo manual se llama **App iPhone Expo (archivada)** y su artefacto **SpaceMusic-expo-ipa-archivada**. Estado de paridad con la web: `PARIDAD.md`.

App nativa (SwiftUI) que usa la misma API del servidor que la web. No abre la web dentro: todo está hecho con pantallas del iPhone.

## Correcciones en revisión

- Desde el menú de canción, colaboradores y administradores pueden editar información, portada y letra.
- Se limitan imágenes y fondo del reproductor a su espacio y se solicitan miniaturas para reducir transferencia.
- El arranque del audio usa menor búfer; la mejora de tiempo debe comprobarse en red real.
- Seguimiento de funciones y pruebas en REVISION-IOS.md.

- El mini reproductor reserva su espacio sobre las pestañas automáticamente.
- Cargar mensajes anteriores conserva la posición; los envíos se bloquean mientras hay otro pendiente y respetan lo escrito durante la espera.
- Cerrar sesión limpia la biblioteca y las pantallas de la cuenta anterior.

- Las portadas mantienen su tamaño cuadrado y se ajustan a la altura disponible del reproductor.
- Buscar o cambiar de canción rápidamente ya no debe mostrar respuestas antiguas sobre la selección nueva.
- La radio descarta respuestas de una cola anterior y el control de tiempo se reinicia al cambiar de canción.
- Pendiente: compilar con el flujo **App iPhone** y comprobar estos cambios en el iPhone real.

## Ampliación de funciones

- Administración: soporte, errores, detalle de usuarios, géneros y mantenimiento.
- Letras: importar LRC/TXT, buscar y borrar con confirmación. Perfil: biografía y ajuste del banner.
- Playlists: recomendaciones. Mixes: renovar y guardar. Listas: filtrar y agregar en grupo.
- Reproductor: temporizador y recuperación de cola/posición al abrir, sin iniciar audio automáticamente.
- Copias por cuenta para abrir sin conexión. Descargas: cancelar, ver errores y reintentar.
- La compilación final ejecutará pruebas de modelos y letras. Consulta REVISION-IOS.md para cobertura, límites y pruebas en el teléfono.

## Qué incluye

- **Entrar, crear cuenta y recuperar contraseña.** Se puede cambiar la dirección del servidor (por defecto `https://spacemusic.tail8ccfdf.ts.net`).
- **Reproductor:**
  - sigue sonando con la pantalla apagada y tiene controles en la pantalla bloqueada y el Centro de control;
  - la siguiente canción ya está cargada, así el cambio es instantáneo;
  - aleatorio, repetir (todo / una) y cola que se puede ordenar;
  - al terminar la lista sigue con canciones parecidas (radio del servidor);
  - AirPlay y Bluetooth;
  - pausa al desconectar los audífonos;
  - registra las escuchas igual que la web (a los 30 s o a la mitad de la canción) y avisa a los amigos qué estás escuchando.
- **Letra:** sincronizada en pantalla completa (toca una línea para saltar ahí) y la línea actual en miniatura debajo del artista. También busca la letra en internet.
- **Inicio:**
  - accesos rápidos a lo último que sonó;
  - lo que escuchan tus amigos;
  - mixes hechos para ti;
  - lo más escuchado, tendencias y recién agregadas;
  - álbumes, artistas, playlists de la comunidad y géneros.
- **Buscar:** canciones, artistas, álbumes, playlists, géneros y personas; también por ánimo ("música relajante", "para el gym"); guarda tus búsquedas recientes.
- **Biblioteca:**
  - favoritos, descargas e historial;
  - tus playlists y las que guardaste;
  - álbumes guardados.
- **Playlists:**
  - crear, editar el nombre y si es pública;
  - cambiar o quitar la portada;
  - ordenar y quitar canciones;
  - duplicar, borrar y guardar las de otras personas.
- **Álbum, artista, género y mix**, con reproducir, aleatorio y radio.
- **Descargas sin conexión:** canciones, álbumes y playlists enteras. Cada cuenta tiene sus propias descargas y se borran al cerrar sesión.
- **Perfiles:**
  - foto, portada y nombre;
  - seguidores y seguidos, y botón para seguir;
  - compatibilidad musical;
  - vibras, top de canciones, artistas y géneros;
  - lo que escucha ahora.
- **Mensajes en tiempo real** con amigos: compartir la canción que suena, "Visto" y mensajes anteriores. Si llega un mensaje mientras suena música en segundo plano, aparece una notificación del iPhone.
- **Notificaciones** (la campana): abrir, borrar una o vaciar todas.
- **Tus estadísticas:** escuchas, tiempo, racha, gráfica por hora, top de canciones, artistas y géneros.
- **Cuenta:** privacidad del perfil, cambiar contraseña o correo y pedir ser colaborador.
- **Subir canciones** (colaboradores y administradores) desde la app Archivos, de dos en dos.
- **Administración:**
  - aprobar o rechazar cuentas y colaboradores;
  - cambiar roles, activar o desactivar cuentas;
  - enviar el correo de contraseña y cerrar las sesiones de alguien;
  - buscar canciones nuevas o géneros que faltan;
  - enviar avisos a todos.

## Instalar la última versión sin Mac (recomendado)

GitHub compila la app solo cada vez que se suben cambios en `ios/` (flujo **App iPhone** en `.github/workflows/ios.yml`).

1. En GitHub: repositorio **SpaceMusic** → pestaña **Actions** → **App iPhone** → la ejecución más reciente con ✓ verde.
2. En **Artifacts**, descarga **SpaceMusic-ipa** y descomprímelo.
3. Instala `SpaceMusic.ipa` con **Sideloadly** (Windows o Mac), con el iPhone conectado por cable.

## Cómo instalarla en tu iPhone con Xcode

1. **Instala Xcode** desde la App Store de la Mac. Esta Mac tiene macOS 14.7, así que la versión más nueva que funciona es **Xcode 16.2**. Si la App Store ofrece una que pide macOS 15, descarga la 16.2 desde developer.apple.com/download/all.
2. Abre `ios/SpaceMusic.xcodeproj`.
3. En Xcode, elige el proyecto **SpaceMusic** → pestaña **Signing & Capabilities** → **Team** → inicia sesión con tu Apple ID.
   - Si Xcode dice que el identificador `com.bpm.spacemusic.ios` ya está en uso, cámbialo por otro, por ejemplo `com.tunombre.spacemusic`.
4. Conecta el iPhone con cable, desbloquéalo y toca "Confiar".
   - En el iPhone: **Ajustes → Privacidad y seguridad → Modo de desarrollador → activar** (se reinicia).
5. Arriba en Xcode elige tu iPhone y pulsa ▶︎ (Run).
6. La primera vez, en el iPhone: **Ajustes → General → VPN y gestión de dispositivos →** tu Apple ID → **Confiar**.

**Ojo con esta MacBook Air (2015–2017, macOS 14 con parche):** el Xcode más nuevo que corre es el 16.2, y ese Xcode **no logra instalar apps en iOS 27**: se corta al conectarse ("Connection was invalidated"). En esta Mac la app compila y se firma, pero para instalarla hace falta otro camino: Sideloadly con el archivo `SpaceMusic.ipa`, o una Mac con Xcode 26.

Con un Apple ID gratis, la app deja de abrir a los **7 días** y hay que volver a instalarla desde Xcode (paso 5). Con la cuenta de desarrollador de Apple (99 USD al año) dura un año y se puede repartir por TestFlight.

## Notas técnicas

- Mínimo iOS 17. Sin librerías externas.
- El ícono está como imágenes sueltas en `Recursos/Icono` (no en un catálogo `.xcassets`). Xcode 16 no puede procesar catálogos de imágenes si no tiene instalado el simulador de iPhone (8 GB), y en esta Mac no cabe.
- Compilar desde la terminal sin firmar (para comprobar que no hay errores):
  ```bash
  cd ios
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project SpaceMusic.xcodeproj \
    -scheme SpaceMusic -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
  ```
- El proyecto usa carpetas sincronizadas (Xcode 16): cualquier archivo nuevo dentro de `ios/SpaceMusic/` entra solo en la app.
- El token se guarda en el llavero del iPhone. El audio se pide con `?t=token`, igual que la web.
- Formatos: MP3, M4A/AAC, FLAC y WAV suenan en el iPhone. **OGG/Opus no los reproduce el iPhone**; la app salta esas canciones y muestra un aviso.
- Revisar el código sin Xcode (usa el SDK de Mac Catalyst que traen las herramientas de línea de comandos, tarda unos minutos):
  ```bash
  cd ios/SpaceMusic
  SDK=/Library/Developer/CommandLineTools/SDKs/MacOSX15.2.sdk
  swiftc -typecheck -swift-version 5 -target x86_64-apple-ios17.0-macabi -sdk $SDK \
    -Fsystem $SDK/System/iOSSupport/System/Library/Frameworks $(find . -name "*.swift")
  ```

## Actualizar desde el iPhone

Cada compilación correcta de la rama `app-ios` publica `SpaceMusic.ipa` en la versión **ios-latest** del repositorio (Releases). La app lo comprueba al abrirse y en segundo plano, y avisa con una notificación.

- **Con SideStore o AltStore** instalados en el iPhone: toca el aviso → «Instalar con SideStore/AltStore». Se instala encima, sin perder datos.
- **Sin ellos**: «Descargar el .ipa» y ábrelo en Sideloadly desde la computadora como siempre.

El iPhone no deja que una app se instale a sí misma: el .ipa hay que firmarlo con tu Apple ID, y eso lo hacen SideStore, AltStore o Sideloadly.


## Actualización de API 3.5 (13/11/2026)

Tú → Tu música incluye Escuchar juntos y Despertador. Las dedicatorias permiten programar fecha y hora y cancelar el envío pendiente. Reproducción ofrece calidad Automática/Alta/Ligera; Automática elige ligera en datos móviles. El resumen muestra la comparación con el período anterior y tu descubrimiento.

El menú del reproductor permite ampliar la letra o compartir versos como imagen. El temporizador baja el volumen durante los últimos 30 segundos. Con la app cerrada el despertador es una notificación que se toca para iniciar música; iOS no garantiza ejecutar el reproductor a una hora exacta con la app suspendida.

Actualizaciones: toca Descargar actualización y espera el progreso; al terminar usa Guardar en Archivos o compartir IPA. El archivo se comprueba antes de ofrecerlo. También puedes abrir `/app/ios.html` en el servidor para descargarlo. Instalar sigue necesitando SideStore, AltStore o Sideloadly y tu Apple ID.


## SyncPlay, dispositivos y mensajes

- El icono de salida de audio del mini reproductor está antes del corazón. Abre Dispositivos: la salida actual, el selector nativo de iPhone/Bluetooth/AirPlay y SyncPlay. También está en el reproductor completo y la letra ampliada.
- SyncPlay se abre desde Dispositivos o Mensajes, no desde los ajustes de cuenta. Permite crear una sala, compartir su enlace o invitar por chat a un amigo.
- En cada chat, el icono de audífonos ofrece Iniciar SyncPlay. Se envía una tarjeta con el enlace; el destinatario la toca para abrir la sala y unirse con su cuenta.
- En Mensajes hay accesos a SyncPlay, Coincidimos y Dedicatorias. El botón + del chat reúne enviar una canción, dedicar e iniciar Coincidimos.
- En Descargar música, las claves de Spotify están antes del registro, y este se despliega en un panel de alto limitado con su propio desplazamiento.
- Los menús de canciones agrupan Cola y radio, Compartir y Más acciones. La letra ampliada tiene fondo de portada, información de la canción y controles propios.
