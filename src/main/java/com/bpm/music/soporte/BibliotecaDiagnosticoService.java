package com.bpm.music.soporte;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.security.JwtUtil;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.EscanerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.bpm.music.services.BibliotecaService.normalizar;

/**
 * Revisión de la biblioteca musical (solo lectura): no borra, reemplaza ni cambia canciones, portadas,
 * letras ni metadatos. Se ejecuta en segundo plano, una a la vez, y guarda el resultado en el historial.
 */
@Service
public class BibliotecaDiagnosticoService {

    private static final int MAX_LISTA = 200;

    private final CancionRepository canciones;
    private final BibliotecaService biblioteca;
    private final EscanerService escaner;
    private final DiagnosticoRepository repo;
    private final UsuarioRepository usuarios;
    private final JwtUtil jwt;
    private final Environment env;
    private final AuditoriaService auditoria;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicBoolean ocupado = new AtomicBoolean();
    private final Map<String, Object> progreso = new ConcurrentHashMap<>();

    public BibliotecaDiagnosticoService(CancionRepository canciones, BibliotecaService biblioteca, EscanerService escaner,
                                        DiagnosticoRepository repo, UsuarioRepository usuarios, JwtUtil jwt, Environment env,
                                        AuditoriaService auditoria) {
        this.canciones = canciones;
        this.biblioteca = biblioteca;
        this.escaner = escaner;
        this.repo = repo;
        this.usuarios = usuarios;
        this.jwt = jwt;
        this.env = env;
        this.auditoria = auditoria;
    }

    public Map<String, Object> iniciar() {
        if (!ocupado.compareAndSet(false, true)) throw new IllegalStateException("Ya hay una revisión de la biblioteca en curso");
        Long uid = CurrentUser.id();
        var u = usuarios.findById(uid).orElse(null);
        String token = u == null ? null : jwt.generateToken(u.getId(), u.getUsername(), u.getRol().name());
        DiagnosticoEjecucion e = new DiagnosticoEjecucion();
        e.setTipo("biblioteca");
        e.setInicio(LocalDateTime.now());
        e.setUsuario(u == null ? "#" + uid : u.getUsername());
        e.setEstado("EN_CURSO");
        DiagnosticoEjecucion guardada = repo.save(e);
        progreso.clear();
        progreso.put("id", guardada.getId());
        progreso.put("fase", "Preparando");
        progreso.put("pct", 0);
        auditoria.registrar("biblioteca.revisar", "Revisión de la biblioteca", "OK");
        Thread.ofVirtual().name("soporte-biblioteca").start(() -> ejecutar(guardada.getId(), token));
        return Map.of("id", guardada.getId());
    }

    public Map<String, Object> progreso() {
        Map<String, Object> r = new LinkedHashMap<>(progreso);
        r.put("enCurso", ocupado.get());
        return r;
    }

    public Optional<Map<String, Object>> ultima() {
        return repo.findFirstByTipoOrderByInicioDesc("biblioteca").map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("inicio", e.getInicio());
            m.put("fin", e.getFin());
            m.put("estado", e.getEstado());
            m.put("usuario", e.getUsuario());
            try { m.put("resultado", e.getResultados() == null ? null : json.readValue(e.getResultados(), Map.class)); }
            catch (Exception ex) { m.put("resultado", null); }
            return m;
        });
    }

    private void ejecutar(Long id, String token) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            fase("Revisando archivos de la base de datos", 5);
            List<Object[]> rutas = canciones.findRutas();
            Set<String> enBd = new HashSet<>();
            List<Map<String, Object>> sinArchivo = new ArrayList<>();
            int i = 0;
            for (Object[] f : rutas) {
                String ruta = String.valueOf(f[1]);
                enBd.add(new File(ruta).getAbsolutePath());
                if (!new File(ruta).isFile() && sinArchivo.size() < MAX_LISTA) {
                    CancionDTO c = biblioteca.porId(((Number) f[0]).longValue()).orElse(null);
                    sinArchivo.add(fila(f[0], c, ruta));
                }
                if (++i % 200 == 0) fase("Revisando archivos de la base de datos", 5 + 30 * i / Math.max(1, rutas.size()));
            }
            long totalSinArchivo = rutas.stream().filter(f -> !new File(String.valueOf(f[1])).isFile()).count();
            r.put("totalCanciones", rutas.size());
            r.put("sinArchivo", Map.of("total", totalSinArchivo, "lista", sinArchivo));

            fase("Buscando archivos sin registro", 40);
            List<String> sinRegistro = new ArrayList<>();
            long totalSinRegistro = 0;
            File raiz = escaner.raiz();
            if (raiz.isDirectory()) {
                try (Stream<Path> s = Files.walk(raiz.toPath(), 12)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (!Files.isRegularFile(p)) continue;
                        String n = p.getFileName().toString();
                        int punto = n.lastIndexOf('.');
                        if (punto < 0 || !EscanerService.EXTENSIONES.contains(n.substring(punto + 1).toLowerCase(Locale.ROOT))) continue;
                        if (!enBd.contains(p.toFile().getAbsolutePath())) {
                            totalSinRegistro++;
                            if (sinRegistro.size() < MAX_LISTA) sinRegistro.add(raiz.toPath().relativize(p).toString());
                        }
                    }
                } catch (IOException e) {
                    r.put("errorRecorrido", Sanitizador.limpiar(e.getMessage(), 200));
                }
            }
            r.put("sinRegistro", Map.of("total", totalSinRegistro, "lista", sinRegistro,
                    "nota", "Incluye las canciones repetidas que el escáner omite a propósito"));

            fase("Revisando datos de las canciones", 65);
            List<CancionDTO> todas = biblioteca.todas();
            List<Map<String, Object>> incompletos = new ArrayList<>(), sinPortada = new ArrayList<>(), sinLetra = new ArrayList<>(), raras = new ArrayList<>();
            long nIncompletos = 0, nSinPortada = 0, nSinLetra = 0, nRaras = 0, nSinGenero = 0;
            Map<String, List<CancionDTO>> porClave = new HashMap<>();
            for (CancionDTO c : todas) {
                boolean incompleto = c.getNombre() == null || c.getNombre().isBlank() || c.getArtista() == null
                        || c.getArtista().isBlank() || "Desconocido".equalsIgnoreCase(c.getArtista());
                if (incompleto) { nIncompletos++; if (incompletos.size() < MAX_LISTA) incompletos.add(fila(c.getId(), c, null)); }
                if (!c.isTienePortada()) { nSinPortada++; if (sinPortada.size() < MAX_LISTA) sinPortada.add(fila(c.getId(), c, null)); }
                if (!c.isTieneLetra()) { nSinLetra++; if (sinLetra.size() < MAX_LISTA) sinLetra.add(fila(c.getId(), c, null)); }
                if (c.getGenero() == null || c.getGenero().isBlank() || "Desconocido".equalsIgnoreCase(c.getGenero())) nSinGenero++;
                String motivo = c.getDuracionSegundos() <= 0 ? "duración 0" : c.getDuracionSegundos() > 3 * 3600 ? "dura más de 3 horas" : null;
                if (motivo != null) { nRaras++; if (raras.size() < MAX_LISTA) { Map<String, Object> f = fila(c.getId(), c, null); f.put("motivo", motivo); raras.add(f); } }
                String clave = normalizar(c.getNombre()) + "|" + normalizar(String.valueOf(c.getArtista()).split("(?i)\\s*(,|&| feat\\.? | ft\\.? )\\s*")[0]);
                porClave.computeIfAbsent(clave, k -> new ArrayList<>()).add(c);
            }
            List<Map<String, Object>> duplicados = new ArrayList<>();
            long nDup = 0;
            for (List<CancionDTO> g : porClave.values()) {
                if (g.size() < 2) continue;
                // Solo cuentan como repetidas si además duran casi lo mismo
                boolean parecidas = g.stream().mapToInt(CancionDTO::getDuracionSegundos).max().orElse(0)
                        - g.stream().mapToInt(CancionDTO::getDuracionSegundos).min().orElse(0) <= 4;
                if (!parecidas) continue;
                nDup++;
                if (duplicados.size() < MAX_LISTA) duplicados.add(Map.of("nombre", g.get(0).getNombre(), "artista", g.get(0).getArtista(),
                        "ids", g.stream().map(CancionDTO::getId).toList()));
            }
            r.put("metadatosIncompletos", Map.of("total", nIncompletos, "lista", incompletos));
            r.put("sinPortada", Map.of("total", nSinPortada, "lista", sinPortada));
            r.put("sinLetra", Map.of("total", nSinLetra, "lista", sinLetra));
            r.put("sinGenero", Map.of("total", nSinGenero));
            r.put("duracionesRaras", Map.of("total", nRaras, "lista", raras));
            r.put("duplicados", Map.of("total", nDup, "lista", duplicados));

            fase("Probando la reproducción de una muestra", 85);
            List<CancionDTO> muestra = new ArrayList<>(todas);
            Collections.shuffle(muestra);
            muestra = muestra.subList(0, Math.min(20, muestra.size()));
            Map<Integer, Integer> estados = new TreeMap<>();
            List<Map<String, Object>> fallos = new ArrayList<>();
            if (token != null) {
                String puerto = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
                for (CancionDTO c : muestra) {
                    int st;
                    try {
                        st = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/api/canciones/" + c.getId() + "/audio"))
                                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token).header("Range", "bytes=0-1023")
                                .header("User-Agent", "SpaceMusic-diagnostico").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                    } catch (Exception e) {
                        st = 0;
                    }
                    estados.merge(st, 1, Integer::sum);
                    if (st != 206 && st != 200) { Map<String, Object> f = fila(c.getId(), c, null); f.put("estado", st == 0 ? "sin respuesta" : st); fallos.add(f); }
                }
            }
            r.put("reproduccion", Map.of("probadas", muestra.size(), "estados", estados, "fallos", fallos,
                    "nota", token == null ? "No disponible: no se pudo crear la sesión de prueba" : "Se piden solo los primeros 1024 bytes de cada canción"));
            r.put("escaner", escaner.estado());

            DiagnosticoEjecucion e = repo.findById(id).orElseThrow();
            e.setFin(LocalDateTime.now());
            e.setEstado("TERMINADO");
            long problemas = totalSinArchivo + fallos.size();
            e.setFallidas((int) problemas);
            e.setAdvertencias((int) (nIncompletos + nDup + nRaras + totalSinRegistro));
            e.setResultados(json.writeValueAsString(r));
            repo.save(e);
            fase("Terminado", 100);
        } catch (Exception ex) {
            repo.findById(id).ifPresent(e -> { e.setFin(LocalDateTime.now()); e.setEstado("FALLIDO");
                e.setResultados("{\"error\":\"" + Sanitizador.limpiar(String.valueOf(ex.getMessage()), 200).replace("\"", "'") + "\"}"); repo.save(e); });
            fase("Falló: " + Sanitizador.limpiar(String.valueOf(ex.getMessage()), 120), 100);
        } finally {
            ocupado.set(false);
        }
    }

    private void fase(String f, int pct) {
        progreso.put("fase", f);
        progreso.put("pct", Math.min(100, pct));
    }

    private static Map<String, Object> fila(Object id, CancionDTO c, String ruta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (c != null) { m.put("nombre", c.getNombre()); m.put("artista", c.getArtista()); }
        if (ruta != null) m.put("archivo", new File(ruta).getName());
        return m;
    }
}
