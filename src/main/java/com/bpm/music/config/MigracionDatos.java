package com.bpm.music.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Arreglos de datos de la versión anterior. Es seguro ejecutarlos en cada arranque.
 *
 *  1. Playlists sin dueño (la versión anterior las creaba así al subir imagen)
 *     → se asignan al primer administrador.
 *  2. Favoritos globales antiguos → pasan a ser favoritos del primer administrador.
 *  3. Columnas nuevas con valores nulos → valor por defecto.
 */
@Component
public class MigracionDatos {

    private static final Logger log = LoggerFactory.getLogger(MigracionDatos.class);
    private final JdbcTemplate jdbc;

    public MigracionDatos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void migrar() {
        try {
            List<Long> admins = jdbc.queryForList(
                    "SELECT id FROM usuario WHERE rol = 'ADMIN' ORDER BY id", Long.class);
            if (!admins.isEmpty()) {
                Long admin = admins.get(0);
                int n = jdbc.update("UPDATE playlist SET usuario_id = ? WHERE usuario_id IS NULL", admin);
                if (n > 0) log.info("Migración: {} playlists sin dueño asignadas al administrador {}", n, admin);

                Integer favsAdmin = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM usuario_favorito WHERE usuario_id = ?", Integer.class, admin);
                if (favsAdmin != null && favsAdmin == 0) {
                    int f = jdbc.update("""
                            INSERT INTO usuario_favorito (usuario_id, cancion_id, fecha_agregado)
                            SELECT ?, c.id, CURRENT_TIMESTAMP FROM cancion c WHERE c.favorito = true""", admin);
                    if (f > 0) log.info("Migración: {} favoritos globales pasados al administrador {}", f, admin);
                }
                jdbc.update("UPDATE cancion SET favorito = false WHERE favorito = true");
            }
            jdbc.update("UPDATE playlist SET publica = false WHERE publica IS NULL");
            jdbc.update("UPDATE playlist SET portada_url = NULL WHERE portada_url LIKE '%singlecolorimage%'");
        } catch (Exception e) {
            log.warn("Migración de datos omitida: {}", e.getMessage());
        }
    }
}
