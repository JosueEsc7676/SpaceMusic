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

    public DescargasService(@Value("${descargas.script:/usr/local/bin/descargar}") String script,
                            @Value("${descargas.datos:${user.home}/.config/spacemusic}") String datos) {
        this.script = Path.of(script);
        this.datos = Path.of(datos);
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
    public Map<String, Object> encolar(String texto) {
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
            new ProcessBuilder("setsid", "-f", script.toString(), "--trabajar")
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
