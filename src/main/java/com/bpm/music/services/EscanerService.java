package com.bpm.music.services;

import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Escanea la carpeta de música y mantiene la base de datos al día.
 *
 *  - Agrega canciones nuevas (MP3, FLAC, M4A, OGG, WAV, OPUS).
 *  - Si una canción cambió de ruta (p. ej. se movió la biblioteca de P:/ a
 *    /home/usuario/musica) se actualiza la ruta en lugar de duplicarla.
 *  - Corre al arrancar y cada N minutos, siempre en segundo plano.
 */
@Service
public class EscanerService {

    private static final Logger log = LoggerFactory.getLogger(EscanerService.class);

    public static final Set<String> EXTENSIONES = Set.of("mp3", "flac", "m4a", "ogg", "oga", "opus", "wav");
    private static final Set<String> CARPETAS_EXCLUIDAS = Set.of(
            "$recycle.bin", "system volume information", "lost+found", "node_modules",
            ".git", ".svn", "__macosx", ".trashes", ".spotlight-v100", ".thumbnails", "@eadir");

    private final CancionRepository repo;
    private final MetadataService metadata;
    private final BibliotecaService biblioteca;
    private final ImagenService imagenes;
    private final String rutaMusica;

    private final AtomicBoolean escaneando = new AtomicBoolean(false);
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "escaner-musica");
        t.setDaemon(true);
        return t;
    });

    // ── Estado visible desde el panel de administración ──
    private volatile LocalDateTime ultimoInicio;
    private volatile LocalDateTime ultimoFin;
    private volatile String fase = "En espera";
    private volatile int archivosEncontrados, procesados, nuevas, reubicadas, errores, duplicadas;
    /** Archivos que son la misma canción que otra ya guardada: no se vuelven a analizar. */
    private final Set<String> duplicadosConocidos = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile String ultimoError;

    /** Índice nombre-de-archivo → archivo, del último escaneo (para encontrar archivos movidos). */
    private volatile Map<String, File> indicePorNombre = Map.of();

    public EscanerService(CancionRepository repo, MetadataService metadata, BibliotecaService biblioteca,
                          ImagenService imagenes,
                          @Value("${music.path}") String rutaMusica) {
        this.repo = repo;
        this.metadata = metadata;
        this.biblioteca = biblioteca;
        this.imagenes = imagenes;
        this.rutaMusica = rutaMusica;
    }

    public File raiz() {
        return new File(rutaMusica).getAbsoluteFile();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        File r = raiz();
        if (!r.exists()) {
            log.warn("La carpeta de música no existe: {} (configura music.path)", r);
        }
        escanearEnSegundoPlano();
    }

    @Scheduled(fixedDelayString = "#{${music.scan-interval-minutes:10} * 60000}",
               initialDelayString = "#{${music.scan-interval-minutes:10} * 60000}")
    public void programado() {
        escanearEnSegundoPlano();
    }

    /** Lanza un escaneo si no hay otro en curso. Devuelve false si ya estaba escaneando. */
    public boolean escanearEnSegundoPlano() {
        if (escaneando.get()) return false;
        ejecutor.submit(this::escanear);
        return true;
    }

    public boolean estaEscaneando() {
        return escaneando.get();
    }

    public Map<String, Object> estado() {
        File r = raiz();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("escaneando", escaneando.get());
        m.put("fase", fase);
        m.put("ruta", r.getPath());
        m.put("rutaExiste", r.isDirectory());
        m.put("ultimoInicio", ultimoInicio);
        m.put("ultimoFin", ultimoFin);
        m.put("archivosEncontrados", archivosEncontrados);
        m.put("procesados", procesados);
        m.put("nuevas", nuevas);
        m.put("reubicadas", reubicadas);
        m.put("duplicadas", duplicadas);
        m.put("errores", errores);
        m.put("ultimoError", ultimoError);
        m.put("totalCanciones", biblioteca.total());
        return m;
    }

    // ═══════════════════════════ Escaneo ═══════════════════════════

    void escanear() {
        if (!escaneando.compareAndSet(false, true)) return;
        ultimoInicio = LocalDateTime.now();
        archivosEncontrados = procesados = nuevas = reubicadas = errores = duplicadas = 0;
        ultimoError = null;
        try {
            File root = raiz();
            if (!root.isDirectory()) {
                fase = "La carpeta de música no existe";
                ultimoError = "No se encuentra " + root.getPath();
                return;
            }

            fase = "Buscando archivos";
            List<File> archivos = new ArrayList<>();
            listar(root, archivos, 0);
            archivosEncontrados = archivos.size();
            Map<String, File> indice = new HashMap<>(archivos.size() * 2);
            for (File f : archivos) indice.putIfAbsent(f.getName().toLowerCase(Locale.ROOT), f);
            indicePorNombre = indice;

            fase = "Comparando con la base de datos";
            Set<String> enBd = new HashSet<>(repo.findAllRutas());
            List<File> pendientes = archivos.stream()
                    .filter(f -> !enBd.contains(f.getAbsolutePath()) && !duplicadosConocidos.contains(f.getAbsolutePath())).toList();

            if (!pendientes.isEmpty()) {
                // Canciones de la BD cuyo archivo ya no está en su ruta → candidatas a reubicar
                Map<String, Long> huerfanas = indiceHuerfanas();
                String raizAbs = root.getAbsolutePath();

                fase = "Procesando " + pendientes.size() + " archivos nuevos";
                List<Cancion> lote = new ArrayList<>();
                // Huellas de lo que ya hay (título + artista principal + duración) para no repetir canciones
                Map<String, List<Integer>> yaHay = new HashMap<>();
                for (var d : biblioteca.todas()) {
                    yaHay.computeIfAbsent(claveDuplicado(d.getNombre(), d.getArtista()), k -> new ArrayList<>()).add(d.getDuracionSegundos());
                }
                for (File f : pendientes) {
                    try {
                        String rel = relativa(raizAbs, f.getAbsolutePath());
                        Long idMovida = huerfanas.remove(rel);
                        if (idMovida != null && idMovida > 0) {
                            repo.findById(idMovida).ifPresent(c -> {
                                c.setRutaArchivo(f.getAbsolutePath());
                                repo.save(c);
                                reubicadas++;
                            });
                        } else {
                            Cancion nueva = crearCancion(f);
                            String clave = claveDuplicado(nueva.getNombre(), nueva.getArtista());
                            List<Integer> duraciones = yaHay.computeIfAbsent(clave, k -> new ArrayList<>());
                            if (duraciones.stream().anyMatch(d -> Math.abs(d - nueva.getDuracionSegundos()) <= 4)) {
                                // La misma canción ya está en la biblioteca (p. ej. descargada otra vez)
                                duplicadosConocidos.add(f.getAbsolutePath());
                                duplicadas++;
                                log.info("Canción repetida, no se agrega: {}", f.getName());
                                procesados++;
                                continue;
                            }
                            duraciones.add(nueva.getDuracionSegundos());
                            lote.add(nueva);
                            nuevas++;
                            if (lote.size() >= 25) {
                                repo.saveAll(lote);
                                lote.clear();
                                biblioteca.invalidar();
                            }
                        }
                    } catch (Exception e) {
                        errores++;
                        ultimoError = f.getName() + ": " + e.getMessage();
                        log.warn("Error procesando {}: {}", f, e.getMessage());
                    }
                    procesados++;
                }
                if (!lote.isEmpty()) repo.saveAll(lote);
                biblioteca.invalidar();
            }

            fase = "Completado";
            if (nuevas > 0 || reubicadas > 0 || duplicadas > 0) {
                log.info("Escaneo: {} archivos, {} nuevas, {} reubicadas, {} repetidas omitidas, {} errores",
                        archivosEncontrados, nuevas, reubicadas, duplicadas, errores);
            }
        } catch (Exception e) {
            fase = "Error";
            ultimoError = e.getMessage();
            log.error("Error en el escaneo de la biblioteca", e);
        } finally {
            ultimoFin = LocalDateTime.now();
            escaneando.set(false);
        }
        prepararMiniaturas();
    }

    private static final int[] TAMANOS_MINIATURA = {64, 160, 300};

    /** Genera de antemano las miniaturas que falten, para que las listas carguen rápido. */
    void prepararMiniaturas() {
        int hechas = 0;
        try {
            for (Object[] fila : repo.findIdsYHuellasConPortada()) {
                Long id = ((Number) fila[0]).longValue();
                String huella = (String) fila[1];
                boolean faltan = huella == null;
                for (int t : TAMANOS_MINIATURA) if (!faltan && !imagenes.existeMiniatura(id, huella, t)) faltan = true;
                if (!faltan) continue;
                byte[] datos = repo.findPortadaById(id).orElse(null);
                if (datos == null) continue;
                for (int t : TAMANOS_MINIATURA) imagenes.miniaturaPortada(id, datos, t);
                hechas++;
            }
        } catch (Exception e) {
            log.debug("Miniaturas: {}", e.getMessage());
        }
        if (hechas > 0) log.info("Miniaturas preparadas para {} portadas", hechas);
    }

    /** Agrega o actualiza un único archivo (usado al subir canciones). */
    public Cancion importarArchivo(File f) {
        String ruta = f.getAbsolutePath();
        Optional<Cancion> existente = repo.findFirstByRutaArchivo(ruta);
        if (existente.isPresent()) return existente.get();
        Cancion c = repo.save(crearCancion(f));
        biblioteca.invalidar();
        return c;
    }

    private Cancion crearCancion(File f) {
        MetadataService.Metadatos md = metadata.leer(f);
        String album = md.album();
        if ("Single".equals(album)) {
            // Si el archivo está en una subcarpeta propia, usarla como álbum
            File dir = f.getParentFile();
            if (dir != null && !dir.getAbsoluteFile().equals(raiz()) && !esCarpetaGenerica(dir.getName())
                    && !BibliotecaService.normalizar(dir.getName()).equals(BibliotecaService.normalizar(md.artista()))) {
                album = dir.getName();
            }
        }
        return Cancion.builder()
                .nombre(md.titulo())
                .artista(md.artista())
                .album(album)
                .genero(md.genero())
                .rutaArchivo(f.getAbsolutePath())
                .portada(md.portada())
                .portadaHash(ImagenService.huella(md.portada()))
                .portadaUrl("")
                .duracionSegundos(md.duracionSegundos())
                .numeroPista(md.numeroPista())
                .anio(md.anio())
                .bitrate(md.bitrate())
                .formato(md.formato())
                .letra(md.letra())
                .letraSincronizada(md.letraSincronizada())
                .fechaAgregada(LocalDateTime.now())
                .build();
    }

    /** Título y artista principal normalizados, sin "(feat. …)", "- Remastered", etc. */
    static String claveDuplicado(String titulo, String artista) {
        String t = titulo == null ? "" : titulo.replaceAll("(?i)\\s*[\\(\\[](feat|ft|with|con)\\.?[^\\)\\]]*[\\)\\]]", "")
                .replaceAll("(?i)\\s*-\\s*(remaster(ed)?|\\d{4} remaster(ed)?).*$", "");
        String a = artista == null ? "" : MetadataService.primerArtista(artista).split("(?i)\\s*(,|&| feat\\.? | ft\\.? | x )\\s*")[0];
        return BibliotecaService.normalizar(t) + "|" + BibliotecaService.normalizar(a);
    }

    private static boolean esCarpetaGenerica(String nombre) {
        String n = BibliotecaService.normalizar(nombre);
        return Set.of("musica", "music", "mp3", "flac", "subidas", "uploads", "descargas", "downloads",
                "singles", "single", "varios", "misc", "nueva carpeta", "new folder").contains(n);
    }

    /**
     * Canciones cuya ruta guardada ya no existe, indexadas por los finales
     * de su ruta ("artista/album/cancion.mp3", "album/cancion.mp3", ...).
     * Si un final es ambiguo se marca con -1.
     */
    private Map<String, Long> indiceHuerfanas() {
        Map<String, Long> m = new HashMap<>();
        for (Object[] fila : repo.findAllIdsYRutas()) {
            Long id = ((Number) fila[0]).longValue();
            String ruta = (String) fila[1];
            if (ruta == null || new File(ruta).exists()) continue;
            String[] seg = ruta.replace('\\', '/').toLowerCase(Locale.ROOT).split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = seg.length - 1; i >= 0 && i >= seg.length - 6; i--) {
                if (seg[i].isEmpty() || seg[i].endsWith(":")) break;
                sb.insert(0, sb.length() == 0 ? seg[i] : seg[i] + "/");
                m.merge(sb.toString(), id, (a, b) -> a.equals(b) ? a : -1L);
            }
        }
        return m;
    }

    private static String relativa(String raiz, String ruta) {
        String r = raiz.replace('\\', '/');
        String p = ruta.replace('\\', '/');
        if (!r.endsWith("/")) r = r + "/";
        if (p.toLowerCase(Locale.ROOT).startsWith(r.toLowerCase(Locale.ROOT))) p = p.substring(r.length());
        return p.toLowerCase(Locale.ROOT);
    }

    private void listar(File dir, List<File> out, int profundidad) {
        if (profundidad > 25) return;
        File[] hijos = dir.listFiles();
        if (hijos == null) return;
        for (File f : hijos) {
            String nombre = f.getName();
            if (nombre.startsWith(".") || nombre.startsWith("._") || nombre.startsWith("~")) continue;
            if (f.isDirectory()) {
                if (!CARPETAS_EXCLUIDAS.contains(nombre.toLowerCase(Locale.ROOT))) listar(f, out, profundidad + 1);
            } else if (EXTENSIONES.contains(MetadataService.extension(nombre)) && f.length() >= 32 * 1024
                    // Si se modificó hace menos de 20 s probablemente aún se está copiando/descargando
                    && System.currentTimeMillis() - f.lastModified() > 20_000) {
                out.add(f);
            }
        }
    }

    // ═══════════════════════════ Utilidades ═══════════════════════════

    /**
     * Encuentra el archivo de una canción. Si la ruta guardada no existe,
     * busca por nombre en el último escaneo (archivo movido de carpeta).
     */
    public File resolverArchivo(String rutaGuardada) {
        if (rutaGuardada == null || rutaGuardada.isBlank()) return null;
        File f = new File(rutaGuardada);
        if (f.isFile()) return f;
        File rel = new File(raiz(), rutaGuardada);
        if (rel.isFile() && dentroDeRaiz(rel)) return rel;
        String nombre = new File(rutaGuardada.replace('\\', '/')).getName().toLowerCase(Locale.ROOT);
        File porNombre = indicePorNombre.get(nombre);
        return (porNombre != null && porNombre.isFile()) ? porNombre : null;
    }

    public boolean dentroDeRaiz(File f) {
        try {
            return f.getCanonicalPath().startsWith(raiz().getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Rellena datos que faltan (pista, año, formato, bitrate, duración, portada,
     * letras) leyendo otra vez las etiquetas. No cambia títulos ni artistas.
     */
    public boolean refrescarMetadatosEnSegundoPlano() {
        return refrescarMetadatosEnSegundoPlano(false);
    }

    /**
     * @param forzarPortadas si es true, reemplaza la portada guardada por la que trae
     *                       el archivo cuando son distintas (corrige portadas equivocadas).
     */
    public boolean refrescarMetadatosEnSegundoPlano(boolean forzarPortadas) {
        if (escaneando.get()) return false;
        ejecutor.submit(() -> {
            if (!escaneando.compareAndSet(false, true)) return;
            ultimoInicio = LocalDateTime.now();
            procesados = errores = 0;
            fase = forzarPortadas ? "Corrigiendo portadas" : "Actualizando metadatos";
            try {
                List<Long> ids = repo.findAllIdsYRutas().stream().map(r -> ((Number) r[0]).longValue()).toList();
                archivosEncontrados = ids.size();
                for (Long id : ids) {
                    try {
                        repo.findById(id).ifPresent(c -> {
                            File f = resolverArchivo(c.getRutaArchivo());
                            if (f == null) return;
                            MetadataService.Metadatos md = metadata.leer(f);
                            boolean cambio = false;
                            if (c.getNumeroPista() == null && md.numeroPista() != null) { c.setNumeroPista(md.numeroPista()); cambio = true; }
                            if (c.getAnio() == null && md.anio() != null) { c.setAnio(md.anio()); cambio = true; }
                            if (c.getFormato() == null) { c.setFormato(md.formato()); cambio = true; }
                            if (c.getBitrate() == null && md.bitrate() != null) { c.setBitrate(md.bitrate()); cambio = true; }
                            if (c.getDuracionSegundos() <= 0 && md.duracionSegundos() > 0) { c.setDuracionSegundos(md.duracionSegundos()); cambio = true; }
                            if (md.portada() != null && (c.getPortada() == null || (forzarPortadas && !ImagenService.huella(md.portada()).equals(c.getPortadaHash())))) { c.ponerPortada(md.portada()); imagenes.invalidarMiniaturas(c.getId()); cambio = true; }
                            if (vacio(c.getLetraSincronizada()) && !vacio(md.letraSincronizada())) { c.setLetraSincronizada(md.letraSincronizada()); cambio = true; }
                            if (vacio(c.getLetra()) && !vacio(md.letra())) { c.setLetra(md.letra()); cambio = true; }
                            if (cambio) repo.save(c);
                        });
                    } catch (Exception e) {
                        errores++;
                        ultimoError = e.getMessage();
                    }
                    procesados++;
                }
                fase = "Completado";
            } finally {
                biblioteca.invalidar();
                ultimoFin = LocalDateTime.now();
                escaneando.set(false);
            }
        });
        return true;
    }

    /**
     * Borra de la base de datos las canciones cuyo archivo ya no existe.
     * Por seguridad no hace nada si la carpeta de música no está accesible
     * o si se borraría más de la mitad de la biblioteca (salvo forzar=true).
     */
    public Map<String, Object> limpiarHuerfanas(boolean forzar) {
        File root = raiz();
        if (!root.isDirectory() || Objects.requireNonNullElse(root.list(), new String[0]).length == 0) {
            throw new IllegalArgumentException("La carpeta de música no está accesible; no se borra nada");
        }
        if (escaneando.get()) throw new IllegalArgumentException("Espera a que termine el escaneo");
        List<Object[]> filas = repo.findAllIdsYRutas();
        List<Long> huerfanas = new ArrayList<>();
        for (Object[] f : filas) {
            if (resolverArchivo((String) f[1]) == null) huerfanas.add(((Number) f[0]).longValue());
        }
        if (!forzar && huerfanas.size() * 2 > filas.size() && filas.size() > 10) {
            throw new IllegalArgumentException("Se borrarían " + huerfanas.size() + " de " + filas.size()
                    + " canciones. Revisa la ruta de la música o confirma para forzar.");
        }
        for (int i = 0; i < huerfanas.size(); i += 500) {
            List<Long> parte = huerfanas.subList(i, Math.min(i + 500, huerfanas.size()));
            repo.borrarDePlaylists(parte);
            repo.borrarFavoritos(parte);
            repo.borrarReproducciones(parte);
            repo.borrarHistorial(parte);
            repo.borrarPorIds(parte);
        }
        biblioteca.invalidar();
        return Map.of("borradas", huerfanas.size(), "total", filas.size());
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
