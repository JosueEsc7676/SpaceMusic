package com.bpm.music.soporte;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.security.JwtUtil;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.EscanerService;
import com.bpm.music.services.MailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Diagnóstico controlado de SpaceMusic. Todas las pruebas son de solo lectura (no cambian datos de nadie),
 * tienen tiempo máximo y no se puede ejecutar más de un diagnóstico a la vez.
 */
@Service
public class DiagnosticoService {

    public record Prueba(String id, String nombre, String descripcion) {}

    public static final List<Prueba> PRUEBAS = List.of(
            new Prueba("api", "API", "La API responde a una petición real"),
            new Prueba("baseDatos", "Base de datos", "Conexión y lectura de datos"),
            new Prueba("almacenamiento", "Almacenamiento musical", "La carpeta de música existe y se puede leer"),
            new Prueba("archivos", "Archivos de canciones", "Una muestra de canciones tiene su archivo en el disco"),
            new Prueba("lectura", "Lectura de audio", "Se pueden leer los primeros bytes de una canción"),
            new Prueba("endpoints", "Rutas esenciales", "Inicio, service worker, portadas y reproducción responden"),
            new Prueba("externos", "Servicios externos", "MusicBrainz, Deezer y LRCLIB (géneros y letras)"),
            new Prueba("tareas", "Tareas programadas", "El monitor y el escáner de la biblioteca siguen activos"),
            new Prueba("configuracion", "Configuración esencial", "Clave de sesiones, carpeta de música, URL pública…"),
            new Prueba("correo", "Correo", "Si el envío de correos está configurado (no se envía nada)"));

    private static final long LIMITE_PRUEBA_MS = 15_000;

    private final DiagnosticoRepository repo;
    private final MonitorService monitor;
    private final EscanerService escaner;
    private final BibliotecaService biblioteca;
    private final CancionRepository canciones;
    private final UsuarioRepository usuarios;
    private final MailService mail;
    private final JwtUtil jwt;
    private final Environment env;
    private final AuditoriaService auditoria;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "soporte-diagnostico"); t.setDaemon(true); return t; });
    private final ExecutorService pruebas = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "soporte-prueba"); t.setDaemon(true); return t; });

    /** Diagnóstico en curso: id, total, hechas y resultados parciales. */
    private final AtomicReference<Map<String, Object>> enCurso = new AtomicReference<>();

    public DiagnosticoService(DiagnosticoRepository repo, MonitorService monitor, EscanerService escaner, BibliotecaService biblioteca,
                              CancionRepository canciones, UsuarioRepository usuarios, MailService mail, JwtUtil jwt,
                              Environment env, AuditoriaService auditoria) {
        this.repo = repo;
        this.monitor = monitor;
        this.escaner = escaner;
        this.biblioteca = biblioteca;
        this.canciones = canciones;
        this.usuarios = usuarios;
        this.mail = mail;
        this.jwt = jwt;
        this.env = env;
        this.auditoria = auditoria;
    }

    /** Inicia el diagnóstico (todas las pruebas o una). Devuelve el id de la ejecución o lanza si ya hay uno. */
    public synchronized Map<String, Object> iniciar(String pruebaId) {
        if (enCurso.get() != null) throw new IllegalStateException("Ya hay un diagnóstico en curso");
        List<Prueba> lista = pruebaId == null || pruebaId.isBlank() ? PRUEBAS
                : PRUEBAS.stream().filter(p -> p.id().equals(pruebaId)).toList();
        if (lista.isEmpty()) throw new IllegalArgumentException("Prueba desconocida");
        Long uid = CurrentUser.id();
        String usuario = usuarios.findById(uid).map(u -> u.getUsername()).orElse("#" + uid);
        // Token propio y temporal para probar la reproducción como ese administrador (nunca se guarda ni se registra)
        String token = usuarios.findById(uid).map(u -> jwt.generateToken(u.getId(), u.getUsername(), u.getRol().name())).orElse(null);
        DiagnosticoEjecucion e = new DiagnosticoEjecucion();
        e.setTipo(lista.size() == 1 ? "prueba" : "completo");
        e.setInicio(LocalDateTime.now());
        e.setUsuario(usuario);
        e.setEstado("EN_CURSO");
        e = repo.save(e);
        Map<String, Object> estado = new ConcurrentHashMap<>();
        estado.put("id", e.getId());
        estado.put("total", lista.size());
        estado.put("hechas", 0);
        estado.put("resultados", Collections.synchronizedList(new ArrayList<Map<String, Object>>()));
        enCurso.set(estado);
        Long id = e.getId();
        hilo.submit(() -> ejecutar(id, lista, estado, token));
        auditoria.registrar("diagnostico.ejecutar", lista.size() == 1 ? "Prueba: " + lista.get(0).nombre() : "Diagnóstico completo", "OK");
        return Map.of("id", id, "total", lista.size());
    }

    @SuppressWarnings("unchecked")
    private void ejecutar(Long id, List<Prueba> lista, Map<String, Object> estado, String token) {
        List<Map<String, Object>> res = (List<Map<String, Object>>) estado.get("resultados");
        try {
            for (Prueba p : lista) {
                res.add(correr(p, token));
                estado.put("hechas", res.size());
            }
            DiagnosticoEjecucion e = repo.findById(id).orElseThrow();
            e.setFin(LocalDateTime.now());
            e.setEstado("TERMINADO");
            e.setCorrectas((int) res.stream().filter(r -> "correcta".equals(r.get("estado"))).count());
            e.setAdvertencias((int) res.stream().filter(r -> "advertencia".equals(r.get("estado"))).count());
            e.setFallidas((int) res.stream().filter(r -> "fallida".equals(r.get("estado"))).count());
            e.setNoDisponibles((int) res.stream().filter(r -> "no_disponible".equals(r.get("estado"))).count());
            e.setResultados(json.writeValueAsString(res));
            repo.save(e);
        } catch (Exception ex) {
            repo.findById(id).ifPresent(e -> { e.setFin(LocalDateTime.now()); e.setEstado("FALLIDO"); repo.save(e); });
        } finally {
            enCurso.set(null);
        }
    }

    private Map<String, Object> correr(Prueba p, String token) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", p.id());
        r.put("nombre", p.nombre());
        r.put("hora", LocalDateTime.now().toString());
        long t0 = System.nanoTime();
        Future<String[]> f = pruebas.submit(() -> prueba(p.id(), token));
        String[] out;
        try {
            out = f.get(LIMITE_PRUEBA_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            out = new String[]{"fallida", "No terminó en " + LIMITE_PRUEBA_MS / 1000 + " s", "Revisa si el servidor está sobrecargado o un servicio no responde."};
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            out = new String[]{"fallida", Sanitizador.limpiar(c.getClass().getSimpleName() + ": " + c.getMessage(), 300), null};
        }
        r.put("duracionMs", Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
        r.put("estado", out[0]);
        r.put("resultado", out[1]);
        r.put("recomendacion", out.length > 2 ? out[2] : null);
        return r;
    }

    // ═══════════════════ Pruebas (solo lectura) ═══════════════════

    private String[] prueba(String id, String token) throws Exception {
        return switch (id) {
            case "api" -> {
                HttpResponse<String> r = get("/api/auth/estado", null, null);
                yield r.statusCode() == 200 ? ok("Respondió " + r.statusCode())
                        : fallo("Respondió " + r.statusCode(), "La API no responde como se espera: revisa los registros del servidor.");
            }
            case "baseDatos" -> {
                double[] ping = monitor.pingDb();
                if (ping == null) yield fallo("No responde a SELECT 1", "Revisa que MySQL esté encendido y las credenciales de la base de datos.");
                long n = usuarios.count();
                long c = canciones.count();
                yield ping[0] > 500 ? adv("Responde lento: " + Math.round(ping[0]) + " ms (" + n + " cuentas, " + c + " canciones)", "La base de datos está lenta; revisa la carga del servidor.")
                        : ok("Responde en " + Math.round(ping[0] * 10) / 10.0 + " ms · " + n + " cuentas, " + c + " canciones");
            }
            case "almacenamiento" -> {
                File raiz = escaner.raiz();
                if (!raiz.isDirectory()) yield fallo("La carpeta " + raiz.getName() + " no existe", "Revisa music.path en la configuración del servidor.");
                if (!raiz.canRead()) yield fallo("La carpeta existe pero no se puede leer", "Revisa los permisos de la carpeta de música.");
                String[] hijos = raiz.list();
                double libre = raiz.getUsableSpace() / 1e9;
                yield libre < 2 ? adv("Se lee bien, pero quedan " + Math.round(libre * 10) / 10.0 + " GB libres", "Libera espacio en el disco del servidor.")
                        : ok("Se lee bien · " + (hijos == null ? 0 : hijos.length) + " elementos en la raíz · " + Math.round(libre) + " GB libres");
            }
            case "archivos" -> {
                List<Object[]> rutas = canciones.findRutas();
                if (rutas.isEmpty()) yield nd("No hay canciones en la biblioteca");
                List<Object[]> muestra = new ArrayList<>(rutas);
                Collections.shuffle(muestra);
                muestra = muestra.subList(0, Math.min(25, muestra.size()));
                int faltan = 0;
                for (Object[] f : muestra) {
                    File a = new File(String.valueOf(f[1]));
                    if (!a.isFile() || a.length() == 0) faltan++;
                }
                yield faltan == 0 ? ok("Las " + muestra.size() + " canciones revisadas al azar tienen su archivo")
                        : adv(faltan + " de " + muestra.size() + " canciones revisadas no tienen archivo", "Ejecuta la revisión de la biblioteca para ver cuáles son.");
            }
            case "lectura" -> {
                List<Object[]> rutas = new ArrayList<>(canciones.findRutas());
                Collections.shuffle(rutas);
                for (Object[] f : rutas.subList(0, Math.min(10, rutas.size()))) {
                    File a = new File(String.valueOf(f[1]));
                    if (!a.isFile()) continue;
                    byte[] buf = new byte[65536];
                    int n;
                    try (InputStream in = new FileInputStream(a)) { n = in.read(buf); }
                    yield n > 0 ? ok("Se leyeron " + n / 1024 + " KB de la canción #" + f[0])
                            : adv("El archivo de la canción #" + f[0] + " está vacío", "Revisa el archivo en el disco.");
                }
                yield nd("No se encontró un archivo de canción para leer");
            }
            case "endpoints" -> {
                List<String> malos = new ArrayList<>();
                int okN = 0;
                for (String ruta : List.of("/", "/sw.js", "/app/version.json")) {
                    int s = get(ruta, null, null).statusCode();
                    if (s == 200) okN++; else malos.add(ruta + " → " + s);
                }
                Optional<CancionDTO> c = biblioteca.todas().stream().findAny();
                if (c.isPresent()) {
                    int sp = get(c.get().getPortadaUrl() != null && !c.get().getPortadaUrl().isBlank() ? c.get().getPortadaUrl() : "/api/portada/db/" + c.get().getId(), null, null).statusCode();
                    if (sp == 200 || sp == 302 || sp == 304) okN++; else malos.add("portada → " + sp);
                    if (token != null) {
                        int sa = get("/api/canciones/" + c.get().getId() + "/audio", token, "bytes=0-1023").statusCode();
                        if (sa == 206 || sa == 200) okN++; else malos.add("reproducción → " + sa);
                    }
                }
                yield malos.isEmpty() ? ok(okN + " rutas responden (incluida la reproducción con rango de bytes)")
                        : fallo(String.join(" · ", malos), "Revisa los registros de incidencias de esas rutas.");
            }
            case "externos" -> {
                List<String> mal = new ArrayList<>(), bien = new ArrayList<>();
                for (String[] s : new String[][]{{"MusicBrainz", "https://musicbrainz.org/ws/2/artist/?query=test&limit=1&fmt=json"},
                        {"Deezer", "https://api.deezer.com/search/artist?q=test&limit=1"}, {"LRCLIB", "https://lrclib.net/api/search?q=test"}}) {
                    try {
                        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(s[1])).timeout(Duration.ofSeconds(6))
                                .header("User-Agent", "SpaceMusic/2.0 (diagnostico)").GET().build(), HttpResponse.BodyHandlers.discarding());
                        if (r.statusCode() < 500) bien.add(s[0]); else mal.add(s[0] + " (" + r.statusCode() + ")");
                    } catch (Exception e) {
                        mal.add(s[0] + " (sin respuesta)");
                    }
                }
                yield mal.isEmpty() ? ok("Responden: " + String.join(", ", bien))
                        : adv("No responden: " + String.join(", ", mal) + (bien.isEmpty() ? "" : " · Responden: " + String.join(", ", bien)),
                        "Sin estos servicios no se buscan géneros ni letras nuevas; la música sigue funcionando.");
            }
            case "tareas" -> {
                MetricaMuestra m = monitor.ultimaMuestra();
                Map<String, Object> esc = escaner.estado();
                boolean monitorOk = m != null && m.getFecha().isAfter(LocalDateTime.now().minusMinutes(3));
                Object fin = esc.get("ultimoFin");
                String escTxt = "Escáner: " + esc.get("fase") + (fin != null ? " · último fin " + fin : "");
                if (m == null) yield nd("El monitor aún no guarda su primera muestra (espera un minuto) · " + escTxt);
                yield monitorOk ? ok("Monitor activo (última muestra " + m.getFecha().withNano(0) + ") · " + escTxt)
                        : fallo("El monitor no guarda muestras desde " + m.getFecha().withNano(0), "Las tareas programadas podrían estar detenidas: revisa los registros del servidor.");
            }
            case "configuracion" -> {
                List<String> avisos = new ArrayList<>(), bien = new ArrayList<>();
                String secreto = env.getProperty("jwt.secret", "");
                if (secreto.isBlank()) avisos.add("jwt.secret vacío: las sesiones se cierran al reiniciar"); else bien.add("clave de sesiones configurada");
                if (!escaner.raiz().isDirectory()) avisos.add("music.path no existe"); else bien.add("carpeta de música");
                String base = env.getProperty("app.base-url", "");
                if (base.startsWith("http://localhost")) avisos.add("app.base-url apunta a localhost (los enlaces de los correos no funcionarán fuera)"); else bien.add("URL pública");
                String url = env.getProperty("spring.datasource.url", "");
                bien.add(url.contains(":h2:") ? "base de datos H2 (desarrollo)" : url.contains(":mysql:") ? "base de datos MySQL" : "base de datos");
                bien.add("esquema: Hibernate ddl-auto=" + env.getProperty("spring.jpa.hibernate.ddl-auto", "?") + " (sin migraciones)");
                yield avisos.isEmpty() ? ok(String.join(" · ", bien)) : adv(String.join(" · ", avisos), "Corrige estos valores en la configuración del servidor.");
            }
            case "correo" -> mail.configurado()
                    ? ok("El correo está configurado (" + env.getProperty("spring.mail.host", "?") + "). No se envió ningún mensaje de prueba.")
                    : nd("Pendiente de configuración: sin correo no se envían verificaciones ni recuperación de contraseñas");
            default -> nd("Prueba desconocida");
        };
    }

    private HttpResponse<String> get(String ruta, String token, String rango) throws Exception {
        String puerto = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + ruta)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "SpaceMusic-diagnostico").GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (rango != null) b.header("Range", rango);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r;
    }

    private static String[] ok(String t) { return new String[]{"correcta", t, null}; }
    private static String[] adv(String t, String rec) { return new String[]{"advertencia", t, rec}; }
    private static String[] fallo(String t, String rec) { return new String[]{"fallida", t, rec}; }
    private static String[] nd(String t) { return new String[]{"no_disponible", t, null}; }

    // ═══════════════════ Consultas ═══════════════════

    public Map<String, Object> estado(Long id) {
        Map<String, Object> c = enCurso.get();
        if (c != null && Objects.equals(c.get("id"), id)) {
            Map<String, Object> r = new LinkedHashMap<>(c);
            r.put("estado", "EN_CURSO");
            return r;
        }
        DiagnosticoEjecucion e = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Diagnóstico no encontrado"));
        return detalle(e);
    }

    public Page<Map<String, Object>> historial(int pagina, int tam) {
        return repo.findByTipoInOrderByInicioDesc(List.of("completo", "prueba"), PageRequest.of(Math.max(0, pagina), Math.min(Math.max(tam, 1), 50)))
                .map(e -> { Map<String, Object> m = detalle(e); m.remove("resultados"); return m; });
    }

    public boolean ocupado() { return enCurso.get() != null; }

    Map<String, Object> detalle(DiagnosticoEjecucion e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("tipo", e.getTipo());
        m.put("inicio", e.getInicio());
        m.put("fin", e.getFin());
        m.put("usuario", e.getUsuario());
        m.put("estado", e.getEstado());
        m.put("correctas", e.getCorrectas());
        m.put("advertencias", e.getAdvertencias());
        m.put("fallidas", e.getFallidas());
        m.put("noDisponibles", e.getNoDisponibles());
        try { m.put("resultados", e.getResultados() == null ? List.of() : json.readValue(e.getResultados(), List.class)); }
        catch (Exception ex) { m.put("resultados", List.of()); }
        return m;
    }
}
