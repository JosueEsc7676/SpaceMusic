package com.bpm.music.organizador;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.externo.Coincidencias;
import com.bpm.music.externo.Proveedores;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.ImagenService;
import com.bpm.music.services.LetrasService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Completa datos que FALTAN (nunca sobrescribe ni toca lo corregido a mano):
 *  - letra normal y sincronizada con LRCLIB;
 *  - portada con MusicBrainz + Cover Art Archive y el año del lanzamiento.
 * Solo se aplica solo lo que coincide de forma fiable; lo dudoso queda PENDIENTE para revisar en Administración.
 * Todo queda en el historial (fuente, fecha y método) y se puede revertir. No se inventa nada.
 * Respeta los límites de cada servicio (MusicBrainz: 1 consulta por segundo con identificación).
 */
@Service
public class CompletadoService {

    private static final Logger log = LoggerFactory.getLogger(CompletadoService.class);
    private static final String AGENTE = "SpaceMusic/3.4 ( https://spacemusic.tail8ccfdf.ts.net )";

    private final BibliotecaService biblioteca;
    private final CancionRepository canciones;
    private final CambioMetadatoRepository cambios;
    private final RegistroMetadatos registro;
    private final LetrasService letras;
    private final ImagenService imagenes;
    private final Proveedores proveedores;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private long ultimaMb;

    public CompletadoService(BibliotecaService biblioteca, CancionRepository canciones, CambioMetadatoRepository cambios,
                             RegistroMetadatos registro, LetrasService letras, ImagenService imagenes, Proveedores proveedores) {
        this.biblioteca = biblioteca;
        this.canciones = canciones;
        this.cambios = cambios;
        this.registro = registro;
        this.letras = letras;
        this.imagenes = imagenes;
        this.proveedores = proveedores;
        proveedores.registrar("lrclib", "LRCLIB", "Letras normales y sincronizadas (gratuito)");
        proveedores.registrar("musicbrainz", "MusicBrainz", "Datos de lanzamientos y año (gratuito, 1 consulta por segundo)");
        proveedores.registrar("caa", "Cover Art Archive", "Portadas de lanzamientos de MusicBrainz (gratuito)");
    }

    // ═══════════════════ Trabajo por lotes ═══════════════════

    /** Canciones que les falta letra o portada y que se pueden intentar ahora. */
    public List<CancionDTO> candidatas(String campo, int n) {
        Set<Long> manuales = new HashSet<>(cambios.conCambioManual(campo));
        LocalDateTime hace30 = LocalDateTime.now().minusDays(30);
        List<CancionDTO> r = new ArrayList<>();
        for (CancionDTO c : biblioteca.todas()) {
            if (r.size() >= n) break;
            boolean falta = campo.equals("letra") ? !c.isTieneLetra() : !c.isTienePortada();
            if (!falta || manuales.contains(c.getId())) continue;
            if (cambios.existsByCancionIdAndCampoAndEstado(c.getId(), campo, CambioMetadato.Estado.PENDIENTE)) continue;
            if (cambios.sinResultadoReciente(c.getId(), campo, hace30)) continue; // caché de "no se encontró"
            r.add(c);
        }
        return r;
    }

    public int letrasLote(int n, BooleanSupplier seguir) {
        int hechas = 0;
        for (CancionDTO c : candidatas("letra", n)) {
            if (!seguir.getAsBoolean() || !proveedores.permitido("lrclib")) break;
            try {
                completarLetra(c);
                proveedores.exito("lrclib");
            } catch (Exception e) {
                proveedores.fallo("lrclib", e.getMessage());
                break;
            }
            hechas++;
            dormir(400);
        }
        if (hechas > 0) biblioteca.invalidar();
        return hechas;
    }

    void completarLetra(CancionDTO c) throws Exception {
        LetrasService.PropuestaLetra p = letras.propuesta(c);
        if (p == null) { anotar(c.getId(), "letra", null, null, "LRCLIB", "sin coincidencia", null, CambioMetadato.Estado.SIN_RESULTADO); return; }
        if (p.instrumental()) { anotar(c.getId(), "letra", null, null, "LRCLIB", p.metodo(), "LRCLIB la marca como instrumental", CambioMetadato.Estado.SIN_RESULTADO); return; }
        String nuevo = p.lrc().isBlank() ? p.plano() : p.lrc();
        String detalle = (p.lrc().isBlank() ? "Letra normal" : "Letra sincronizada") + " · " + c.getArtista() + " – " + c.getNombre();
        // Exacta o casi exacta (±2 s): se aplica. Más diferencia: a revisión. Más de 10 s: no se usa.
        if (p.diferencia() <= 2) {
            CambioMetadato cm = anotar(c.getId(), "letra", "", nuevo, "LRCLIB", p.metodo(), detalle, CambioMetadato.Estado.PENDIENTE);
            aplicar(cm, "sistema");
        } else if (p.diferencia() <= 10) {
            anotar(c.getId(), "letra", "", nuevo, "LRCLIB", p.metodo(), detalle + " · revisar: la duración difiere " + p.diferencia() + " s", CambioMetadato.Estado.PENDIENTE);
        } else {
            anotar(c.getId(), "letra", null, null, "LRCLIB", p.metodo(), "Duración muy distinta: descartada", CambioMetadato.Estado.SIN_RESULTADO);
        }
    }

    public int portadasLote(int n, BooleanSupplier seguir) {
        int hechas = 0;
        for (CancionDTO c : candidatas("portada", n)) {
            if (!seguir.getAsBoolean() || !proveedores.permitido("musicbrainz")) break;
            try {
                completarPortada(c);
            } catch (Exception e) {
                log.debug("Portada de {}: {}", c.getId(), e.getMessage());
                break; // proveedor con problemas: se sigue en la próxima ejecución
            }
            hechas++;
        }
        if (hechas > 0) biblioteca.invalidar();
        return hechas;
    }

    void completarPortada(CancionDTO c) throws Exception {
        String q = "recording:\"" + comillas(Coincidencias.base(c.getNombre())) + "\" AND artist:\"" + comillas(principal(c.getArtista())) + "\"";
        JsonNode r = mb("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=10&query=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        String albumLocal = c.getAlbum() == null || "single".equalsIgnoreCase(c.getAlbum()) ? null : Coincidencias.base(c.getAlbum());
        String mejorRelease = null, metodo = null, anio = null;
        boolean fiable = false;
        for (JsonNode rec : r.path("recordings")) {
            List<String> arts = new ArrayList<>();
            rec.path("artist-credit").forEach(a -> arts.add(a.path("name").asText()));
            Integer dur = rec.path("length").isNumber() ? rec.path("length").asInt() / 1000 : null;
            if (Coincidencias.comparar(rec.path("title").asText(), arts, dur, c) != Coincidencias.Resultado.MISMA) continue;
            for (JsonNode rel : rec.path("releases")) {
                String titulo = Coincidencias.base(rel.path("title").asText());
                boolean mismoAlbum = albumLocal != null && titulo.equals(albumLocal);
                if (mismoAlbum || mejorRelease == null) {
                    mejorRelease = rel.path("id").asText();
                    String fecha = rel.path("date").asText("");
                    anio = fecha.length() >= 4 ? fecha.substring(0, 4) : null;
                    metodo = "MusicBrainz: artista + título + duración (±4 s)" + (mismoAlbum ? " + álbum" : "") + " · lanzamiento " + mejorRelease;
                    fiable = mismoAlbum;
                    if (mismoAlbum) break;
                }
            }
            if (fiable) break;
        }
        if (mejorRelease == null || !mejorRelease.matches("[0-9a-f-]{36}")) {
            anotar(c.getId(), "portada", null, null, "MusicBrainz", "sin coincidencia fiable", null, CambioMetadato.Estado.SIN_RESULTADO);
            return;
        }
        byte[] img = caa(mejorRelease);
        if (img == null) {
            anotar(c.getId(), "portada", null, null, "Cover Art Archive", metodo, "El lanzamiento no tiene portada", CambioMetadato.Estado.SIN_RESULTADO);
            return;
        }
        // La imagen propuesta se guarda aparte hasta aplicarla
        CambioMetadato cm = anotar(c.getId(), "portada", "", null, "Cover Art Archive", metodo,
                c.getArtista() + " – " + c.getNombre() + (fiable ? "" : " · revisar: el álbum no coincide o no se conoce"), CambioMetadato.Estado.PENDIENTE);
        Path archivo = imagenes.carpeta("propuestas").resolve(cm.getId() + ".jpg");
        Files.write(archivo, img);
        cm.setValorNuevo("propuestas/" + cm.getId() + ".jpg");
        cambios.save(cm);
        if (fiable) aplicar(cm, "sistema");
        // Año: solo si falta y la coincidencia es fiable
        if (fiable && anio != null && anio.matches("\\d{4}") && c.getAnio() == null && !registro.esManual(c.getId(), "anio")) {
            CambioMetadato ca = anotar(c.getId(), "anio", "", anio, "MusicBrainz", metodo, "Año del lanzamiento", CambioMetadato.Estado.PENDIENTE);
            aplicar(ca, "sistema");
        }
    }

    private byte[] caa(String release) throws Exception {
        if (!proveedores.permitido("caa")) return null;
        HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create("https://coverartarchive.org/release/" + release + "/front-500"))
                .header("User-Agent", AGENTE).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() == 404) { proveedores.exito("caa"); return null; }
        if (res.statusCode() != 200) { proveedores.fallo("caa", "HTTP " + res.statusCode()); return null; }
        proveedores.exito("caa");
        byte[] b = res.body();
        if (b == null || b.length > 8 * 1024 * 1024) return null;
        return imagenes.redimensionar(b, 1200); // también comprueba que sea una imagen válida
    }

    private synchronized JsonNode mb(String url) throws Exception {
        long espera = 1100 - (System.currentTimeMillis() - ultimaMb);
        if (espera > 0) Thread.sleep(espera);
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", AGENTE)
                    .header("Accept", "application/json").timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 503) { proveedores.fallo("musicbrainz", "Límite de consultas (503)"); throw new IllegalStateException("MusicBrainz pidió esperar"); }
            if (res.statusCode() != 200) { proveedores.fallo("musicbrainz", "HTTP " + res.statusCode()); throw new IllegalStateException("HTTP " + res.statusCode()); }
            proveedores.exito("musicbrainz");
            return json.readTree(res.body());
        } finally {
            ultimaMb = System.currentTimeMillis();
        }
    }

    // ═══════════════════ Revisión: aplicar, rechazar, revertir ═══════════════════

    private CambioMetadato anotar(Long id, String campo, String anterior, String nuevo, String fuente, String metodo, String detalle, CambioMetadato.Estado estado) {
        CambioMetadato c = new CambioMetadato();
        c.setCancionId(id);
        c.setCampo(campo);
        c.setValorAnterior(anterior);
        c.setValorNuevo(nuevo);
        c.setFuente(fuente);
        c.setMetodo(metodo == null ? null : metodo.length() > 200 ? metodo.substring(0, 200) : metodo);
        c.setDetalle(detalle == null ? null : detalle.length() > 500 ? detalle.substring(0, 500) : detalle);
        c.setEstado(estado);
        return cambios.save(c);
    }

    /** Aplica un cambio pendiente (solo si el campo sigue vacío y nadie lo corrigió a mano). */
    public CambioMetadato aplicar(CambioMetadato c, String quien) throws Exception {
        if (c.getEstado() != CambioMetadato.Estado.PENDIENTE) throw new IllegalArgumentException("Ese cambio no está pendiente");
        CancionDTO actual = biblioteca.porId(c.getCancionId()).orElse(null);
        if (actual == null) throw new NoSuchElementException("La canción ya no existe");
        if (registro.esManual(c.getCancionId(), c.getCampo())) return cerrar(c, CambioMetadato.Estado.RECHAZADO, quien, "Tiene una corrección manual");
        switch (c.getCampo()) {
            case "letra" -> {
                if (actual.isTieneLetra()) return cerrar(c, CambioMetadato.Estado.RECHAZADO, quien, "Ya tiene letra");
                String v = Objects.toString(c.getValorNuevo(), "");
                boolean lrc = com.bpm.music.services.MetadataService.LRC_TIMESTAMP.matcher(v).find();
                canciones.actualizarLetras(c.getCancionId(), lrc ? v.replaceAll("\\[[^\\]]*]", "").replaceAll("(?m)^\\s+", "").trim() : v, lrc ? v : "");
            }
            case "portada" -> {
                if (actual.isTienePortada()) return cerrar(c, CambioMetadato.Estado.RECHAZADO, quien, "Ya tiene portada");
                Path f = archivoPropuesta(c);
                if (f == null || !Files.isRegularFile(f)) throw new IllegalStateException("La imagen propuesta ya no existe");
                byte[] img = Files.readAllBytes(f);
                String huella = ImagenService.huella(img);
                canciones.actualizarPortada(c.getCancionId(), img, huella);
                imagenes.invalidarMiniaturas(c.getCancionId());
                imagenes.miniaturaPortada(c.getCancionId(), img, 160); // miniaturas para las listas
                imagenes.miniaturaPortada(c.getCancionId(), img, 300);
            }
            case "anio" -> {
                if (actual.getAnio() != null) return cerrar(c, CambioMetadato.Estado.RECHAZADO, quien, "Ya tiene año");
                canciones.actualizarAnio(c.getCancionId(), Integer.valueOf(c.getValorNuevo()));
            }
            case "nombre" -> {
                // Solo la base de datos: el archivo no se renombra
                if (!Objects.equals(actual.getNombre(), c.getValorAnterior())) return cerrar(c, CambioMetadato.Estado.RECHAZADO, quien, "El título cambió desde la propuesta");
                canciones.actualizarNombre(c.getCancionId(), c.getValorNuevo());
            }
            case "duplicado" -> {
                // Confirmar no borra nada: borrar un duplicado sigue siendo una decisión manual
                return cerrar(c, CambioMetadato.Estado.APLICADO, quien, "Confirmado como duplicado (no se borró nada)");
            }
            default -> throw new IllegalArgumentException("Este tipo de cambio se revisa a mano");
        }
        biblioteca.invalidar();
        return cerrar(c, CambioMetadato.Estado.APLICADO, quien, null);
    }

    public CambioMetadato rechazar(CambioMetadato c, String quien) {
        if (c.getEstado() != CambioMetadato.Estado.PENDIENTE) throw new IllegalArgumentException("Ese cambio no está pendiente");
        return cerrar(c, CambioMetadato.Estado.RECHAZADO, quien, null);
    }

    /** Deshace un cambio automático aplicado: el campo vuelve a quedar vacío (como estaba). */
    public CambioMetadato revertir(CambioMetadato c, String quien) {
        if (c.getEstado() != CambioMetadato.Estado.APLICADO || "manual".equals(c.getFuente()))
            throw new IllegalArgumentException("Solo se revierten cambios automáticos aplicados");
        switch (c.getCampo()) {
            case "letra" -> canciones.actualizarLetras(c.getCancionId(), "", "");
            case "portada" -> { canciones.actualizarPortada(c.getCancionId(), null, null); imagenes.invalidarMiniaturas(c.getCancionId()); }
            case "anio" -> canciones.actualizarAnio(c.getCancionId(), null);
            case "nombre" -> canciones.actualizarNombre(c.getCancionId(), c.getValorAnterior());
            case "duplicado" -> { /* solo era una marca */ }
            default -> throw new IllegalArgumentException("Este cambio no se puede revertir automáticamente");
        }
        biblioteca.invalidar();
        return cerrar(c, CambioMetadato.Estado.REVERTIDO, quien, null);
    }

    private CambioMetadato cerrar(CambioMetadato c, CambioMetadato.Estado e, String quien, String motivo) {
        c.setEstado(e);
        c.setResueltoEn(LocalDateTime.now());
        c.setResueltoPor(quien);
        if (motivo != null) c.setDetalle(((c.getDetalle() == null ? "" : c.getDetalle() + " · ") + motivo));
        return cambios.save(c);
    }

    public Path archivoPropuesta(CambioMetadato c) {
        String v = c.getValorNuevo();
        if (v == null || !v.matches("propuestas/\\d+\\.jpg")) return null;
        return imagenes.carpeta("propuestas").resolve(v.substring("propuestas/".length()));
    }

    public Page<CambioMetadato> listar(String estado, String campo, int pagina) {
        Collection<CambioMetadato.Estado> es = estado == null || estado.isBlank()
                ? List.of(CambioMetadato.Estado.PENDIENTE, CambioMetadato.Estado.APLICADO, CambioMetadato.Estado.REVERTIDO, CambioMetadato.Estado.RECHAZADO)
                : List.of(CambioMetadato.Estado.valueOf(estado));
        PageRequest p = PageRequest.of(Math.max(0, pagina), 30);
        return campo == null || campo.isBlank() ? cambios.findByEstadoInOrderByFechaDesc(es, p) : cambios.findByEstadoInAndCampoOrderByFechaDesc(es, campo, p);
    }

    public Map<String, Object> resumen() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (CambioMetadato.Estado e : CambioMetadato.Estado.values()) m.put(e.name(), cambios.countByEstado(e));
        long sinLetra = biblioteca.todas().stream().filter(c -> !c.isTieneLetra()).count();
        long sinPortada = biblioteca.todas().stream().filter(c -> !c.isTienePortada()).count();
        m.put("sinLetra", sinLetra);
        m.put("sinPortada", sinPortada);
        return m;
    }

    private static String comillas(String s) { return s.replace("\"", " ").replace("\\", " "); }

    private static String principal(String artista) {
        return artista == null ? "" : artista.split("(?i)\\s*(,|;|&| feat\\.? | ft\\.? | featuring | x )\\s*")[0].trim();
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
