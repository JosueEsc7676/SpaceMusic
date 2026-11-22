package com.bpm.music.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Traducción de la letra línea por línea (para verla debajo de la letra sincronizada en la web y la TV).
 * Usa MyMemory (gratis, sin claves; detecta el idioma) y guarda cada traducción en disco: una canción se traduce
 * una sola vez por idioma. Si la letra ya está en el idioma pedido, se avisa y no se gasta nada.
 */
@Service
public class TraduccionService {

    private static final Set<String> IDIOMAS = Set.of("es", "en", "pt", "fr", "it", "de");
    private static final Pattern TIEMPO = Pattern.compile("^\\s*(\\[[0-9:.]+\\]\\s*)+");

    private final LetrasService letras;
    private final Path carpeta;
    private final String correo;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public TraduccionService(LetrasService letras, ImagenService imagenes,
                             @Value("${traduccion.mymemory.correo:}") String correo) {
        this.letras = letras;
        this.carpeta = imagenes.carpeta("traducciones");
        this.correo = correo == null ? "" : correo.trim();
    }

    /** {idioma, origen, mismoIdioma, lineas: [texto traducido por cada línea de la letra (sin tiempos)]} */
    public Map<String, Object> traducir(Long cancionId, String idioma) throws Exception {
        String a = idioma == null ? "es" : idioma.toLowerCase(Locale.ROOT);
        if (!IDIOMAS.contains(a)) throw new IllegalArgumentException("Idioma no disponible");
        Map<String, Object> l = letras.obtener(cancionId);
        String texto = Objects.toString(l.get("letraSincronizada"), "");
        if (texto.isBlank()) texto = Objects.toString(l.get("letra"), "");
        if (texto.isBlank()) throw new NoSuchElementException("Esta canción no tiene letra");
        // La traducción guardada va con la huella de la letra: si la letra cambia, se traduce de nuevo
        Path archivo = carpeta.resolve(cancionId + "-" + a + "-" + Integer.toHexString(texto.hashCode()) + ".json");
        if (Files.isRegularFile(archivo)) {
            @SuppressWarnings("unchecked") Map<String, Object> guardada = json.readValue(archivo.toFile(), Map.class);
            return guardada;
        }
        List<String> lineas = new ArrayList<>();
        for (String s : texto.split("\\R")) {
            String t = TIEMPO.matcher(s).replaceAll("").trim();
            if (s.trim().matches("^\\[[a-zA-Z]+:.*\\]$")) continue; // etiquetas [ar:], [ti:]…
            lineas.add(t);
        }
        // Se traduce por tandas de hasta ~450 caracteres (límite del servicio), con un renglón por línea
        List<String> salida = new ArrayList<>(Collections.nCopies(lineas.size(), ""));
        String origen = null;
        int i = 0;
        while (i < lineas.size()) {
            StringBuilder tanda = new StringBuilder();
            List<Integer> indices = new ArrayList<>();
            while (i < lineas.size() && tanda.length() + lineas.get(i).length() + 1 <= 450) {
                if (!lineas.get(i).isBlank()) {
                    if (!tanda.isEmpty()) tanda.append('\n');
                    tanda.append(lineas.get(i));
                    indices.add(i);
                }
                i++;
            }
            if (indices.isEmpty()) { if (i < lineas.size() && lineas.get(i).length() > 450) i++; continue; }
            JsonNode r = pedir(tanda.toString(), a);
            String detectado = r.path("responseData").path("detectedLanguage").asText("");
            if (origen == null && !detectado.isBlank()) {
                origen = detectado.toLowerCase(Locale.ROOT);
                if (origen.startsWith(a)) {
                    Map<String, Object> mismo = Map.of("idioma", a, "origen", origen, "mismoIdioma", true, "lineas", List.of());
                    json.writeValue(archivo.toFile(), mismo);
                    return mismo;
                }
            }
            if (r.path("quotaFinished").asBoolean(false)) throw new IllegalStateException("Se acabó la traducción gratis de hoy. Vuelve mañana.");
            String[] partes = r.path("responseData").path("translatedText").asText("").split("\\R", -1);
            if (partes.length == indices.size()) {
                for (int k = 0; k < partes.length; k++) salida.set(indices.get(k), limpiar(partes[k]));
            } else {
                // El servicio juntó o separó renglones: se traduce cada línea por separado
                for (int idx : indices) salida.set(idx, limpiar(pedir(lineas.get(idx), a).path("responseData").path("translatedText").asText("")));
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idioma", a);
        r.put("origen", origen);
        r.put("mismoIdioma", false);
        r.put("lineas", salida);
        r.put("originales", lineas); // para que cada app empareje por texto (no por posición)
        json.writeValue(archivo.toFile(), r);
        return r;
    }

    private JsonNode pedir(String q, String a) throws Exception {
        String url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&langpair=" + URLEncoder.encode("Autodetect|" + a, StandardCharsets.UTF_8)
                + (correo.isEmpty() ? "" : "&de=" + URLEncoder.encode(correo, StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SpaceMusic/1.0").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("El servicio de traducción no respondió");
        return json.readTree(r.body());
    }

    /** Quita las comillas angulares y el punto final que el servicio agrega a frases cortas. */
    static String limpiar(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("«") && t.endsWith("».")) t = t.substring(1, t.length() - 2);
        else if (t.startsWith("«") && t.endsWith("»")) t = t.substring(1, t.length() - 1);
        return t;
    }
}
