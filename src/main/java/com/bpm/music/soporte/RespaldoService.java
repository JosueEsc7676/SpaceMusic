package com.bpm.music.soporte;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * Copias de seguridad de la base de datos (cuentas, playlists, Me gusta, mensajes…).
 * Se hacen con JDBC (sin ejecutar comandos del sistema): MySQL → archivo .sql.gz con la estructura y los datos;
 * H2 → SCRIPT TO. Una diaria a las 4:30 y a pedido; se conservan las últimas "respaldo.copias".
 * La restauración no se hace desde la web (es destructiva): el panel muestra cómo hacerla a mano.
 */
@Service
public class RespaldoService {

    private static final Logger log = LoggerFactory.getLogger(RespaldoService.class);
    private static final DateTimeFormatter NOMBRE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final ConfigSoporteService config;
    private final Path carpeta;
    private final AtomicBoolean enCurso = new AtomicBoolean();
    private volatile Map<String, Object> ultimo;

    public RespaldoService(DataSource dataSource, JdbcTemplate jdbc, Environment env, ConfigSoporteService config,
                           @Value("${soporte.respaldos.ruta:./respaldos}") String ruta) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.env = env;
        this.config = config;
        this.carpeta = Path.of(ruta).toAbsolutePath().normalize();
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void diario() {
        if (enCurso.get()) return;
        ejecutar("automática");
    }

    /** Lanza una copia en segundo plano. */
    public boolean iniciar() {
        if (enCurso.get()) return false;
        Thread.ofVirtual().name("soporte-respaldo").start(() -> ejecutar("manual"));
        return true;
    }

    private void ejecutar(String motivo) {
        if (!enCurso.compareAndSet(false, true)) return;
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("inicio", LocalDateTime.now().toString());
        r.put("motivo", motivo);
        try {
            Files.createDirectories(carpeta);
            if (carpeta.toFile().getUsableSpace() < 200L * 1024 * 1024) throw new IOException("Hay menos de 200 MB libres en el disco");
            String url = env.getProperty("spring.datasource.url", "");
            String nombre = "spacemusic-" + LocalDateTime.now().format(NOMBRE);
            Path archivo = carpeta.resolve(nombre + ".sql.gz");
            try {
                if (url.contains(":h2:")) volcarH2(archivo);
                else volcarMysql(archivo);
            } catch (Exception e) {
                Files.deleteIfExists(archivo); // una copia fallida no debe quedar como si fuera válida
                throw e;
            }
            r.put("archivo", archivo.getFileName().toString());
            r.put("tamanoMb", MonitorService.redondear(Files.size(archivo) / 1048576.0));
            r.put("resultado", "OK");
            recortar();
        } catch (Exception e) {
            r.put("resultado", "ERROR");
            r.put("error", Sanitizador.limpiar(e.getMessage(), 300));
            log.warn("No se pudo crear la copia de seguridad: {}", Sanitizador.limpiar(e.getMessage(), 300));
        } finally {
            r.put("duracionSeg", (System.currentTimeMillis() - t0) / 1000);
            ultimo = r;
            enCurso.set(false);
        }
    }

    /** Estructura (SHOW CREATE TABLE) y datos (INSERT por lotes) de todas las tablas, comprimido. */
    private void volcarMysql(Path archivo) throws Exception {
        Path tmp = archivo.resolveSibling(archivo.getFileName() + ".parcial");
        try (Connection c = dataSource.getConnection();
             Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8))) {
            w.write("-- Copia de seguridad de SpaceMusic · " + LocalDateTime.now() + "\nSET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\n\n");
            List<String> tablas = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) tablas.add(rs.getString("TABLE_NAME"));
            }
            for (String t : tablas) {
                if (!t.matches("[A-Za-z0-9_]+")) continue;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW CREATE TABLE `" + t + "`")) {
                    if (rs.next()) w.write("DROP TABLE IF EXISTS `" + t + "`;\n" + rs.getString(2) + ";\n\n");
                }
                try (Statement st = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    st.setFetchSize(Integer.MIN_VALUE); // MySQL: leer fila por fila sin cargar toda la tabla en memoria
                    try (ResultSet rs = st.executeQuery("SELECT * FROM `" + t + "`")) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        int enLote = 0;
                        while (rs.next()) {
                            w.write(enLote == 0 ? "INSERT INTO `" + t + "` VALUES\n(" : ",\n(");
                            for (int i = 1; i <= n; i++) {
                                if (i > 1) w.write(',');
                                Object v = rs.getObject(i);
                                if (v == null) w.write("NULL");
                                else if (v instanceof byte[] b) w.write(b.length == 0 ? "''" : "0x" + HexFormat.of().formatHex(b));
                                else if (v instanceof Number || v instanceof Boolean) w.write(v instanceof Boolean bo ? (bo ? "1" : "0") : v.toString());
                                else w.write("'" + v.toString().replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'");
                            }
                            w.write(')');
                            if (++enLote >= 100) { w.write(";\n"); enLote = 0; }
                        }
                        if (enLote > 0) w.write(";\n");
                        w.write('\n');
                    }
                }
            }
            w.write("SET FOREIGN_KEY_CHECKS=1;\n");
        }
        Files.move(tmp, archivo);
    }

    /** H2: SCRIPT TO a un archivo temporal y luego se comprime con gzip. */
    private void volcarH2(Path archivo) throws IOException {
        Path tmp = archivo.resolveSibling(archivo.getFileName() + ".parcial.sql");
        try {
            jdbc.execute("SCRIPT TO '" + tmp.toString().replace("'", "''") + "'");
            try (InputStream in = Files.newInputStream(tmp); OutputStream out = new GZIPOutputStream(Files.newOutputStream(archivo))) {
                in.transferTo(out);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void recortar() throws IOException {
        int conservar = (int) config.valor("respaldo.copias");
        List<Path> copias = listar();
        for (int i = conservar; i < copias.size(); i++) Files.deleteIfExists(copias.get(i));
    }

    private List<Path> listar() throws IOException {
        if (!Files.isDirectory(carpeta)) return List.of();
        try (var s = Files.list(carpeta)) {
            return s.filter(p -> p.getFileName().toString().startsWith("spacemusic-") && !p.toString().contains(".parcial"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
    }

    public Map<String, Object> estado() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("carpeta", carpeta.getFileName().toString());
        r.put("enCurso", enCurso.get());
        r.put("ultimo", ultimo);
        r.put("programacion", "Todos los días a las 4:30");
        r.put("conservar", (int) config.valor("respaldo.copias"));
        List<Map<String, Object>> copias = new ArrayList<>();
        try {
            for (Path p : listar()) copias.add(Map.of("archivo", p.getFileName().toString(),
                    "tamanoMb", MonitorService.redondear(Files.size(p) / 1048576.0),
                    "fecha", Files.getLastModifiedTime(p).toString()));
        } catch (IOException e) {
            r.put("error", "No se pudo leer la carpeta de copias");
        }
        r.put("copias", copias);
        r.put("restaurar", env.getProperty("spring.datasource.url", "").contains(":h2:")
                ? "Detén el servidor y usa descomprime el archivo (gunzip) y ejecuta RUNSCRIPT FROM '<archivo>.sql' en la consola de H2."
                : "Detén el servidor y ejecuta: gunzip -c respaldos/<archivo>.sql.gz | mysql -u <usuario> -p musicdb");
        return r;
    }
}
