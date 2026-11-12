package com.bpm.music.experiencias;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.organizador.AudioAnalisisService;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.EstilosService;
import com.bpm.music.services.RecomendacionService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Rutas musicales: "llévame de aquí hasta allá". La persona elige cuánto dura, cómo empieza y adónde quiere
 * llegar (más calma o más energía). Se arma una secuencia gradual con canciones del servidor usando la
 * energía CALCULADA del sonido (ffmpeg), no emociones detectadas. Se puede ajustar sobre la marcha.
 */
@Service
public class RutasService {

    public static final Map<String, Double> NIVELES = Map.of("calma", 0.22, "media", 0.5, "energia", 0.82);

    private final BibliotecaService biblioteca;
    private final AudioAnalisisService audio;
    private final EstilosService estilos;
    private final RecomendacionService recomendaciones;

    public RutasService(BibliotecaService biblioteca, AudioAnalisisService audio, EstilosService estilos, RecomendacionService recomendaciones) {
        this.biblioteca = biblioteca;
        this.audio = audio;
        this.estilos = estilos;
        this.recomendaciones = recomendaciones;
    }

    public record Pedido(int minutos, String desde, String hasta, Long inicioId, Long finId, double sorpresa) {}

    /**
     * Arma la ruta. Si se pasan {@code yaElegidas} (al ajustar), se conservan y se sigue desde la última,
     * con el nuevo destino.
     */
    public Map<String, Object> armar(Long uid, Pedido p, List<Long> yaElegidas, String ajuste) {
        int minutos = Math.max(10, Math.min(p.minutos(), 120));
        Map<Long, Double> energia = audio.energias();
        var permitidas = recomendaciones.permitidas(uid);
        List<CancionDTO> pool = biblioteca.todas().stream()
                .filter(c -> energia.containsKey(c.getId()) && c.getDuracionSegundos() >= 60 && permitidas.test(c)).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        if (pool.size() < 15) {
            r.put("disponible", false);
            r.put("nota", "Todavía se está analizando el sonido de la biblioteca (" + energia.size() + " de " + biblioteca.total()
                    + " canciones). Las rutas estarán listas cuando haya más analizadas.");
            return r;
        }
        double e0 = NIVELES.getOrDefault(p.desde(), 0.5), e1 = NIVELES.getOrDefault(p.hasta(), 0.5);
        double sorpresa = Math.max(0, Math.min(1, p.sorpresa()));
        Map<String, Double> gusto = recomendaciones.pesosEstilo(uid);
        double gustoMax = gusto.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);

        List<CancionDTO> ruta = new ArrayList<>();
        Set<Long> usadas = new HashSet<>();
        if (yaElegidas != null) for (Long id : yaElegidas) biblioteca.porId(id).ifPresent(c -> { ruta.add(c); usadas.add(c.getId()); });
        int segundosTotal = minutos * 60;
        int transcurrido = ruta.stream().mapToInt(CancionDTO::getDuracionSegundos).sum();
        // Al ajustar, el resto del camino sale desde la energía actual hacia el nuevo destino
        double desde = ruta.isEmpty() ? e0 : energia.getOrDefault(ruta.get(ruta.size() - 1).getId(), e0);
        int inicioTramo = transcurrido;
        if ("mantener".equals(ajuste)) e1 = desde;
        if ("calma".equals(ajuste)) e1 = Math.max(0.1, desde - 0.25);
        if ("energia".equals(ajuste)) e1 = Math.min(0.95, desde + 0.25);
        if ("sorpresa".equals(ajuste)) sorpresa = Math.min(1, sorpresa + 0.4);

        CancionDTO fin = p.finId() == null ? null : biblioteca.porId(p.finId()).orElse(null);
        if (ruta.isEmpty() && p.inicioId() != null) biblioteca.porId(p.inicioId()).ifPresent(c -> { ruta.add(c); usadas.add(c.getId()); });
        if (fin != null) { usadas.add(fin.getId()); segundosTotal -= fin.getDuracionSegundos(); e1 = energia.getOrDefault(fin.getId(), e1); }
        transcurrido = ruta.stream().mapToInt(CancionDTO::getDuracionSegundos).sum();
        Random rnd = new Random();
        List<Double> curva = new ArrayList<>();
        while (transcurrido < segundosTotal - 45 && ruta.size() < 60) {
            double avance = (double) (transcurrido - inicioTramo) / Math.max(1, segundosTotal - inicioTramo);
            double objetivo = desde + (e1 - desde) * Math.min(1, avance + 0.08);
            CancionDTO prev = ruta.isEmpty() ? null : ruta.get(ruta.size() - 1);
            CancionDTO mejor = null;
            double mejorPuntos = Double.NEGATIVE_INFINITY;
            for (CancionDTO c : pool) {
                if (usadas.contains(c.getId())) continue;
                double e = energia.get(c.getId());
                double puntos = -6 * Math.abs(e - objetivo);                                   // seguir la curva
                if (prev != null) puntos += 0.6 * estilos.transicion(prev, c);                 // transiciones suaves, sin repetir artista
                puntos += (1 - sorpresa) * 1.5 * gusto.getOrDefault(estilos.de(c).clave(), 0.0) / gustoMax; // sus estilos
                puntos += rnd.nextDouble() * (0.6 + 2.5 * sorpresa);                           // variedad
                if (puntos > mejorPuntos) { mejorPuntos = puntos; mejor = c; }
            }
            if (mejor == null) break;
            ruta.add(mejor);
            usadas.add(mejor.getId());
            curva.add(Math.round(objetivo * 100) / 100.0);
            transcurrido += mejor.getDuracionSegundos();
        }
        if (fin != null) ruta.add(fin);
        r.put("disponible", true);
        r.put("canciones", ruta);
        r.put("energias", ruta.stream().map(c -> energia.get(c.getId())).toList());
        r.put("minutos", Math.round(ruta.stream().mapToInt(CancionDTO::getDuracionSegundos).sum() / 60.0));
        r.put("explicacion", "Empieza " + nombre(desde) + " y va " + (e1 > desde + 0.05 ? "subiendo" : e1 < desde - 0.05 ? "bajando" : "manteniéndose")
                + " hasta " + nombre(e1) + ". La energía de cada canción se calcula de su sonido (volumen y dinámica); es una estimación.");
        return r;
    }

    private static String nombre(double e) {
        return e < 0.35 ? "tranquilo" : e < 0.65 ? "con energía media" : "con mucha energía";
    }
}
