package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class CancionService {

    @Autowired
    private CancionRepository repository;

    @Value("${music.base-url:http://localhost:8080}")
    private String baseUrl;

    // =============================================
    // CACHÉ EN MEMORIA — sobrevive entre requests
    // =============================================
    private volatile List<CancionDTO> cacheCompleto = null;
    private volatile long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 120_000; // 2 minutos

    // Caché de búsquedas recientes (LRU simple)
    private final Map<String, List<CancionDTO>> cacheBusquedas =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<CancionDTO>> e) {
                    return size() > 50; // máximo 50 búsquedas cacheadas
                }
            });

    // Historial de búsquedas del usuario
    private final LinkedList<String> historial = new LinkedList<>();

    // Flag para saber si FULLTEXT está disponible
    private volatile boolean fulltextDisponible = true;

    // =============================================
    // WARMUP: pre-cargar caché al arrancar
    // =============================================
    @PostConstruct
    public void warmup() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000); // esperar que BD esté lista
                listarTodos();
                System.out.println("✅ Caché de canciones calentado");
            } catch (Exception e) {
                System.err.println("⚠️ Warmup caché: " + e.getMessage());
            }
        });
    }

    // =============================================
    // LISTAR — con caché en memoria
    // =============================================
    public List<CancionDTO> listarTodos() {
        long now = System.currentTimeMillis();
        if (cacheCompleto != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cacheCompleto;
        }
        List<CancionDTO> lista = repository.findAll(PageRequest.of(0, 500)).getContent()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        cacheCompleto = lista;
        cacheTimestamp = now;
        return lista;
    }

    // =============================================
    // BÚSQUEDA ROBUSTA — fulltext con fallback a LIKE
    // =============================================
    public List<CancionDTO> buscarProfesional(String query) {
        if (query == null || query.trim().isEmpty()) {
            return listarTodos();
        }

        String q = query.trim().toLowerCase();

        // 1. Revisar caché de búsquedas
        List<CancionDTO> cached = cacheBusquedas.get(q);
        if (cached != null) {
            guardarHistorial(q);
            return cached;
        }

        List<CancionDTO> resultados;

        // 2. Intentar fulltext si está disponible
        if (fulltextDisponible) {
            try {
                // Construir query fulltext seguro:
                // - Palabras de menos de 3 chars: usar LIKE
                // - Palabras >=3 chars: añadir * para prefix match
                String ftQuery = buildFulltextQuery(q);
                resultados = repository.buscarFullText(ftQuery)
                        .stream()
                        .map(this::convertToDTO)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                // Si FULLTEXT falla (índice no existe, etc.) → marcar y usar LIKE
                System.err.println("⚠️ FULLTEXT no disponible, usando LIKE: " + e.getMessage());
                fulltextDisponible = false;
                resultados = buscarConLike(q);
            }
        } else {
            resultados = buscarConLike(q);
        }

        // 3. Si fulltext devuelve vacío, intentar LIKE como respaldo
        if (resultados.isEmpty() && fulltextDisponible) {
            resultados = buscarConLike(q);
        }

        // 4. Guardar en caché y historial
        if (!resultados.isEmpty()) {
            cacheBusquedas.put(q, resultados);
        }
        guardarHistorial(q);

        return resultados;
    }

    /**
     * Construye un query safe para FULLTEXT BOOLEAN MODE.
     * Evita errores con palabras muy cortas o caracteres especiales.
     */
    private String buildFulltextQuery(String input) {
        String[] palabras = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : palabras) {
            // Limpiar caracteres especiales de FULLTEXT
            String limpia = p.replaceAll("[+\\-><()~*\"@]+", "").trim();
            if (limpia.isEmpty()) continue;
            if (limpia.length() >= 3) {
                sb.append("+").append(limpia).append("* ");
            } else {
                // Palabras cortas: búsqueda exacta sin wildcard
                sb.append("\"").append(limpia).append("\" ");
            }
        }
        String result = sb.toString().trim();
        // Si quedó vacío (todo eran chars especiales), usar el input original
        return result.isEmpty() ? input : result;
    }

    /**
     * Fallback con LIKE — siempre funciona, sin índice especial.
     */
    private List<CancionDTO> buscarConLike(String q) {
        return repository.buscarOptimizadoLike(q, PageRequest.of(0, 50))
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // =============================================
    // ARTISTA
    // =============================================
    public List<CancionDTO> getByArtista(String artista) {
        return repository.findByArtistaContainingIgnoreCase(artista)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // =============================================
    // ÁLBUM
    // =============================================
    public List<CancionDTO> getByAlbum(String album) {
        return repository.findByAlbumContainingIgnoreCase(album)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // =============================================
    // ÁLBUMES CON INFO
    // =============================================
    public List<Map<String, Object>> getAlbumesConInfo() {
        List<Object[]> data = repository.findAlbumesConId();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : data) {
            String album  = (String) row[0];
            Number count  = (Number) row[1];
            Number id     = (Number) row[2];
            Map<String, Object> map = new HashMap<>();
            map.put("nombre",     album);
            map.put("conteo",     count.longValue());
            map.put("portadaUrl", baseUrl + "/api/portada/id/" + id.longValue());
            result.add(map);
        }
        return result;
    }

    // =============================================
    // FAVORITOS
    // =============================================
    public List<CancionDTO> getFavoritos() {
        return repository.findByFavoritoTrueOrderByFechaAgregadaDesc()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public CancionDTO toggleFavorito(Long id) {
        Cancion c = repository.findById(id).orElseThrow();
        c.setFavorito(!c.isFavorito());
        invalidarCache();
        return convertToDTO(repository.save(c));
    }

    // =============================================
    // TOP
    // =============================================
    public List<CancionDTO> getTopReproducidas() {
        return repository.findTop20ByOrderByReproduccionesDesc()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // =============================================
    // REPRODUCCIÓN
    // =============================================
    @Transactional
    public void incrementarReproduccion(Long id) {
        repository.incrementarReproduccion(id);
    }

    // =============================================
    // GET BY ID — con caché del listado completo
    // =============================================
    public CancionDTO getById(Long id) {
        // Intentar desde caché primero
        if (cacheCompleto != null) {
            Optional<CancionDTO> cached = cacheCompleto.stream()
                    .filter(c -> c.getId().equals(id))
                    .findFirst();
            if (cached.isPresent()) return cached.get();
        }
        return repository.findById(id)
                .map(this::convertToDTO)
                .orElseThrow();
    }

    public Cancion getCancionEntity(Long id) {
        return repository.findById(id).orElseThrow();
    }

    // =============================================
    // HISTORIAL
    // =============================================
    private void guardarHistorial(String q) {
        if (q == null || q.length() < 2) return;
        historial.remove(q);
        historial.addFirst(q);
        if (historial.size() > 10) historial.removeLast();
    }

    public List<String> obtenerHistorial() {
        return new ArrayList<>(historial);
    }

    // =============================================
    // INVALIDAR CACHÉ
    // =============================================
    public void invalidarCache() {
        cacheCompleto = null;
        cacheTimestamp = 0;
        cacheBusquedas.clear();
    }

    // =============================================
    // PORTADAS
    // =============================================
    private String getPortadaUrl(Cancion c) {
        // ✅ Rutas relativas — funcionan desde localhost,
        //    desde la IP de red y desde cualquier dispositivo
        if (c.getPortada() != null && c.getPortada().length > 0) {
            return "/api/portada/db/" + c.getId();
        }
        if (c.getPortadaUrl() != null && c.getPortadaUrl().startsWith("http")) {
            // Portada externa (Deezer, etc.) — mantener URL absoluta
            return c.getPortadaUrl();
        }
        return "/api/portada/id/" + c.getId();
    }

    // =============================================
    // DTO
    // =============================================
    public CancionDTO convertToDTO(Cancion c) {
        return new CancionDTO(
                c.getId(),
                c.getNombre(),
                c.getArtista(),
                (c.getAlbum() == null || c.getAlbum().isEmpty()) ? "Single" : c.getAlbum(),
                getPortadaUrl(c),
                c.isFavorito(),
                c.getReproducciones(),
                c.getDuracionSegundos(),
                "/api/canciones/" + c.getId() + "/audio",   // ✅ relativa
                c.getRutaArchivo(),
                c.getLetra()             != null ? c.getLetra()             : "",
                c.getLetraSincronizada() != null ? c.getLetraSincronizada() : ""
        );
    }

}
