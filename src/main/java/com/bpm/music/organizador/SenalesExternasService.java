package com.bpm.music.organizador;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.externo.Coincidencias;
import com.bpm.music.externo.YouTubeMusicProveedor;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.services.BibliotecaService;
import com.fasterxml.jackson.databind.JsonNode;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Señales de YouTube Music para las recomendaciones, sin salir del catálogo local:
 *  1. Vincula cada canción del servidor con su id de YouTube Music. Primero por la etiqueta del archivo
 *     (las descargas guardan su enlace en "comment"); si no, buscando y comparando título, artista,
 *     versión y duración. Lo ambiguo queda pendiente de revisión y no se usa.
 *  2. Para las vinculadas, pide sus "canciones relacionadas" y guarda solo las que ya están en el servidor.
 * Nunca descarga nada. Si el proveedor falla, las recomendaciones locales siguen igual.
 */
@Service
public class SenalesExternasService {

    private static final Logger log = LoggerFactory.getLogger(SenalesExternasService.class);
    public static final String FUENTE = "ytmusic";
    private static final Pattern ID = Pattern.compile("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private final BibliotecaService biblioteca;
    private final CancionRepository canciones;
    private final CancionExternaRepository vinculos;
    private final SimilitudRepository similitudes;
    private final YouTubeMusicProveedor ytm;

    /** cancionId → (similarId → peso), en memoria; se recarga cada 10 minutos. */
    private volatile Map<Long, Map<Long, Double>> mapa = Map.of();
    private volatile long mapaT;

    public SenalesExternasService(BibliotecaService biblioteca, CancionRepository canciones, CancionExternaRepository vinculos,
                                  SimilitudRepository similitudes, YouTubeMusicProveedor ytm) {
        this.biblioteca = biblioteca;
        this.canciones = canciones;
        this.vinculos = vinculos;
        this.similitudes = similitudes;
        this.ytm = ytm;
        java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    // ═══════════════════ Para las recomendaciones ═══════════════════

    /** Qué tanto se parece B a A según YouTube Music (0 si no hay dato). Nunca llama al proveedor. */
    public double peso(Long a, Long b) {
        Map<Long, Double> m = mapa().get(a);
        if (m == null) return 0;
        return m.getOrDefault(b, 0.0);
    }

    /** Canciones del servidor parecidas a una (de la más a la menos parecida). */
    public List<Long> parecidas(Long id) {
        Map<Long, Double> m = mapa().getOrDefault(id, Map.of());
        return m.entrySet().stream().sorted(Map.Entry.<Long, Double>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
    }

    private Map<Long, Map<Long, Double>> mapa() { return mapa; }

    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 30000, fixedDelay = 60000)
    public void refrescarMapa() {
        if (System.currentTimeMillis() - mapaT > 10 * 60_000) {
            Map<Long, Map<Long, Double>> m = new HashMap<>();
            for (Similitud s : similitudes.findAll()) {
                m.computeIfAbsent(s.getCancionId(), k -> new HashMap<>()).merge(s.getSimilarId(), s.getPeso(), Math::max);
                // La relación también sirve al revés, con algo menos de peso
                m.computeIfAbsent(s.getSimilarId(), k -> new HashMap<>()).merge(s.getCancionId(), s.getPeso() * 0.7, Math::max);
            }
            mapa = m;
            mapaT = System.currentTimeMillis();
        }
    }

    public void recargar() { mapaT = 0; }

    // ═══════════════════ Trabajo en segundo plano (lo llama el organizador) ═══════════════════

    /** Vincula hasta {@code n} canciones sin vínculo. Devuelve cuántas procesó. */
    public int vincularLote(int n, BooleanSupplier seguir) {
        Set<Long> hechas = new HashSet<>(vinculos.cancionesConVinculo(FUENTE));
        int procesadas = 0;
        for (CancionDTO c : biblioteca.todas()) {
            if (procesadas >= n || !seguir.getAsBoolean()) break;
            if (hechas.contains(c.getId())) continue;
            // Si el proveedor no está disponible no se guarda nada: se para y se sigue otro día
            if (!vincular(c)) break;
            procesadas++;
        }
        if (procesadas > 0) recargar();
        return procesadas;
    }

    /** Pendientes de vincular (para el progreso). */
    public long pendientesDeVincular() {
        return Math.max(0, biblioteca.total() - vinculos.cancionesConVinculo(FUENTE).size());
    }

    /** Devuelve true si guardó una decisión (vinculada, ambigua o sin coincidencia). */
    boolean vincular(CancionDTO c) {
        CancionExterna v = vinculos.findByProveedorAndCancionId(FUENTE, c.getId()).orElseGet(CancionExterna::new);
        v.setCancionId(c.getId());
        v.setProveedor(FUENTE);
        v.setFecha(LocalDateTime.now());
        String deArchivo = idDeArchivo(c.getId());
        if (deArchivo != null) {
            v.setIdExterno(deArchivo);
            v.setEstado(CancionExterna.Estado.VERIFICADA);
            v.setMetodo("etiqueta-archivo");
            v.setDetalle("Enlace guardado en la etiqueta del archivo");
            vinculos.save(v);
            return true;
        }
        if (!ytm.disponible()) return false; // sin proveedor: se intentará otro día
        try {
            JsonNode r = ytm.buscar(c.getArtista() + " " + c.getNombre(), "songs", 5).path("resultados");
            CancionExterna mejor = null;
            for (JsonNode it : r) {
                List<String> arts = new ArrayList<>();
                it.path("artistas").forEach(a -> arts.add(a.asText()));
                Integer dur = it.path("duracion").isNumber() ? it.path("duracion").asInt() : null;
                Coincidencias.Resultado res = Coincidencias.comparar(it.path("titulo").asText(), arts, dur, c);
                if (res == Coincidencias.Resultado.MISMA) {
                    v.setIdExterno(it.path("videoId").asText());
                    v.setEstado(CancionExterna.Estado.VERIFICADA);
                    v.setMetodo("busqueda-exacta");
                    v.setDetalle(recortar(it.path("titulo").asText() + " · " + String.join(", ", arts) + " · " + dur + " s"));
                    vinculos.save(v);
                    return true;
                }
                if (res == Coincidencias.Resultado.AMBIGUA && mejor == null) {
                    mejor = v;
                    v.setIdExterno(it.path("videoId").asText());
                    v.setDetalle(recortar("Posible: " + it.path("titulo").asText() + " · " + String.join(", ", arts) + " · " + dur + " s"));
                }
            }
            v.setEstado(mejor != null ? CancionExterna.Estado.AMBIGUA : CancionExterna.Estado.SIN_COINCIDENCIA);
            v.setMetodo("busqueda");
            if (mejor == null) { v.setIdExterno(null); v.setDetalle("Sin coincidencia fiable"); }
            vinculos.save(v);
            return true;
        } catch (Exception e) {
            // Proveedor caído o en pausa: no se guarda nada y se reintenta en otra pasada
            log.debug("No se pudo vincular {}: {}", c.getId(), e.getMessage());
            return false;
        }
    }

    /** Id de YouTube que las descargas guardan en la etiqueta "comment" del archivo. */
    String idDeArchivo(Long cancionId) {
        try {
            String ruta = canciones.findRutaById(cancionId).orElse(null);
            if (ruta == null) return null;
            File f = new File(ruta);
            if (!f.isFile()) return null;
            Tag t = AudioFileIO.read(f).getTag();
            if (t == null) return null;
            Matcher m = ID.matcher(Objects.toString(t.getFirst(FieldKey.COMMENT), ""));
            return m.find() ? m.group(1) : null;
        } catch (Exception | Error e) {
            return null;
        }
    }

    /** Pide las relacionadas de hasta {@code n} canciones vinculadas (las que nunca se pidieron o hace más de 30 días). */
    public int relacionadasLote(int n, BooleanSupplier seguir) {
        if (!ytm.disponible()) return 0;
        Map<String, List<CancionDTO>> indice = new HashMap<>();
        for (CancionDTO c : biblioteca.todas()) indice.computeIfAbsent(Coincidencias.base(c.getNombre()), k -> new ArrayList<>()).add(c);
        Map<String, Long> porVideo = new HashMap<>();
        for (CancionExterna v : vinculos.findAll())
            if (v.getEstado() == CancionExterna.Estado.VERIFICADA && v.getIdExterno() != null) porVideo.put(v.getIdExterno(), v.getCancionId());
        int procesadas = 0;
        for (CancionExterna v : vinculos.pendientesDeRelacionadas(FUENTE, LocalDateTime.now().minusDays(30), PageRequest.of(0, n))) {
            if (!seguir.getAsBoolean()) break;
            try {
                JsonNode r = ytm.relacionadas(v.getIdExterno(), 25).path("resultados");
                List<Similitud> nuevas = new ArrayList<>();
                Set<Long> ya = new HashSet<>();
                int i = 0;
                for (JsonNode it : r) {
                    i++;
                    String vid = it.path("videoId").asText();
                    Long local = porVideo.get(vid);
                    if (local == null) {
                        List<String> arts = new ArrayList<>();
                        it.path("artistas").forEach(a -> arts.add(a.asText()));
                        Integer dur = it.path("duracion").isNumber() ? it.path("duracion").asInt() : null;
                        var m = Coincidencias.buscar(it.path("titulo").asText(), arts, dur,
                                indice.getOrDefault(Coincidencias.base(it.path("titulo").asText()), List.of()));
                        if (m != null && m.getValue() == Coincidencias.Resultado.MISMA) local = m.getKey().getId();
                    }
                    if (local == null || local.equals(v.getCancionId()) || !ya.add(local)) continue;
                    nuevas.add(new Similitud(v.getCancionId(), local, FUENTE, Math.max(0.2, 1 - (i - 1) / 25.0)));
                }
                similitudes.borrarDe(v.getCancionId(), FUENTE);
                similitudes.saveAll(nuevas);
                v.setRelacionadasEn(LocalDateTime.now());
                vinculos.save(v);
                procesadas++;
            } catch (IllegalArgumentException e) {
                v.setRelacionadasEn(LocalDateTime.now()); // id no válido: no insistir
                vinculos.save(v);
            } catch (Exception e) {
                log.debug("Relacionadas de {}: {}", v.getCancionId(), e.getMessage());
                break; // proveedor con problemas: se sigue en la próxima ejecución
            }
        }
        if (procesadas > 0) recargar();
        return procesadas;
    }

    public Map<String, Object> resumen() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (CancionExterna.Estado e : CancionExterna.Estado.values()) m.put(e.name(), vinculos.countByProveedorAndEstado(FUENTE, e));
        m.put("pendientes", pendientesDeVincular());
        m.put("relaciones", similitudes.count());
        return m;
    }

    private static String recortar(String s) {
        return s.length() > 290 ? s.substring(0, 290) : s;
    }
}
