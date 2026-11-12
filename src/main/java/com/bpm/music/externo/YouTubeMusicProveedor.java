package com.bpm.music.externo;

import com.bpm.music.services.CacheTemporal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Adaptador de YouTube Music. Usa ytmusicapi (biblioteca NO oficial, no es una API de Google) mediante
 * el script scripts/ytm-proveedor.py. La petición va como JSON por la entrada estándar: nunca se pone
 * texto del usuario en la línea de comandos.
 * Límites: una consulta a la vez, mínimo 0,7 s entre consultas, 20 s de espera máxima, 1 reintento,
 * caché de 30 minutos y pausa automática tras fallos seguidos (ver {@link Proveedores}).
 */
@Service
public class YouTubeMusicProveedor {

    public static final String CLAVE = "ytmusic";
    public static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    public static final Pattern BROWSE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final Path python, script;
    private final Proveedores proveedores;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore turno = new Semaphore(1, true);
    private final CacheTemporal<String, JsonNode> cache = new CacheTemporal<>(30 * 60_000, 2000);
    private volatile long ultima;

    public YouTubeMusicProveedor(@Value("${ytmusic.python:${user.home}/spacemusic/spotdl-env/bin/python}") String python,
                                 @Value("${ytmusic.script:${user.home}/spacemusic/ytm-proveedor.py}") String script,
                                 Proveedores proveedores) {
        this.python = Path.of(python);
        this.script = Path.of(script);
        this.proveedores = proveedores;
        proveedores.registrar(CLAVE, "YouTube Music (ytmusicapi)", "Búsqueda y canciones relacionadas. Biblioteca no oficial.");
    }

    public boolean disponible() {
        return Files.isExecutable(python) && Files.isReadable(script);
    }

    public JsonNode buscar(String consulta, String filtro, int limite) {
        String q = consulta == null ? "" : consulta.strip();
        if (q.length() < 2 || q.length() > 150) throw new IllegalArgumentException("Escribe entre 2 y 150 caracteres");
        if (!Set.of("songs", "albums", "artists").contains(filtro)) throw new IllegalArgumentException("Tipo de búsqueda no válido");
        return pedir(Map.of("accion", "buscar", "consulta", q, "filtro", filtro, "limite", Math.max(1, Math.min(limite, 50))));
    }

    public JsonNode relacionadas(String videoId, int limite) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) throw new IllegalArgumentException("Identificador no válido");
        return pedir(Map.of("accion", "relacionadas", "videoId", videoId, "limite", Math.max(1, Math.min(limite, 50))));
    }

    public JsonNode album(String browseId) {
        if (browseId == null || !BROWSE_ID.matcher(browseId).matches()) throw new IllegalArgumentException("Identificador no válido");
        return pedir(Map.of("accion", "album", "browseId", browseId));
    }

    public JsonNode artista(String browseId) {
        if (browseId == null || !BROWSE_ID.matcher(browseId).matches()) throw new IllegalArgumentException("Identificador no válido");
        return pedir(Map.of("accion", "artista", "browseId", browseId));
    }

    private JsonNode pedir(Map<String, Object> pedido) {
        if (!disponible()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La búsqueda de YouTube Music no está instalada en este servidor");
        String clave;
        try { clave = json.writeValueAsString(new TreeMap<>(pedido)); } catch (Exception e) { throw new IllegalArgumentException("Petición no válida"); }
        JsonNode guardado = cache.leer(clave);
        if (guardado != null) return guardado;
        if (!proveedores.permitido(CLAVE))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "YouTube Music no responde; se volverá a intentar en unos minutos");
        String ultimoError = null;
        for (int intento = 1; intento <= 2; intento++) {
            try {
                JsonNode r = ejecutar(clave);
                if (r.path("ok").asBoolean()) {
                    proveedores.exito(CLAVE);
                    cache.poner(clave, r);
                    return r;
                }
                String err = r.path("error").asText("Error del proveedor");
                // Errores de la petición (no del proveedor): no se reintentan ni cuentan como fallo
                if (err.contains("no válid") || err.contains("demasiado corta")) throw new IllegalArgumentException("Búsqueda no válida");
                ultimoError = err;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                ultimoError = e.getMessage();
            }
            try { Thread.sleep(800L * intento); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        proveedores.fallo(CLAVE, ultimoError);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "YouTube Music no respondió. Inténtalo de nuevo en un momento.");
    }

    private JsonNode ejecutar(String entrada) throws Exception {
        if (!turno.tryAcquire(25, TimeUnit.SECONDS)) throw new TimeoutException("Hay muchas búsquedas en curso");
        try {
            long espera = 700 - (System.currentTimeMillis() - ultima);
            if (espera > 0) Thread.sleep(espera);
            Process p = new ProcessBuilder(python.toString(), script.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream os = p.getOutputStream()) { os.write(entrada.getBytes(StandardCharsets.UTF_8)); }
            Future<byte[]> salida = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
                try (InputStream is = p.getInputStream()) { return is.readNBytes(2_000_000); }
            });
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new TimeoutException("YouTube Music tardó demasiado"); }
            return json.readTree(new String(salida.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } finally {
            ultima = System.currentTimeMillis();
            turno.release();
        }
    }
}
