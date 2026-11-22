package com.bpm.music.descargas;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panel de "Descargar música": hace lo mismo que "Descargar musica.bat", pero desde la web.
 * Usa el programa {@code descargar} del servidor (cola en segundo plano, historial sin repetidos,
 * reintentos) y lee sus archivos de estado: cola, registro, fallidas y bloqueo de Spotify.
 * No ejecuta comandos con texto del usuario: los enlaces van por la entrada estándar a un archivo de cola.
 * Las acciones quedan en la auditoría del soporte (AuditoriaInterceptor, como todo /api/admin).
 */
@Service
public class DescargasService {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
    private static final Pattern LISTA = Pattern.compile("════ (\\S+ \\S+) · Lista (\\S+) · (\\d+) entrada");
    private static final Pattern ENTRADA = Pattern.compile("▶ \\[(\\d+)/(\\d+)] (.+)");
    private static final Pattern NOMBRE_COLA = Pattern.compile("[0-9]{8}-[0-9]{6}-[0-9]+\\.txt");
    private static final int MAX_LINEAS = 2000;
    private static final int MAX_LARGO = 500;

    private final Path script;
    private final Path datos;
    private com.bpm.music.soporte.MonitorService monitor;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMonitor(com.bpm.music.soporte.MonitorService monitor) { this.monitor = monitor; }
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 10000, fixedDelay = 2000)
    public void prioridadAudio() {
        if (monitor == null || !Files.isDirectory(datos)) return;
        try {
            Path aviso = datos.resolve("audio-activo");
            if (monitor.audiosActivos() > 0) Files.writeString(aviso, String.valueOf(System.currentTimeMillis()));
            else Files.deleteIfExists(aviso);
        } catch (IOException ignored) { }
    }

    public DescargasService(@Value("${descargas.script:/usr/local/bin/descargar}") String script,
                            @Value("${descargas.datos:${user.home}/.config/spacemusic}") String datos) {
        Path preferido = Path.of(System.getProperty("user.home"), "spacemusic", "descargar-api");
        this.script = "/usr/local/bin/descargar".equals(script) && Files.isExecutable(preferido) ? preferido : Path.of(script);
        this.datos = Path.of(datos);
    }

    // ═══════════════════ Buscar en YouTube Music ═══════════════════

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Pattern ID_EN_TEXTO = Pattern.compile("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})");
    private com.bpm.music.externo.YouTubeMusicProveedor ytm;
    private com.bpm.music.services.BibliotecaService biblioteca;
    /** Datos de lo último que se mostró en una búsqueda (para comprobar duplicados al encolar por id). */
    private final com.bpm.music.services.CacheTemporal<String, Map<String, Object>> vistos = new com.bpm.music.services.CacheTemporal<>(6 * 3600_000, 5000);

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBusqueda(com.bpm.music.externo.YouTubeMusicProveedor ytm, com.bpm.music.services.BibliotecaService biblioteca) {
        this.ytm = ytm;
        this.biblioteca = biblioteca;
    }

    /** Dónde aparece cada canción de YouTube: en cola, descargándose, terminada o fallida. */
    record Situacion(Set<String> enCola, Set<String> descargando, Set<String> terminadas, Set<String> fallidas) {}

    Situacion situacion() {
        Set<String> cola = new HashSet<>(), bajando = new HashSet<>(), hechas = new HashSet<>(), fallas = new HashSet<>();
        List<Path> archivos = listar(datos.resolve("cola"));
        boolean activo = pidTrabajador() != null;
        for (int i = 0; i < archivos.size(); i++)
            for (String l : leerLineas(archivos.get(i), MAX_LINEAS)) ids(l, cola);
        for (Path f : listar(datos.resolve("cola-hechas"))) for (String l : leerLineas(f, MAX_LINEAS)) ids(l, hechas);
        for (String l : leerLineas(datos.resolve("fallidas.txt"), 2000)) ids(l, fallas);
        if (activo) {
            for (String l : cola(datos.resolve("descargas.log"), 200)) {
                Matcher b = ENTRADA.matcher(l);
                if (b.find()) { bajando.clear(); ids(b.group(3), bajando); }
            }
        }
        return new Situacion(cola, bajando, hechas, fallas);
    }

    private static void ids(String texto, Set<String> destino) {
        Matcher m = ID_EN_TEXTO.matcher(texto);
        while (m.find()) destino.add(m.group(1));
    }

    /** Estado de un resultado: biblioteca, descargando, en_cola, completado, fallido o disponible. */
    String estadoDe(String videoId, String titulo, List<String> artistas, Integer duracion, Situacion s, List<com.bpm.music.dtos.CancionDTO> locales) {
        if (locales != null && titulo != null) {
            var c = com.bpm.music.externo.Coincidencias.buscar(titulo, artistas, duracion, locales);
            if (c != null && c.getValue() == com.bpm.music.externo.Coincidencias.Resultado.MISMA) return "biblioteca";
        }
        if (s.descargando().contains(videoId)) return "descargando";
        if (s.enCola().contains(videoId)) return "en_cola";
        if (s.fallidas().contains(videoId)) return "fallido";
        if (s.terminadas().contains(videoId)) return "completado";
        return "disponible";
    }

    private Map<String, List<com.bpm.music.dtos.CancionDTO>> indiceLocal() {
        Map<String, List<com.bpm.music.dtos.CancionDTO>> r = new HashMap<>();
        if (biblioteca == null) return r;
        for (var c : biblioteca.todas()) r.computeIfAbsent(com.bpm.music.externo.Coincidencias.base(c.getNombre()), k -> new ArrayList<>()).add(c);
        return r;
    }

    private List<Map<String, Object>> conEstado(com.fasterxml.jackson.databind.JsonNode resultados) {
        Situacion s = situacion();
        Map<String, List<com.bpm.music.dtos.CancionDTO>> indice = indiceLocal();
        List<Map<String, Object>> r = new ArrayList<>();
        for (var it : resultados) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new LinkedHashMap<>(JSON.convertValue(it, Map.class));
            String vid = it.path("videoId").asText(null);
            if (vid != null) {
                List<String> arts = new ArrayList<>();
                it.path("artistas").forEach(a -> arts.add(a.asText()));
                Integer dur = it.path("duracion").isNumber() ? it.path("duracion").asInt() : null;
                String titulo = it.path("titulo").asText(null);
                m.put("estado", estadoDe(vid, titulo, arts, dur, s, indice.get(com.bpm.music.externo.Coincidencias.base(titulo))));
                vistos.poner(vid, m);
            }
            r.add(m);
        }
        return r;
    }

    private com.bpm.music.externo.YouTubeMusicProveedor ytm() {
        if (ytm == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La búsqueda de YouTube Music no está disponible");
        return ytm;
    }

    public Map<String, Object> buscarYT(String consulta, String filtro, int limite) {
        var r = ytm().buscar(consulta, filtro, limite);
        return Map.of("resultados", conEstado(r.path("resultados")), "limite", limite, "hayMas", r.path("resultados").size() >= limite && limite < 50);
    }

    public Map<String, Object> albumYT(String browseId) {
        var r = ytm().album(browseId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("album", JSON.convertValue(r.path("album"), Map.class));
        m.put("resultados", conEstado(r.path("resultados")));
        return m;
    }

    public Map<String, Object> artistaYT(String browseId) {
        var r = ytm().artista(browseId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("artista", JSON.convertValue(r.path("artista"), Map.class));
        m.put("resultados", conEstado(r.path("resultados")));
        return m;
    }

    /**
     * Agrega canciones de YouTube Music a la cola por su id, sin copiar enlaces. Se omiten las que ya
     * están en la biblioteca, en la cola o descargándose. La descarga la hace la cola de siempre, en segundo plano.
     */
    public synchronized Map<String, Object> encolarCanciones(List<String> ids) {
        requerirInstalado();
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Elige al menos una canción");
        if (ids.size() > 200) throw new IllegalArgumentException("Máximo 200 canciones por vez");
        Situacion s = situacion();
        Map<String, List<com.bpm.music.dtos.CancionDTO>> indice = indiceLocal();
        List<String> lineas = new ArrayList<>();
        Map<String, String> omitidas = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(ids)) {
            if (id == null || !com.bpm.music.externo.YouTubeMusicProveedor.VIDEO_ID.matcher(id).matches())
                throw new IllegalArgumentException("Identificador no válido");
            Map<String, Object> v = vistos.leer(id);
            String titulo = v == null ? null : (String) v.get("titulo");
            @SuppressWarnings("unchecked") List<String> arts = v == null ? List.of() : (List<String>) v.getOrDefault("artistas", List.of());
            Integer dur = v != null && v.get("duracion") instanceof Number n ? n.intValue() : null;
            String e = estadoDe(id, titulo, arts, dur, s, indice.get(com.bpm.music.externo.Coincidencias.base(titulo)));
            if (Set.of("biblioteca", "en_cola", "descargando").contains(e)) { omitidas.put(id, e); continue; }
            lineas.add("https://music.youtube.com/watch?v=" + id);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("omitidas", omitidas);
        if (lineas.isEmpty()) { r.put("agregadas", 0); r.put("mensaje", "Ya estaban en la biblioteca o en la cola"); return r; }
        Map<String, Object> res = encolar(String.join("\n", lineas));
        r.put("agregadas", res.get("agregadas"));
        r.put("mensaje", res.get("mensaje"));
        return r;
    }

    /** Un álbum completo por su lista de YouTube Music (OLAK5uy_…). */
    public synchronized Map<String, Object> encolarAlbum(String listaId) {
        requerirInstalado();
        if (listaId == null || !listaId.matches("OLAK5uy_[A-Za-z0-9_-]{20,60}")) throw new IllegalArgumentException("Álbum no válido");
        String url = "https://music.youtube.com/playlist?list=" + listaId;
        for (Path f : listar(datos.resolve("cola"))) if (leerLineas(f, MAX_LINEAS).contains(url))
            return Map.of("agregadas", 0, "mensaje", "Ese álbum ya está en la cola");
        return encolar(url);
    }

    public boolean instalado() {
        return Files.isExecutable(script);
    }

    // ═══════════════════ Estado ═══════════════════

    public Map<String, Object> estado(int lineasRegistro) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("instalado", instalado());
        if (!instalado()) {
            r.put("nota", "La herramienta de descargas no está instalada en este servidor (" + script + ").");
            return r;
        }
        Long pid = pidTrabajador();
        boolean activo = pid != null;
        r.put("activo", activo);

        // Listas en la cola (la más vieja es la que se está bajando si el trabajador está activo)
        List<Map<String, Object>> cola = new ArrayList<>();
        List<Path> archivos = listar(datos.resolve("cola"));
        for (int i = 0; i < archivos.size(); i++) {
            Path f = archivos.get(i);
            List<String> lineas = leerLineas(f, 400);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", f.getFileName().toString());
            m.put("entradas", contarLineas(f));
            m.put("muestra", lineas.stream().limit(5).toList());
            m.put("fecha", fechaDe(f));
            m.put("enCurso", activo && i == 0);
            cola.add(m);
        }
        r.put("cola", cola);

        // Lo último terminado
        List<Path> hechas = listar(datos.resolve("cola-hechas"));
        Collections.reverse(hechas);
        r.put("terminadas", hechas.stream().limit(10).map(f -> Map.of(
                "nombre", f.getFileName().toString(), "entradas", contarLineas(f), "fecha", fechaDe(f))).toList());

        // Registro: progreso de la lista actual y últimas líneas
        List<String> log = cola(datos.resolve("descargas.log"), Math.min(Math.max(lineasRegistro, 20), 400));
        r.put("registro", log);
        Map<String, Object> progreso = new LinkedHashMap<>();
        for (String l : log) {
            Matcher a = LISTA.matcher(l);
            if (a.find()) { progreso.clear(); progreso.put("inicio", a.group(1)); progreso.put("lista", a.group(2)); progreso.put("total", Integer.parseInt(a.group(3))); }
            Matcher b = ENTRADA.matcher(l);
            if (b.find()) { progreso.put("actual", Integer.parseInt(b.group(1))); progreso.put("de", Integer.parseInt(b.group(2))); progreso.put("entrada", b.group(3)); }
            if (l.startsWith("✓ Todo descargado")) progreso.put("resultado", "ok");
            if (l.startsWith("✗ No se pudo")) progreso.put("resultado", "con fallos");
        }
        r.put("progreso", progreso);

        List<String> fallidas = leerLineas(datos.resolve("fallidas.txt"), 500).stream().distinct().toList();
        r.put("fallidas", fallidas);

        // Spotify: claves configuradas (nunca se devuelven) y si Spotify las limitó
        r.put("spotifyConfigurado", Files.exists(datos.resolve("spotify.env")));
        try {
            String hasta = Files.readString(datos.resolve(".spotify-bloqueado-hasta")).trim();
            long seg = Long.parseLong(hasta);
            if (seg > Instant.now().getEpochSecond()) r.put("spotifyLimitadoHasta", Instant.ofEpochSecond(seg).toString());
        } catch (Exception ignored) { }
        return r;
    }

    // ═══════════════════ Acciones ═══════════════════

    /** Agrega enlaces o búsquedas "Artista - Canción" a la cola (una por línea). */
    public synchronized Map<String, Object> encolar(String texto) {
        requerirInstalado();
        if (texto == null || texto.isBlank()) throw new IllegalArgumentException("Pega al menos un enlace");
        List<String> lineas = new ArrayList<>();
        for (String l : texto.replace("\r", "").split("\n")) {
            for (String parte : separar(l.strip())) {
                if (parte.isEmpty()) continue;
                if (parte.length() > MAX_LARGO) throw new IllegalArgumentException("Una línea es demasiado larga (máximo " + MAX_LARGO + " caracteres)");
                // Una línea que empieza con "-" se tomaría como opción del programa: no se acepta
                if (parte.startsWith("-")) throw new IllegalArgumentException("Las líneas no pueden empezar con “-”: " + recortar(parte));
                if (parte.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Hay caracteres no válidos en: " + recortar(parte));
                if (parte.matches("(?i)^[a-z]+://.*") && !parte.matches("(?i)^https?://.*"))
                    throw new IllegalArgumentException("Solo se aceptan enlaces http o https: " + recortar(parte));
                lineas.add(parte);
            }
        }
        if (lineas.isEmpty()) throw new IllegalArgumentException("Pega al menos un enlace");
        if (lineas.size() > MAX_LINEAS) throw new IllegalArgumentException("Máximo " + MAX_LINEAS + " entradas por vez");
        List<String> unicas = new ArrayList<>(new LinkedHashSet<>(lineas));
        String salida = ejecutar(String.join("\n", unicas) + "\n", "--encolar");
        return Map.of("agregadas", unicas.size(), "repetidasQuitadas", lineas.size() - unicas.size(), "mensaje", salida);
    }

    public Map<String, Object> reintentar() {
        requerirInstalado();
        String salida = ejecutar(null, "--reintentar");
        return Map.of("mensaje", salida);
    }

    /** Detiene la descarga en curso. Las listas siguen en la cola y se pueden reanudar (lo bajado no se repite). */
    public Map<String, Object> detener() {
        requerirInstalado();
        Long pid = pidTrabajador();
        if (pid == null) return Map.of("mensaje", "No hay descargas en curso");
        // El trabajador se arranca con setsid: su grupo de procesos incluye spotdl y yt-dlp
        correr(List.of("kill", "-TERM", "-" + pid));
        correr(List.of("kill", "-TERM", String.valueOf(pid)));
        return Map.of("mensaje", "Descargas detenidas. Lo que quedó en la cola se puede reanudar.");
    }

    public Map<String, Object> reanudar() {
        requerirInstalado();
        if (pidTrabajador() != null) return Map.of("mensaje", "Ya se está descargando");
        if (listar(datos.resolve("cola")).isEmpty()) return Map.of("mensaje", "La cola está vacía");
        try {
            List<String> comando = new ArrayList<>(List.of("setsid", "-f"));
            if (Files.isExecutable(Path.of("/usr/bin/ionice"))) comando.addAll(List.of("/usr/bin/ionice", "-c", "3"));
            if (Files.isExecutable(Path.of("/usr/bin/nice"))) comando.addAll(List.of("/usr/bin/nice", "-n", "15"));
            comando.addAll(List.of(script.toString(), "--trabajar"));
            new ProcessBuilder(comando)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(datos.resolve("descargas.log").toFile()))
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo reanudar: " + e.getMessage());
        }
        return Map.of("mensaje", "Descargas reanudadas");
    }

    /** Quita de la cola una lista que todavía no empezó. */
    public void quitarDeCola(String nombre) {
        requerirInstalado();
        if (nombre == null || !NOMBRE_COLA.matcher(nombre).matches()) throw new IllegalArgumentException("Lista no válida");
        List<Path> archivos = listar(datos.resolve("cola"));
        Path f = datos.resolve("cola").resolve(nombre);
        if (!archivos.contains(f)) throw new NoSuchElementException("Esa lista ya no está en la cola");
        if (pidTrabajador() != null && archivos.get(0).equals(f))
            throw new IllegalArgumentException("Esa lista se está descargando ahora: usa “Detener” primero");
        try { Files.delete(f); } catch (IOException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo quitar la lista"); }
    }

    /** Olvida la lista de fallidas (no borra canciones). */
    public void limpiarFallidas() {
        requerirInstalado();
        try { Files.writeString(datos.resolve("fallidas.txt"), ""); } catch (IOException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo limpiar la lista"); }
    }

    /** Guarda las credenciales propias de Spotify (como la opción C del .bat). Nunca se devuelven. */
    public void guardarSpotify(String id, String secreto) {
        requerirInstalado();
        if (id == null || secreto == null || !id.trim().matches("[A-Za-z0-9]{16,64}") || !secreto.trim().matches("[A-Za-z0-9]{16,64}"))
            throw new IllegalArgumentException("El Client ID y el Client secret son códigos de letras y números (32 caracteres)");
        Path f = datos.resolve("spotify.env");
        try {
            Files.createDirectories(datos);
            Files.writeString(f, "CLIENT_ID=" + id.trim() + "\nCLIENT_SECRET=" + secreto.trim() + "\n", StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudieron guardar las credenciales");
        }
    }

    // ═══════════════════ Ayudas ═══════════════════

    private void requerirInstalado() {
        if (!instalado()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La herramienta de descargas no está instalada en este servidor");
    }

    /** Separa varios enlaces pegados en una línea; las búsquedas "Artista - Canción" quedan enteras. */
    static List<String> separar(String linea) {
        if (linea.matches("(?i)^https?://.*") && linea.contains(" ")) return Arrays.asList(linea.split("\\s+"));
        return List.of(linea);
    }

    private static String recortar(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private String ejecutar(String entrada, String opcion) {
        try {
            Process p = new ProcessBuilder(script.toString(), opcion).redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                if (entrada != null) os.write(entrada.getBytes(StandardCharsets.UTF_8));
            }
            String salida;
            try (InputStream is = p.getInputStream()) {
                salida = new String(is.readNBytes(20_000), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroy();
            return limpiar(salida).strip();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo ejecutar la herramienta de descargas: " + e.getMessage());
        }
    }

    private static void correr(List<String> cmd) {
        try { new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    private Long pidTrabajador() {
        try {
            String t = Files.readString(datos.resolve(".trabajador.pid")).trim();
            if (!t.matches("\\d{1,9}")) return null;
            long pid = Long.parseLong(t);
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent() ? pid : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Path> listar(Path dir) {
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (var s = Files.list(dir)) {
            return new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<String> leerLineas(Path f, int max) {
        try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            List<String> r = new ArrayList<>();
            String l;
            while ((l = br.readLine()) != null && r.size() < max) if (!l.isBlank()) r.add(limpiar(l).strip());
            return r;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long contarLineas(Path f) {
        try (var s = Files.lines(f, StandardCharsets.UTF_8)) {
            return s.filter(l -> !l.isBlank()).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Últimas {@code n} líneas del registro, sin colores de terminal (se leen solo los últimos 256 KB). */
    private static List<String> cola(Path f, int n) {
        if (!Files.exists(f)) return List.of();
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long largo = raf.length();
            long desde = Math.max(0, largo - 256 * 1024);
            raf.seek(desde);
            byte[] b = new byte[(int) (largo - desde)];
            raf.readFully(b);
            String texto = limpiar(new String(b, StandardCharsets.UTF_8));
            List<String> lineas = new ArrayList<>();
            for (String l : texto.split("\n")) {
                // las barras de progreso reescriben la línea con \r: se queda lo último
                int k = l.lastIndexOf('\r');
                String x = (k >= 0 ? l.substring(k + 1) : l).stripTrailing();
                if (!x.isBlank()) lineas.add(x);
            }
            if (desde > 0 && !lineas.isEmpty()) lineas.remove(0); // primera línea cortada
            return lineas.subList(Math.max(0, lineas.size() - n), lineas.size());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String limpiar(String s) {
        return ANSI.matcher(s).replaceAll("");
    }

    private static String fechaDe(Path f) {
        try { return Files.getLastModifiedTime(f).toInstant().toString(); } catch (IOException e) { return null; }
    }
}
