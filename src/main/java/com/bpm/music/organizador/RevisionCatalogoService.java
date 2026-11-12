package com.bpm.music.organizador;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.externo.Coincidencias;
import com.bpm.music.services.BibliotecaService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Revisión del catálogo con reglas (sin IA): posibles duplicados y títulos con "basura" de video
 * ("(Official Video)", "[Letra]", "(Audio)"…). Solo PROPONE: nunca borra, mueve ni renombra archivos.
 */
@Service
public class RevisionCatalogoService {

    static final Pattern BASURA = Pattern.compile("(?i)\\s*[\\(\\[](official\\s*(music\\s*)?(video|audio|visualizer|lyric video)|video\\s*oficial|audio\\s*oficial|"
            + "lyrics?|letra|con letra|audio|video|visualizer|hd|hq|4k)[\\)\\]]");

    private final BibliotecaService biblioteca;
    private final CambioMetadatoRepository cambios;
    private final RegistroMetadatos registro;

    public RevisionCatalogoService(BibliotecaService biblioteca, CambioMetadatoRepository cambios, RegistroMetadatos registro) {
        this.biblioteca = biblioteca;
        this.cambios = cambios;
        this.registro = registro;
    }

    /** Título sin la basura de video, o null si no hay nada que limpiar. */
    static String limpio(String titulo) {
        if (titulo == null) return null;
        String t = BASURA.matcher(titulo).replaceAll("").replaceAll("\\s{2,}", " ").trim();
        return t.isEmpty() || t.equals(titulo) ? null : t;
    }

    /** Recorre toda la biblioteca y anota propuestas nuevas. Devuelve cuántas propuso. */
    public int revisar() {
        List<CancionDTO> todas = biblioteca.todas();
        Set<String> ya = new HashSet<>();
        cambios.findAll().forEach(c -> { if (c.getCampo().equals("duplicado") || c.getCampo().equals("nombre")) ya.add(c.getCampo() + "|" + c.getCancionId()); });
        int propuestas = 0;
        // Títulos con basura de video
        for (CancionDTO c : todas) {
            String nuevo = limpio(c.getNombre());
            if (nuevo == null || ya.contains("nombre|" + c.getId()) || registro.esManual(c.getId(), "nombre")) continue;
            proponer(c.getId(), "nombre", c.getNombre(), nuevo, "regla: quitar etiquetas de video del título", c.getArtista() + " – " + c.getNombre());
            propuestas++;
        }
        // Posibles duplicados: mismo título base, artista principal y versión, y duración a ±3 s
        Map<String, List<CancionDTO>> grupos = new HashMap<>();
        for (CancionDTO c : todas) {
            String k = Coincidencias.base(c.getNombre()) + "|" + BibliotecaService.normalizar(GeneroServiceAcceso.principal(c.getArtista())) + "|" + Coincidencias.versiones(c.getNombre());
            grupos.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
        }
        for (List<CancionDTO> g : grupos.values()) {
            if (g.size() < 2) continue;
            g.sort(Comparator.comparingLong(CancionDTO::getId));
            CancionDTO primera = g.get(0);
            for (CancionDTO otra : g.subList(1, g.size())) {
                if (Math.abs(otra.getDuracionSegundos() - primera.getDuracionSegundos()) > 3 || ya.contains("duplicado|" + otra.getId())) continue;
                proponer(otra.getId(), "duplicado", null, String.valueOf(primera.getId()),
                        "regla: mismo título, artista, versión y duración (±3 s)",
                        otra.getArtista() + " – " + otra.getNombre() + " (" + otra.getAlbum() + ") parece igual a #" + primera.getId() + " (" + primera.getAlbum() + "). Revisa antes de borrar nada.");
                propuestas++;
            }
        }
        return propuestas;
    }

    private void proponer(Long id, String campo, String anterior, String nuevo, String metodo, String detalle) {
        CambioMetadato c = new CambioMetadato();
        c.setCancionId(id);
        c.setCampo(campo);
        c.setValorAnterior(anterior);
        c.setValorNuevo(nuevo);
        c.setFuente("sistema");
        c.setMetodo(metodo);
        c.setDetalle(detalle.length() > 500 ? detalle.substring(0, 500) : detalle);
        c.setEstado(CambioMetadato.Estado.PENDIENTE);
        cambios.save(c);
    }

    /** Artista principal ("Bad Bunny, ABRA" → "Bad Bunny"), igual que en GeneroService. */
    static final class GeneroServiceAcceso {
        static String principal(String artista) {
            String a = artista == null ? "" : artista;
            return a.split("(?i)\\s*(,|;|&| feat\\.? | ft\\.? | featuring | x | y | with )\\s*")[0].trim();
        }
    }
}
