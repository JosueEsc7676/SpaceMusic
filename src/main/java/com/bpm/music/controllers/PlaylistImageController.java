// controllers/PlaylistImageController.java
package com.bpm.music.controllers;

import com.bpm.music.models.Playlist;
import com.bpm.music.repositories.PlaylistRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/playlists")
@CrossOrigin(origins = "*")
public class PlaylistImageController {

    @Autowired
    private PlaylistRepository playlistRepository;

    @Value("${music.playlist-images-path:./playlist-images}")
    private String imagenesPath;

    @Value("${music.base-url:http://localhost:8080}")
    private String baseUrl;

    // Crear playlist con imagen incluida (MÉTODO ÚNICO)
    @PostMapping("/con-imagen")
    public ResponseEntity<?> crearPlaylistConImagen(
            @RequestParam("nombre") String nombre,
            @RequestParam(value = "descripcion", required = false) String descripcion,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        try {
            // Crear la playlist primero
            Playlist playlist = Playlist.builder()
                    .nombre(nombre)
                    .descripcion(descripcion != null ? descripcion : "")
                    .portadaUrl("https://singlecolorimage.com/get/1DB954/300x300")
                    .cancionesCount(0)
                    .canciones(new ArrayList<>())
                    .build();

            playlist = playlistRepository.save(playlist);

            // Si se subió una imagen, guardarla
            if (file != null && !file.isEmpty()) {
                File directorio = new File(imagenesPath);
                if (!directorio.exists()) {
                    directorio.mkdirs();
                }

                String extension = "";
                String nombreOriginal = file.getOriginalFilename();
                if (nombreOriginal != null && nombreOriginal.contains(".")) {
                    extension = nombreOriginal.substring(nombreOriginal.lastIndexOf("."));
                }
                String nombreArchivo = "playlist_" + playlist.getId() + "_" + UUID.randomUUID().toString() + extension;
                Path rutaCompleta = Paths.get(imagenesPath, nombreArchivo);

                Files.write(rutaCompleta, file.getBytes());

                String imagenUrl = baseUrl + "/api/playlists/imagen/" + nombreArchivo;
                playlist.setPortadaUrl(imagenUrl);
                playlist.setPortadaPath(rutaCompleta.toString());
                playlist = playlistRepository.save(playlist);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("id", playlist.getId());
            response.put("nombre", playlist.getNombre());
            response.put("descripcion", playlist.getDescripcion());
            response.put("portadaUrl", playlist.getPortadaUrl());
            response.put("cancionesCount", playlist.getCancionesCount());

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Error al crear la playlist: " + e.getMessage()));
        }
    }

    // Subir imagen para una playlist existente
    @PostMapping("/{id}/imagen")
    public ResponseEntity<?> subirImagen(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            Playlist playlist = playlistRepository.findById(id).orElse(null);
            if (playlist == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Playlist no encontrada"));
            }

            // Crear directorio si no existe
            File directorio = new File(imagenesPath);
            if (!directorio.exists()) {
                directorio.mkdirs();
            }

            // Generar nombre único para la imagen
            String extension = "";
            String nombreOriginal = file.getOriginalFilename();
            if (nombreOriginal != null && nombreOriginal.contains(".")) {
                extension = nombreOriginal.substring(nombreOriginal.lastIndexOf("."));
            }
            String nombreArchivo = "playlist_" + id + "_" + UUID.randomUUID().toString() + extension;
            Path rutaCompleta = Paths.get(imagenesPath, nombreArchivo);

            // Guardar la imagen
            Files.write(rutaCompleta, file.getBytes());

            // Guardar la URL en la playlist
            String imagenUrl = baseUrl + "/api/playlists/imagen/" + nombreArchivo;
            playlist.setPortadaUrl(imagenUrl);
            playlist.setPortadaPath(rutaCompleta.toString());
            playlistRepository.save(playlist);

            Map<String, String> response = new HashMap<>();
            response.put("url", imagenUrl);
            response.put("message", "Imagen subida correctamente");

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Error al guardar la imagen: " + e.getMessage()));
        }
    }

    // Servir la imagen de la playlist
    @GetMapping("/imagen/{filename}")
    public ResponseEntity<byte[]> getImagen(@PathVariable String filename) {
        try {
            Path ruta = Paths.get(imagenesPath, filename);
            if (Files.exists(ruta)) {
                byte[] imagen = Files.readAllBytes(ruta);
                String contentType = Files.probeContentType(ruta);
                if (contentType == null) {
                    contentType = "image/jpeg";
                }
                return ResponseEntity.ok()
                        .header("Content-Type", contentType)
                        .header("Cache-Control", "max-age=86400")
                        .body(imagen);
            }
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Actualizar imagen de playlist
    @PutMapping("/{id}/imagen")
    public ResponseEntity<?> actualizarImagen(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file
    ) {
        return subirImagen(id, file);
    }

    // Eliminar imagen de playlist
    @DeleteMapping("/{id}/imagen")
    public ResponseEntity<?> eliminarImagen(@PathVariable Long id) {
        try {
            Playlist playlist = playlistRepository.findById(id).orElse(null);
            if (playlist == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Playlist no encontrada"));
            }

            if (playlist.getPortadaPath() != null) {
                File imagenFile = new File(playlist.getPortadaPath());
                if (imagenFile.exists()) {
                    imagenFile.delete();
                }
            }

            playlist.setPortadaUrl("https://singlecolorimage.com/get/1DB954/300x300");
            playlist.setPortadaPath(null);
            playlistRepository.save(playlist);

            return ResponseEntity.ok(Map.of("message", "Imagen eliminada correctamente"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}