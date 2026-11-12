package com.bpm.music.experiencias;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.CacheTemporal;
import com.bpm.music.services.RecomendacionService;
import com.bpm.music.services.UsuarioService;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * "Preparar escucha sin internet": la persona dice cuánto tiempo, cuánto espacio y qué parte de favoritas y de
 * descubrimientos quiere. Se propone una selección con el tamaño REAL de cada archivo, para revisarla y
 * sustituir canciones antes de descargar. La descarga la hace el sistema sin conexión de siempre.
 */
@Service
public class PrepararOfflineService {

    private final BibliotecaService biblioteca;
    private final CancionRepository canciones;
    private final UsuarioService usuarios;
    private final RecomendacionService recomendaciones;
    private final DescubrirService descubrir;
    private final CacheTemporal<Long, Long> tamanos = new CacheTemporal<>(30 * 60_000, 10_000);

    public PrepararOfflineService(BibliotecaService biblioteca, CancionRepository canciones, UsuarioService usuarios,
                                  RecomendacionService recomendaciones, DescubrirService descubrir) {
        this.biblioteca = biblioteca;
        this.canciones = canciones;
        this.usuarios = usuarios;
        this.recomendaciones = recomendaciones;
        this.descubrir = descubrir;
    }

    /** Tamaño real del archivo de audio (bytes), o 0 si no está. */
    long tamano(Long id) {
        return tamanos.obtener(id, () -> canciones.findRutaById(id).map(File::new).filter(File::isFile).map(File::length).orElse(0L));
    }

    public Map<String, Object> proponer(Long uid, int minutos, int maxMb, int porcentajeFavoritas) {
        minutos = Math.max(10, Math.min(minutos, 1200));
        maxMb = Math.max(50, Math.min(maxMb, 20_000));
        porcentajeFavoritas = Math.max(0, Math.min(porcentajeFavoritas, 100));
        long maxBytes = (long) maxMb * 1024 * 1024;
        var permitidas = recomendaciones.permitidas(uid);
        List<CancionDTO> favoritas = new ArrayList<>(usuarios.favoritos(uid));
        favoritas.addAll(usuarios.masEscuchadas(uid, 90, 80));
        Collections.shuffle(favoritas, new Random());
        List<CancionDTO> nuevas = descubrir.descubrir(uid, "cercano", 60).stream().map(m -> (CancionDTO) m.get("cancion")).toList();

        List<Map<String, Object>> elegidas = new ArrayList<>();
        Set<Long> ya = new HashSet<>();
        long bytes = 0;
        int segundos = 0, objetivo = minutos * 60;
        Iterator<CancionDTO> itF = favoritas.iterator(), itN = nuevas.iterator();
        while (segundos < objetivo && (itF.hasNext() || itN.hasNext())) {
            // Mantener la proporción pedida a medida que se llena
            long nFav = elegidas.stream().filter(m -> "favorita".equals(m.get("tipo"))).count();
            boolean tocaFavorita = elegidas.isEmpty() ? porcentajeFavoritas >= 50 : 100.0 * nFav / elegidas.size() < porcentajeFavoritas;
            Iterator<CancionDTO> it = (tocaFavorita && itF.hasNext()) || !itN.hasNext() ? itF : itN;
            CancionDTO c = it.next();
            if (!ya.add(c.getId()) || !permitidas.test(c)) continue;
            long t = tamano(c.getId());
            if (t <= 0) continue;                         // sin archivo: no se puede descargar
            if (bytes + t > maxBytes) continue;           // no cabe: probar otra más corta
            bytes += t;
            segundos += c.getDuracionSegundos();
            elegidas.add(Map.of("cancion", c, "bytes", t, "tipo", it == itF ? "favorita" : "descubrimiento"));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("canciones", elegidas);
        r.put("bytes", bytes);
        r.put("minutos", Math.round(segundos / 60.0));
        r.put("completa", segundos >= objetivo * 0.9);
        r.put("nota", segundos < objetivo * 0.9 ? (bytes >= maxBytes * 0.95 ? "No cabe más en el espacio que elegiste" : "No hay más canciones para esa combinación") : null);
        return r;
    }

    /** Otra canción del mismo tipo para sustituir una de la selección. */
    public Map<String, Object> sustituto(Long uid, String tipo, Set<Long> excluir, long maxBytes) {
        var permitidas = recomendaciones.permitidas(uid);
        List<CancionDTO> pool = new ArrayList<>("favorita".equals(tipo) ? usuarios.favoritos(uid) : descubrir.descubrir(uid, "cercano", 60).stream().map(m -> (CancionDTO) m.get("cancion")).toList());
        if ("favorita".equals(tipo)) pool.addAll(usuarios.masEscuchadas(uid, 90, 80));
        Collections.shuffle(pool);
        for (CancionDTO c : pool) {
            if (excluir.contains(c.getId()) || !permitidas.test(c)) continue;
            long t = tamano(c.getId());
            if (t <= 0 || (maxBytes > 0 && t > maxBytes)) continue;
            return Map.of("cancion", c, "bytes", t, "tipo", tipo);
        }
        throw new NoSuchElementException("No hay otra canción para sustituirla");
    }
}
