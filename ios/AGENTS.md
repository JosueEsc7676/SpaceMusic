# SpaceMusic para iPhone: contexto para agentes (Codex, Claude, etc.)

Responde siempre **en español** y con palabras sencillas: el dueño no es programador de iOS y solo tiene iPhone.

## Qué es

SpaceMusic es un servidor de música propio, parecido a Spotify (Spring Boot, en la raíz del repositorio). Esta carpeta `ios/` contiene la **app nativa de iPhone**:

- SwiftUI, iOS 17 o más nuevo, sin librerías externas;
- usa la **misma API** que la web (`src/main/resources/static/js/`). Si dudas cómo debe funcionar algo, mira cómo lo hace la web y repítelo;
- **no hay que cambiar el servidor** para la app. Si un cambio lo necesita, avisa antes: el servidor está en producción y se publica a mano (ver `NOTAS-PARA-MAC.md`).

Servidor: `https://spacemusic.tail8ccfdf.ts.net` (por defecto en la app; se puede cambiar en la pantalla de entrada).

## Estructura

```
ios/
  SpaceMusic.xcodeproj      proyecto Xcode 16 con carpeta sincronizada: todo archivo dentro de SpaceMusic/ entra solo en la app
  SpaceMusic/
    Info.plist              audio en segundo plano, red local, ícono (CFBundleIcons)
    App/                    SpaceMusicApp (@main, delegado de notificaciones), Navegacion (pestañas y Destino), Tema (colores y estilos)
    Red/                    API (peticiones, errores, subidas), Sesion (token en el llavero), Eventos (Server-Sent Events en vivo), Imagenes (caché de portadas)
    Modelos/                Modelos (structs que leen el JSON de forma tolerante), Biblioteca (favoritos, playlists, guardados)
    Reproductor/            Reproductor (AVQueuePlayer, cola, radio, pantalla bloqueada), Letras (LRC), Descargas (sin conexión)
    Vistas/                 una pantalla o grupo de pantallas por archivo
    Recursos/Icono/         ícono como PNG sueltos (NO usar .xcassets, ver abajo)
  LEEME.md                  funciones e instalación (para el dueño)
```

## Reglas del código

- **Nombres en español**, como en el resto del proyecto (`cargar`, `cancion`, `Reproductor`…). Comentarios cortos en español.
- Estado compartido: clases `@MainActor @Observable` con un `static let compartido/compartida` (`Reproductor`, `Biblioteca`, `Eventos`, `Descargas`, `Navegacion`, `Avisos`, `Hojas`). En las vistas se usan con `@State private var x = Clase.compartido`.
- **Modelos tolerantes:** cada struct tiene un `init(from:)` que usa `decoder.campos()` y los ayudantes `texto/entero/numero/bool/lista/v`. Si falta un campo se pone un valor por defecto en vez de fallar. Sigue ese patrón para modelos nuevos.
- Peticiones: `API.get`, `API.enviar(metodo, ruta, cuerpo)`, `API.hacer` (sin respuesta) y `API.subir` (multipart). Para trozos de ruta usa `API.segmento(...)`; para parámetros de URL, `API.codificar(...)`.
- Navegación: agrega un caso a `Destino` y su vista en `VistaDestino`. Se usa con `NavigationLink(value: Destino.x)` o `Navegacion.compartida.ir(.x)`. `Navegacion.abrir(ruta:)` traduce rutas de la web (`#/u/ana`, `#/playlist/3`…) que llegan en las notificaciones.
- Mensajes breves al usuario: `Avisos.compartido.mostrar("…")` o `.error(error)`.
- Menú de canción: `OpcionesCancion` (es el mismo en toda la app). Las hojas "Agregar a playlist" y "Enviar a un amigo" se abren con `Hojas.compartidas`.
- Las listas largas llevan `.espacioReproductor()` para que el mini reproductor no tape el final.
- Swift 5 (`SWIFT_VERSION = 5.0`); hay avisos de concurrencia de Swift 6 que se pueden ir limpiando.
- **Nada secreto en git:** el repositorio de GitHub es público. Sin contraseñas, tokens ni llaves.

## Compilar y comprobar

La Mac del dueño es una **MacBook Air 2015–2017 con macOS 14 parcheado**: solo corre **Xcode 16.2** (SDK iOS 18.2) y tiene poco disco.

```bash
cd ios
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project SpaceMusic.xcodeproj \
  -scheme SpaceMusic -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

- Después de cada cambio, **compila y deja 0 errores** antes de terminar.
- **No hay simulador instalado** (no cabe en el disco). Por eso el ícono no está en un `.xcassets`: Xcode 16 exige el simulador para procesar catálogos de imágenes. No vuelvas a agregar `Assets.xcassets`.
- **Instalar en el iPhone:** el iPhone tiene iOS 27 y Xcode 16.2 no puede instalarle apps. Se instala con **Sideloadly** usando un `.ipa`:
  ```bash
  cd ios
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project SpaceMusic.xcodeproj -scheme SpaceMusic \
    -configuration Release -destination 'generic/platform=iOS' -derivedDataPath /tmp/sm-dd CODE_SIGNING_ALLOWED=NO build
  rm -rf /tmp/sm-ipa && mkdir -p /tmp/sm-ipa/Payload && cp -R /tmp/sm-dd/Build/Products/Release-iphoneos/SpaceMusic.app /tmp/sm-ipa/Payload/
  (cd /tmp/sm-ipa && zip -qry ~/Desktop/SpaceMusic.ipa Payload)
  ```
  Luego el dueño arrastra `SpaceMusic.ipa` a Sideloadly. Con Apple ID gratis dura 7 días.
- Pide permiso antes de instalar programas, aceptar licencias o borrar archivos del dueño. Lo que necesite `sudo` lo hace él.

## API que usa la app (resumen)

Todas con `Authorization: Bearer <token>`, salvo el audio (`?t=<token>`).

| Qué | Ruta |
|---|---|
| Entrar / registro / olvidé | `POST /api/auth/login {usuario,password}`, `/register`, `/forgot-password`; `POST /api/auth/refresh` |
| Yo | `GET/PUT /api/auth/me`, `POST /api/auth/me/foto` (multipart `file`) |
| Inicio, buscar | `GET /api/inicio`, `GET /api/buscar?q=` (incluye `estilo` para géneros y ánimos) |
| Música | `/api/albumes`, `/api/albumes/detalle?nombre=`, `/api/artistas[/{nombre}]`, `/api/generos[/{nombre}]`, `/api/mixes/{id}?cantidad=`, `/api/radio/{id}?cantidad=&excluir=&anteriores=`, `/api/shuffle/artista/{a}` |
| Audio | `GET /api/canciones/{id}/audio?t=`, `/descargar?t=` |
| Escuchas | `POST /api/reproducciones {cancionId,contexto,segundos}` (a los 30 s o a la mitad), `POST /api/actividad {cancionId,sonando}` |
| Letras | `GET /api/letras/{id}`, `POST /api/letras/{id}/buscar[?forzar=true]` |
| Favoritos y guardados | `GET /api/usuario/favoritos[/ids]`, `POST/DELETE /api/usuario/favoritos/{id}`, `GET /api/usuario/guardados`, `POST/DELETE /api/usuario/guardados/{album\|playlist}?clave=` |
| Playlists | `GET/POST /api/playlists`, `GET/PUT/DELETE /api/playlists/{id}`, `/duplicar`, `POST /{id}/canciones {ids}`, `DELETE /{id}/canciones/{cid}`, `PUT /{id}/orden {ids}`, `POST/DELETE /{id}/imagen` |
| Personas | `GET /api/perfiles/{u}`, `POST/DELETE /api/perfiles/{u}/seguir`, `/seguidores`, `/siguiendo`, `GET /api/amigos/actividad`, `PUT /api/perfiles/yo/privacidad`, `POST/DELETE /api/perfiles/yo/banner` |
| Mensajes | `GET /api/mensajes`, `/api/mensajes/amigos`, `GET/POST /api/mensajes/{u}` (`?antes=id`), `POST /api/mensajes/{u}/leidos` |
| En vivo | `GET /api/eventos` (SSE: `mensaje`, `visto`, `notificacion`) |
| Notificaciones | `GET/DELETE /api/notificaciones[/{id}]`, `POST /api/notificaciones/leidas`, `GET /api/notificaciones/resumen` |
| Cuenta | `/api/usuario/estadisticas?dias=`, `/cambiar-password`, `/cambiar-email`, `/solicitar-colaborador`, `/recientes?limite=` |
| Colaborador y admin | `POST /api/canciones/subir` (multipart `files`), `/api/admin/...` (resumen, cuentas, solicitudes, usuarios, biblioteca, aviso) |
| Novedades | `GET /app/novedades.json` |

Las respuestas de error traen `{"error": "mensaje en español"}`; `API` ya lo muestra.

## Estado actual

- Compila sin errores con Xcode 16.2. **Todavía no se ha probado a fondo en el iPhone real.** Lo primero es probar y corregir:
  - que siga sonando con la pantalla apagada y pase sola a la siguiente canción;
  - los controles en la pantalla bloqueada;
  - los mensajes en vivo;
  - las descargas sin conexión;
  - subir fotos.
- El iPhone no reproduce **OGG/Opus**: el reproductor salta esas canciones con un aviso.

## Ideas para mejorar (por prioridad)

1. **Arreglar lo que falle en el iPhone real**: es lo más importante.
2. **Sin conexión de verdad:** guardar copia de Inicio, Biblioteca y la última lista de mensajes (la web lo hace) y mostrarla al abrir sin señal. Si no hay red, abrir directo en Descargas.
3. **Funciones de la web que faltan:**
   - editar los datos de una canción (`PUT /api/canciones/{id}/editar`) y cambiar su portada (`POST /api/canciones/{id}/portada`);
   - editar o subir la letra (`PUT /api/letras/{id}`, `POST /api/letras/subir/{id}`);
   - "Ajustar" la posición de la portada del perfil (`PUT /api/perfiles/yo/banner/posicion`);
   - en Administración: Soporte (`/api/admin/sistema`, `/api/admin/errores`), la ficha de cada usuario (`/api/admin/usuarios/{id}/detalle`) y poner el género a mano (`PUT /api/admin/generos/artista`).
4. **Reproductor:** temporizador para dormir, avance entre canciones con fundido, guardar y restaurar la cola al reabrir la app, y botón de favorito en la pantalla bloqueada (`MPRemoteCommandCenter.likeCommand`).
5. **Diseño:** pulir según la web (`static/css/noir.css`), tamaños de iPad, animaciones al abrir el reproductor y colores sacados de la portada.
6. **Extras de iPhone:** widget de "Reproduciendo", acceso rápido desde el ícono (Quick Actions), atajos de Siri y CarPlay (CarPlay pide un permiso especial de Apple).
7. **Calidad:**
   - limpiar los avisos de concurrencia de Swift 6;
   - pruebas del lector de LRC y de los modelos con JSON reales del servidor;
   - partir las vistas grandes (`PerfilVistas`, `ColeccionVistas`) que tardan en compilar.

Al terminar un cambio:

- actualiza `ios/LEEME.md` si cambian funciones o pasos;
- agrega una línea en `CAMBIOS.md` (sección "App de iPhone"). Ese archivo usa finales de línea de Windows (CRLF): no los cambies.
