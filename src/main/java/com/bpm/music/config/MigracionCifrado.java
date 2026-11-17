package com.bpm.music.config;

import com.bpm.music.security.CifradoMensajes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Al arrancar: prepara la columna del texto de los mensajes para el cifrado (TEXT: el texto cifrado ocupa más que
 * los 1000 caracteres originales) y cifra los mensajes guardados antes, por lotes de 500. Es repetible: lo ya
 * cifrado no se toca.
 */
@Component
public class MigracionCifrado {

    private static final Logger log = LoggerFactory.getLogger(MigracionCifrado.class);
    private final JdbcTemplate jdbc;
    private final CifradoMensajes cifrado;

    public MigracionCifrado(JdbcTemplate jdbc, CifradoMensajes cifrado) {
        this.jdbc = jdbc;
        this.cifrado = cifrado;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void migrar() {
        try {
            ampliarColumna();
            int total = 0;
            for (;;) {
                List<Object[]> lote = jdbc.query("SELECT id, texto FROM mensaje WHERE texto NOT LIKE 'enc:v1:%' ORDER BY id LIMIT 500",
                        (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)});
                if (lote.isEmpty()) break;
                jdbc.batchUpdate("UPDATE mensaje SET texto = ? WHERE id = ?",
                        lote.stream().map(f -> new Object[]{cifrado.cifrar((String) f[1]), f[0]}).toList());
                total += lote.size();
                if (lote.size() < 500) break;
            }
            if (total > 0) log.info("Mensajes cifrados en la base de datos: {}", total);
        } catch (Exception e) {
            log.error("No se pudieron cifrar los mensajes existentes: {}", e.getMessage());
        }
    }

    /** MySQL: si la columna sigue siendo VARCHAR(1000), pasa a TEXT (Hibernate no cambia tipos de columnas existentes). */
    private void ampliarColumna() {
        String motor = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
        if (motor == null || !motor.toLowerCase().contains("mysql")) return;
        List<String> tipo = jdbc.queryForList("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'mensaje' AND COLUMN_NAME = 'texto'", String.class);
        if (!tipo.isEmpty() && !tipo.get(0).toLowerCase().contains("text")) {
            jdbc.execute("ALTER TABLE mensaje MODIFY texto TEXT NOT NULL");
            log.info("Columna mensaje.texto ampliada a TEXT para el cifrado");
        }
    }
}
