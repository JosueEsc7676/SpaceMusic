# SpaceMusic

Servidor de música personal: escanea tu carpeta de música y la sirve con una
app web estilo Spotify (también instalable en el celular).

## Qué incluye

**Escuchar**
- Reproductor con cola, aleatorio, repetir, arrastrar para reordenar y "reproducir a continuación".
- Radio automática: al terminar una lista sigue con canciones parecidas.
- Pantalla completa con portada, color dinámico, visualizador y **letras sincronizadas** (clic en una línea para saltar, ajuste de desfase).
- Letras automáticas: si una canción no tiene, se buscan en [LRCLIB](https://lrclib.net) y se guardan.
- Ecualizador de 5 bandas con preajustes, temporizador para dormir, atajos de teclado (`?`), teclas multimedia y pantalla de bloqueo del celular.
- Recuerda lo que sonaba, la posición y el volumen al recargar.

**Explorar**
- Inicio personalizado: Mix del día, Descubrir, mixes por artista y género, escuchado recientemente, tendencias.
- Búsqueda instantánea que ignora acentos y tolera errores de escritura.
- Páginas de álbum (con número de pista), artista (populares, discografía, parecidos), género, historial.

**Organizar**
- Me gusta por usuario, playlists con foto, descripción, reordenar, duplicar, públicas o privadas, y recomendaciones para completarlas.
- Estadísticas personales: tiempo escuchado, top canciones/artistas/géneros, horario, racha de días.

**Administrar**
- Roles: usuario, colaborador (sube canciones, edita datos, portadas y letras) y administrador.
- Panel: usuarios, solicitudes de colaborador, estado de la biblioteca, escaneo manual, completar metadatos, limpiar canciones borradas.
- Subida de canciones desde el navegador (MP3, FLAC, M4A, OGG, OPUS, WAV).

## Configuración

Las contraseñas **no** van en el código. Copia el ejemplo y rellénalo:

```bash
cp config/application.properties.example config/application.properties
```

Spring Boot lee `config/application.properties` automáticamente si está en la
carpeta desde la que se ejecuta (junto al `.jar`, o la raíz del proyecto en el IDE).
También puedes usar variables de entorno: `DB_URL`, `DB_USER`, `DB_PASSWORD`,
`JWT_SECRET`, `MUSIC_PATH`, `UPLOADS_PATH`, `APP_BASE_URL`, `MAIL_USER`, `MAIL_PASSWORD`, `SETUP_KEY`.

| Propiedad | Para qué |
|---|---|
| `music.path` | Carpeta con tu música (se escanea cada 10 min, `music.scan-interval-minutes`) |
| `music.playlist-images-path` | Fotos de playlists, avatares y miniaturas |
| `jwt.secret` | Clave de las sesiones (texto largo aleatorio: `openssl rand -base64 48`) |
| `app.base-url` | URL pública, para los enlaces de los correos |
| `spring.mail.*` | Gmail con contraseña de aplicación (verificación y recuperación) |
| `app.setup-key` | Clave para crear el primer administrador en `/setup.html` |
| `app.allow-downloads` | `false` para que solo los admins descarguen canciones |

## Probar en tu PC sin MySQL

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Usa una base H2 en `./data` y la música de `./data/music`. En IntelliJ: *Run → Edit
Configurations → Active profiles: `dev`*. Abre <http://localhost:8080/setup.html>
(clave `dev-setup`) para crear el admin.

## Subir al servidor (Ubuntu)

1. **Java 21** en el servidor: `sudo apt install openjdk-21-jre-headless`
2. **Compilar** en tu PC: `mvn clean package -DskipTests` → `target/spacemusic.jar`
3. **Copiar** al servidor:
   ```bash
   ssh usuario@servidor 'sudo mkdir -p /opt/spacemusic/config && sudo chown $USER /opt/spacemusic -R'
   scp target/spacemusic.jar usuario@servidor:/opt/spacemusic/
   scp config/application.properties usuario@servidor:/opt/spacemusic/config/
   ```
   En el servidor, edita `/opt/spacemusic/config/application.properties` y pon las
   rutas de Linux (por ejemplo `music.path=/home/joss200509/musica/music`).
4. **Servicio** (arranca solo y se reinicia si falla):
   ```bash
   sudo cp deploy/spacemusic.service /etc/systemd/system/   # revisa User= dentro
   sudo systemctl daemon-reload
   sudo systemctl enable --now spacemusic
   journalctl -u spacemusic -f
   ```
5. Entra a `http://servidor:8080`. Si es la primera vez, abre `/setup.html`.
6. Opcional: `deploy/nginx-spacemusic.conf` para usar un dominio con HTTPS.

Para actualizar después: `./deploy/actualizar.sh usuario@servidor`.

### Si cambias la música de carpeta

Si la base de datos se llenó con rutas de Windows (`P:\...`) y el servidor usa
`/home/...`, no se duplican las canciones: el escáner reconoce los archivos por su
ruta relativa y actualiza la ruta guardada. Conserva favoritos, playlists y estadísticas.

## Base de datos

`spring.jpa.hibernate.ddl-auto=update` agrega las columnas nuevas sola (año, pista,
formato, playlists públicas…). Los datos de la versión anterior se conservan. Al
arrancar se hacen dos arreglos automáticos: las playlists sin dueño pasan al primer
administrador, y los favoritos globales antiguos pasan a ser sus favoritos.

**Haz una copia de la base antes del primer arranque:**
`mysqldump -u usuario -p musicdb > musicdb-respaldo.sql`

## Estructura

```
src/main/java/com/bpm/music
  config/        seguridad, errores, migración de datos
  controllers/   API REST (/api/...)
  services/      biblioteca en memoria, escáner, recomendaciones, letras, imágenes, correo
  security/      JWT, límite de intentos de login
src/main/resources/static
  index.html     app (módulos JS en js/, estilos en css/app.css)
  login.html, register.html, ...  páginas de acceso
deploy/          servicio systemd, nginx, script de actualización
mobile-app/      app Capacitor que abre el sitio
```
