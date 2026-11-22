package com.bpm.music.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * «Hay canciones nuevas de tus artistas»: una vez al día se revisa lo que llegó al servidor en las últimas 24 horas
 * y se avisa a cada persona que tiene en Me gusta (o escuchó en los últimos 30 días) canciones de ese artista.
 * Un aviso por artista, con el nombre de la canción y cuántas más llegaron.
 */
@Service
public class NuevasCancionesService {

    private final JdbcTemplate jdbc;
    private final NotificacionesService notificaciones;

    public NuevasCancionesService(JdbcTemplate jdbc, NotificacionesService notificaciones) {
        this.jdbc = jdbc;
        this.notificaciones = notificaciones;
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "America/El_Salvador")
    public void avisar() {
        avisarDesde(LocalDateTime.now().minusHours(24));
    }

    /** Separado para probarlo: avisa de lo agregado desde {@code desde}. Devuelve cuántos avisos se enviaron. */
    int avisarDesde(LocalDateTime desde) {
        // Canciones nuevas por artista (nombre normalizado → artista tal cual y títulos)
        Map<String, String> nombre = new HashMap<>();
        Map<String, List<String>> nuevas = new LinkedHashMap<>();
        jdbc.query("SELECT artista, nombre FROM cancion WHERE fecha_agregada >= ? ORDER BY fecha_agregada DESC",
                (rs) -> {
                    String a = rs.getString(1);
                    if (a == null || a.isBlank()) return;
                    String k = a.trim().toLowerCase(Locale.ROOT);
                    nombre.putIfAbsent(k, a.trim());
                    nuevas.computeIfAbsent(k, x -> new ArrayList<>()).add(rs.getString(2));
                }, Timestamp.valueOf(desde));
        if (nuevas.isEmpty()) return 0;
        int enviados = 0;
        for (var e : nuevas.entrySet()) {
            String artista = nombre.get(e.getKey());
            // Quién sigue a ese artista: lo tiene en Me gusta o lo escuchó hace poco (sin contar las canciones nuevas)
            List<Long> personas = jdbc.queryForList(
                    "SELECT DISTINCT uid FROM (" +
                    " SELECT f.usuario_id AS uid FROM usuario_favorito f JOIN cancion c ON c.id = f.cancion_id WHERE LOWER(TRIM(c.artista)) = ? AND c.fecha_agregada < ?" +
                    " UNION SELECT r.usuario_id AS uid FROM usuario_reproduccion r JOIN cancion c ON c.id = r.cancion_id WHERE LOWER(TRIM(c.artista)) = ? AND r.fecha >= ? AND c.fecha_agregada < ?" +
                    ") t", Long.class, e.getKey(), Timestamp.valueOf(desde), e.getKey(), Timestamp.valueOf(LocalDateTime.now().minusDays(30)), Timestamp.valueOf(desde));
            List<String> titulos = e.getValue();
            String texto = "Nuevas canciones de " + artista + ": «" + titulos.get(0) + "»"
                    + (titulos.size() > 1 ? " y " + (titulos.size() - 1) + (titulos.size() == 2 ? " más" : " más") : "");
            for (Long uid : personas) {
                try {
                    notificaciones.crear(uid, "NUEVAS", null, texto, "#/artista/" + java.net.URLEncoder.encode(artista, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
                    enviados++;
                } catch (RuntimeException ignored) { /* un usuario con problema no frena a los demás */ }
            }
        }
        return enviados;
    }
}
