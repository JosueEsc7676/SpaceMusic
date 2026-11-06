# SpaceMusic: notas para seguir en la MacBook

Léelas al empezar una conversación nueva con Claude Code en la Mac: "lee NOTAS-PARA-MAC.md y CAMBIOS.md".
Respuestas siempre en español; el usuario solo tiene iPhone.

## Qué es

Servidor de música propio (como Spotify), con:

- **Web** (también se instala en el iPhone desde Safari);
- **app Android** para teléfono (WebView que abre la web);
- **app Android TV**.

Versión actual: **web 2.7**, **app Android 1.5 (code 6)**, **TV 1.0**. El historial completo está en `CAMBIOS.md`.

## Cómo está armado

| Parte | Dónde | Tecnología |
|---|---|---|
| Servidor y API | `src/main/java/com/bpm/music` | Spring Boot 3.2.5, Java 21, JPA (`ddl-auto=update`), JWT |
| Web | `src/main/resources/static` | JavaScript sin frameworks (módulos ES), router por `#/ruta`, CSS en `css/noir.css` |
| Service worker | `src/main/resources/sw/sw.js` | lo sirve `ServiceWorkerController`; caché sin conexión y descargas |
| App Android | `android-movil/` | Java sin Gradle; se compila con `compilar.sh` |
| App TV | `android-tv/` | igual; interfaz de TV en `static/js/tv.js` |
| Descargar música al servidor | `scripts/descargar` y `scripts/Descargar musica.bat` | |

Detalles importantes:

- **Base de datos:** en el servidor, MySQL. En desarrollo, H2 (`--spring.profiles.active=dev`, cuenta `admin` / `prueba123`, puerto 8090).
- **Campos nuevos de usuario:** usar `Boolean`/`Integer` que acepten null (JPA solo agrega columnas).
- **CSP estricta:** `script-src 'self'`, así que no se puede usar JavaScript dentro del HTML.
- **Datos de cada usuario:** en el navegador se guardan con `deUsuario(clave)`, para que no se mezclen entre cuentas.
- **Conexión en vivo** (mensajes y notificaciones): `/api/eventos`, Server-Sent Events. En pestañas ocultas se cierra; en la app Android se mantiene.
- **Dominio público:** `https://spacemusic.tail8ccfdf.ts.net`, por Tailscale Funnel.
  - Usa HTTP/1.1, así que hay máximo 6 conexiones por navegador. No hay que abrir conexiones de más.
  - La llave de Tailscale no expira (ya se desactivó la expiración).
- **Seguridad:** el rol y el estado de la cuenta se leen de la base en cada petición (`JwtFilter`, caché de 20 s). `sesionesDesde` invalida sesiones viejas.

## Servidor (Ubuntu en la red de casa)

- **Dirección en la red local:** `192.168.10.165`. Servicio `systemd`: `spacemusic`. Puerto interno: `8081`.
- **Archivos:** el `.jar` está en `~/spacemusic/spacemusic.jar` del usuario del servidor. La copia anterior queda como `spacemusic.jar.anterior`.
- **Configuración con contraseñas:** vive solo en el servidor. Nunca subirla a git.
- **Publicar una versión nueva (desde la Mac, con tu llave SSH):**
  ```bash
  ./mvnw -q clean package -DskipTests   # o mvn
  scp target/spacemusic.jar USUARIO@192.168.10.165:~/spacemusic/spacemusic.jar.new
  ssh USUARIO@192.168.10.165 'cd ~/spacemusic && cp spacemusic.jar spacemusic.jar.anterior && mv spacemusic.jar.new spacemusic.jar && sudo systemctl restart spacemusic'
  ```
- **Aviso de actualización:** al arrancar, el servidor avisa a todos si cambió `version` en `static/app/novedades.json`. Por eso cada versión nueva lleva su texto ahí; el anterior pasa a `anteriores`.
- **Sudo temporal:** existe `/etc/sudoers.d/90-temporal-spacemusic`. Quitarlo es decisión del usuario.

## App Android

- Los cambios de la web le llegan solos, porque la app abre la página del servidor.
- Solo hay que sacar una APK nueva si cambia algo del teléfono (`android-movil/src`). Pasos:
  1. Subir `VERSION_CODE` y `VERSION_NAME` en `compilar.sh`, y lo mismo en `static/app/version.json`.
  2. Compilar y copiar la APK a `static/app/SpaceMusic.apk`.
- Para compilar en la Mac hace falta:
  - **Android SDK** (`platforms/android-34`, `build-tools/34.0.0`) y un JDK 21. Indicarlos con `ANDROID_SDK_ROOT` y `JAVA_HOME`.
  - **La llave de firma** `android-tv/firma/spacemusic.jks` y su `clave.txt`. No están en git y deben copiarse a mano desde la laptop Windows. Sin la misma llave, la actualización no se instala en los teléfonos.

## TV

- Mi TV en `192.168.10.120:5555` (ADB por red). Si cambia la IP, buscarla en el router o en la TV.

## Reglas del usuario

- Nunca escribir sus contraseñas. Las credenciales de Spotify las pone él con la opción "C" del `.bat`.
- Preguntar antes de descargar programas o aceptar licencias.
- Nada secreto en git. **El repositorio de GitHub es público.**
- Los cambios que necesitan `sudo` los hace el usuario si hace falta.

## Pendiente / por probar

- Probar en iPhone real:
  - que la siguiente canción suene con la pantalla apagada y datos móviles (precarga en memoria, `player.js` → `traerSiguiente`);
  - que Mensajes abra con datos móviles.
- La app Android no se ha probado en un teléfono (el usuario no tiene Android).
- App de TV: actualizarla otro día (sigue en 1.0).
- Idea posible: app nativa de iPhone (Xcode, en la Mac).
