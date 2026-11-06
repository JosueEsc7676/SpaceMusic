package com.bpm.music.services;

import com.bpm.music.models.Notificacion;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.NotificacionRepository;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Crea las notificaciones (la campana) y las entrega al instante.
 * Tipos: ACTUALIZACION (novedades de SpaceMusic), CANCIONES (música nueva en el servidor),
 * PLAYLIST (alguien que sigues publicó una playlist), SEGUIDOR, AMIGO, COLABORADOR.
 * Quedan guardadas hasta que el usuario las borre.
 */
@Service
public class NotificacionesService {

    private static final Logger log = LoggerFactory.getLogger(NotificacionesService.class);

    private final NotificacionRepository repo;
    private final UsuarioRepository usuarios;
    private final SeguimientoRepository seguimientos;
    private final EventosService eventos;

    public NotificacionesService(NotificacionRepository repo, UsuarioRepository usuarios,
                                 SeguimientoRepository seguimientos, EventosService eventos) {
        this.repo = repo;
        this.usuarios = usuarios;
        this.seguimientos = seguimientos;
        this.eventos = eventos;
    }

    public void crear(Long para, String tipo, Long de, String texto, String ruta) {
        repo.save(Notificacion.builder().usuarioId(para).tipo(tipo).deId(de)
                .texto(texto.length() > 300 ? texto.substring(0, 297) + "…" : texto).ruta(ruta).build());
        eventos.enviar(para, "notificacion", Map.of("tipo", tipo));
    }

    private List<Usuario> activos() {
        List<Usuario> r = new ArrayList<>();
        for (Usuario u : usuarios.findAll()) {
            if (u.isActivo() && u.getEstadoCuenta() == Usuario.EstadoCuenta.APROBADA) r.add(u);
        }
        return r;
    }

    /** Canciones nuevas en el servidor (una sola notificación por escaneo). */
    @Async
    public void cancionesNuevas(int cantidad, List<String> artistas) {
        if (cantidad <= 0) return;
        String quienes = artistas.isEmpty() ? "" : ": " + String.join(", ", artistas.stream().limit(3).toList())
                + (artistas.size() > 3 ? " y más" : "");
        String texto = (cantidad == 1 ? "Se agregó 1 canción nueva" : "Se agregaron " + cantidad + " canciones nuevas") + quienes;
        for (Usuario u : activos()) crear(u.getId(), "CANCIONES", null, texto, "#/");
    }

    /** Alguien publicó una playlist: se avisa a quienes lo siguen. */
    @Async
    public void playlistPublicada(Long duenoId, Long playlistId, String nombre) {
        usuarios.findById(duenoId).ifPresent(d -> {
            for (Long seguidor : seguimientos.seguidores(duenoId)) {
                if (repo.existsByUsuarioIdAndTipoAndRuta(seguidor, "PLAYLIST", "#/playlist/" + playlistId)) continue;
                crear(seguidor, "PLAYLIST", duenoId, d.getNombreVisible() + " publicó la playlist “" + nombre + "”", "#/playlist/" + playlistId);
            }
        });
    }

    /**
     * Al arrancar el servidor: si hay una versión nueva de SpaceMusic (app/novedades.json),
     * cada usuario recibe una notificación con sus novedades (una sola vez por versión).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void avisarActualizacion() {
        try {
            JsonNode n = new ObjectMapper().readTree(new ClassPathResource("static/app/novedades.json").getInputStream());
            String version = n.path("version").asText("");
            if (version.isEmpty()) return;
            String ruta = "#/novedades/" + version;
            String texto = "SpaceMusic se actualizó (" + version + "): " + n.path("titulo").asText("hay novedades")
                    .replaceFirst("^Novedades:\\s*", "") + ". Toca para ver qué hay de nuevo.";
            int creadas = 0;
            for (Usuario u : activos()) {
                if (repo.existsByUsuarioIdAndTipoAndRuta(u.getId(), "ACTUALIZACION", ruta)) continue;
                crear(u.getId(), "ACTUALIZACION", null, texto, ruta);
                creadas++;
            }
            if (creadas > 0) log.info("Notificación de la versión {} enviada a {} usuarios", version, creadas);
        } catch (Exception e) {
            log.debug("Sin novedades: {}", e.getMessage());
        }
    }
}
