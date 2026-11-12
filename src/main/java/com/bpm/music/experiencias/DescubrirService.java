package com.bpm.music.experiencias;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.organizador.SenalesExternasService;
import com.bpm.music.services.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Descubrimiento con control: Familiar → Cercano → Sorpréndeme. Solo canciones del servidor que la persona
 * no ha escuchado, cada una con una explicación breve basada en señales reales (sus artistas, sus estilos,
 * "se parece a" según YouTube Music). "Hoy no" la esconde unos días; "No me gusta este estilo" baja el estilo.
 */
@Service
public class DescubrirService {

    private final BibliotecaService biblioteca;
    private final RecomendacionService recomendaciones;
    private final UsuarioService usuarios;
    private final EstilosService estilos;
    private final SenalesExternasService senales;
    private final DescarteDescubrirRepository descartes;

    public DescubrirService(BibliotecaService biblioteca, RecomendacionService recomendaciones, UsuarioService usuarios,
                            EstilosService estilos, SenalesExternasService senales, DescarteDescubrirRepository descartes) {
        this.biblioteca = biblioteca;
        this.recomendaciones = recomendaciones;
        this.usuarios = usuarios;
        this.estilos = estilos;
        this.senales = senales;
        this.descartes = descartes;
    }

    public List<Map<String, Object>> descubrir(Long uid, String modo, int cantidad) {
        cantidad = Math.max(5, Math.min(cantidad, 60));
        Set<Long> escuchadas = recomendaciones.escuchadas(uid);
        var permitidas = recomendaciones.permitidas(uid);
        Set<Long> hoyNo = new HashSet<>();
        Set<String> estilosNo = new HashSet<>();
        for (DescarteDescubrir d : descartes.findByUsuarioIdAndHastaAfter(uid, LocalDateTime.now())) {
            if (d.getCancionId() != null) hoyNo.add(d.getCancionId());
            if (d.getEstilo() != null) estilosNo.add(d.getEstilo());
        }
        List<CancionDTO> nuevas = biblioteca.todas().stream()
                .filter(c -> !escuchadas.contains(c.getId()) && !hoyNo.contains(c.getId()) && permitidas.test(c)
                        && !estilosNo.contains(estilos.de(c).clave()))
                .toList();
        Map<String, Long> artistas = usuarios.pesosArtistas(uid);
        Map<String, Double> gustos = recomendaciones.pesosEstilo(uid);
        Set<String> favoritos = new HashSet<>(gustos.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(4).map(Map.Entry::getKey).toList());
        Set<String> familias = new HashSet<>();
        favoritos.forEach(k -> EstilosService.porClave(k).ifPresent(x -> familias.add(x.familia())));
        // Lo que más le gusta (para "se parece a…")
        List<CancionDTO> suyas = new ArrayList<>(usuarios.favoritos(uid));
        suyas.addAll(usuarios.masEscuchadas(uid, 60, 40));
        Random rnd = new Random(LocalDate.now().toEpochDay() * 17 + uid);

        List<Map<String, Object>> r = new ArrayList<>();
        Set<Long> ya = new HashSet<>();
        switch (modo == null ? "cercano" : modo) {
            case "familiar" -> {
                // Canciones nuevas de artistas que ya escucha
                List<CancionDTO> l = new ArrayList<>(nuevas.stream().filter(c -> artistas.containsKey(BibliotecaService.normalizar(c.getArtista()))).toList());
                Collections.shuffle(l, rnd);
                for (CancionDTO c : EstilosService.variadas(l, 2, cantidad)) agregar(r, ya, c, "Porque escuchas a " + c.getArtista());
            }
            case "sorpresa" -> {
                // Estilos que no suele escuchar (sin los que dijo que no le gustan)
                List<CancionDTO> l = new ArrayList<>(nuevas.stream().filter(c -> !favoritos.contains(estilos.de(c).clave())).toList());
                Collections.shuffle(l, rnd);
                for (CancionDTO c : EstilosService.variadas(l, 1, cantidad)) {
                    var e = estilos.de(c);
                    agregar(r, ya, c, familias.contains(e.familia()) ? "Algo distinto, pero cerca de lo tuyo: " + e.nombre() : "Algo distinto: " + e.nombre());
                }
            }
            default -> {
                // "Se parece a" (YouTube Music, solo canciones del servidor) y luego sus estilos con otros artistas
                Set<Long> nuevasIds = new HashSet<>();
                nuevas.forEach(c -> nuevasIds.add(c.getId()));
                Collections.shuffle(suyas, rnd);
                for (CancionDTO s : suyas) {
                    if (r.size() >= cantidad / 2) break;
                    for (Long id : senales.parecidas(s.getId())) {
                        if (!nuevasIds.contains(id) || ya.contains(id)) continue;
                        biblioteca.porId(id).ifPresent(c -> agregar(r, ya, c, "Se parece a «" + s.getNombre() + "», que te gusta"));
                        break;
                    }
                }
                List<CancionDTO> l = new ArrayList<>(nuevas.stream().filter(c -> favoritos.contains(estilos.de(c).clave())
                        && !artistas.containsKey(BibliotecaService.normalizar(c.getArtista()))).toList());
                Collections.shuffle(l, rnd);
                for (CancionDTO c : EstilosService.variadas(l, 1, cantidad)) {
                    if (r.size() >= cantidad) break;
                    agregar(r, ya, c, "Del estilo " + estilos.de(c).nombre() + " que escuchas, de otro artista");
                }
            }
        }
        // Orden que fluye, manteniendo cada explicación con su canción
        Map<Long, Map<String, Object>> porId = new LinkedHashMap<>();
        r.forEach(m -> porId.put(((CancionDTO) m.get("cancion")).getId(), m));
        List<CancionDTO> orden = estilos.enBloques(r.stream().map(m -> (CancionDTO) m.get("cancion")).toList());
        return orden.stream().map(c -> porId.get(c.getId())).limit(cantidad).toList();
    }

    private void agregar(List<Map<String, Object>> r, Set<Long> ya, CancionDTO c, String razon) {
        if (!ya.add(c.getId())) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cancion", c);
        m.put("razon", razon);
        m.put("estilo", estilos.de(c).clave());
        m.put("estiloNombre", estilos.de(c).nombre());
        r.add(m);
    }

    /** "Hoy no": se esconde esa canción 7 días. "No me gusta este estilo": el estilo baja 60 días. */
    public void descartar(Long uid, Long cancionId, String tipo) {
        CancionDTO c = biblioteca.porId(cancionId).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        DescarteDescubrir d = new DescarteDescubrir();
        d.setUsuarioId(uid);
        if ("estilo".equals(tipo)) {
            d.setEstilo(estilos.de(c).clave());
            d.setHasta(LocalDateTime.now().plusDays(60));
        } else if ("hoy".equals(tipo)) {
            d.setCancionId(cancionId);
            d.setHasta(LocalDateTime.now().plusDays(7));
        } else throw new IllegalArgumentException("Elige «hoy no» o «no me gusta este estilo»");
        descartes.save(d);
    }

    /** Estilos que la persona dijo que no le gustan (para mostrarlos y poder quitarlos). */
    public List<Map<String, Object>> estilosDescartados(Long uid) {
        List<Map<String, Object>> r = new ArrayList<>();
        for (DescarteDescubrir d : descartes.findByUsuarioIdAndHastaAfter(uid, LocalDateTime.now()))
            if (d.getEstilo() != null) r.add(Map.of("id", d.getId(), "estilo", EstilosService.porClave(d.getEstilo()).map(EstilosService.Estilo::nombre).orElse(d.getEstilo()), "hasta", d.getHasta()));
        return r;
    }

    public void quitarDescarte(Long uid, Long id) {
        DescarteDescubrir d = descartes.findById(id).filter(x -> x.getUsuarioId().equals(uid)).orElseThrow(() -> new NoSuchElementException("No encontrado"));
        descartes.delete(d);
    }
}
