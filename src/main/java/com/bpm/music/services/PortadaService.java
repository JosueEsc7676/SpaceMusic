package com.bpm.music.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;

@Service
@EnableAsync
public class PortadaService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    @Async
    public CompletableFuture<Map<String, String>> buscarInfoCompletaAsync(String artista, String cancion, Long cancionId) {

        String cacheKey = String.valueOf(cancionId);

        if (cache.containsKey(cacheKey)) {
            return CompletableFuture.completedFuture(cache.get(cacheKey));
        }

        Map<String, String> info = new HashMap<>();

        try {
            String url = "https://api.deezer.com/search?q="
                    + java.net.URLEncoder.encode(artista + " " + cancion, "UTF-8")
                    + "&limit=1";

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode data = root.path("data");

            if (data.isArray() && data.size() > 0) {
                JsonNode track = data.get(0);

                info.put("album", track.path("album").path("title").asText("Single"));
                info.put("duracionSegundos", String.valueOf(track.path("duration").asInt(0)));
                info.put("artista", track.path("artist").path("name").asText(artista));
                info.put("nombre", track.path("title").asText(cancion));
                info.put("genero", "");
                info.put("portadaUrl", null);

            } else {
                info.put("album", "Single");
                info.put("duracionSegundos", "0");
                info.put("artista", artista);
                info.put("nombre", cancion);
                info.put("genero", "");
                info.put("portadaUrl", null);
            }

            cache.put(cacheKey, info);
            return CompletableFuture.completedFuture(info);

        } catch (Exception e) {
            info.put("album", "Single");
            info.put("duracionSegundos", "0");
            info.put("artista", artista);
            info.put("nombre", cancion);
            info.put("genero", "");
            info.put("portadaUrl", null);

            cache.put(cacheKey, info);
            return CompletableFuture.completedFuture(info);
        }
    }
}