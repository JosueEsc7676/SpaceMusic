//package com.bpm.music.utils;
//
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.HttpMethod;
//import org.springframework.http.ResponseEntity;
//import org.springframework.stereotype.Component;
//import org.springframework.web.client.RestTemplate;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//
//@Component
//public class MusicBrainzApi {
//
//    private final RestTemplate restTemplate = new RestTemplate();
//    private final ObjectMapper objectMapper = new ObjectMapper();
//
//    public String buscarPortada(String artista, String nombreCancion) {
//        try {
//            String urlBusqueda = "https://musicbrainz.org/ws/2/release/?query=artist:"
//                    + artista + " AND release:" + nombreCancion + "&fmt=json";
//
//            // Configurar Headers para que MusicBrainz no nos bloquee
//            HttpHeaders headers = new HttpHeaders();
//            headers.set("User-Agent", "MiAppMusica/1.0 ( contacto@ejemplo.com )"); // Pon cualquier nombre y correo
//            HttpEntity<String> entity = new HttpEntity<>(headers);
//
//            // Hacer la petición con los headers
//            ResponseEntity<String> response = restTemplate.exchange(urlBusqueda, HttpMethod.GET, entity, String.class);
//
//            JsonNode root = objectMapper.readTree(response.getBody());
//
//            JsonNode releases = root.path("releases");
//            if (releases.isArray() && !releases.isEmpty()) {
//                String mbid = releases.get(0).path("id").asText();
//                return "https://coverartarchive.org/release/" + mbid + "/front";
//            }
//        } catch (Exception e) {
//            System.err.println("Error: " + e.getMessage());
//        }
//        return "https://via.placeholder.com/500?text=Sin+Portada";
//    }
//}

// utils/MusicBrainzApi.java
package com.bpm.music.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class MusicBrainzApi {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public String buscarPortada(String artista, String cancion) {
        try {
            // Usar Cover Art Archive o Deezer API (más fácil)
            String url = "https://api.deezer.com/search?q="
                    + artista.replace(" ", "%20")
                    + "%20"
                    + cancion.replace(" ", "%20");

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode data = root.path("data");

            if (data.isArray() && data.size() > 0) {
                String coverUrl = data.get(0).path("album").path("cover_medium").asText();
                if (!coverUrl.isEmpty()) {
                    return coverUrl;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠ No se pudo obtener portada para: " + artista + " - " + cancion);
        }
        return "https://via.placeholder.com/300x300?text=No+Cover";
    }
}