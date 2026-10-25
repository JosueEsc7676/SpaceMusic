package com.bpm.music.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    @Value("${music.path}")
    private String ruta;

    @GetMapping("/stream/{filename:.+}")
    public ResponseEntity<Resource> stream(@PathVariable String filename) {
        try {
            // Método 1: Decodificar URL
            String decoded = URLDecoder.decode(filename, StandardCharsets.UTF_8.toString());

            // Método 2: Reemplazar %20 por espacio
            String cleanName = decoded.replace("%20", " ").replace("_", " ");

            // Método 3: Usar el nombre directamente sin modificar
            // Construir la ruta completa
            Path filePath = Paths.get(ruta).resolve(cleanName).normalize();

            System.out.println("=== DEBUG STREAM ===");
            System.out.println("Filename original: " + filename);
            System.out.println("Decoded: " + decoded);
            System.out.println("Clean name: " + cleanName);
            System.out.println("Ruta completa: " + filePath.toString());

            File file = filePath.toFile();

            if (!file.exists()) {
                // Intentar con el nombre original sin decodificar
                Path fallbackPath = Paths.get(ruta).resolve(filename.replace("%20", " "));
                System.out.println("Intentando fallback: " + fallbackPath);
                file = fallbackPath.toFile();

                if (!file.exists()) {
                    System.out.println("❌ Archivo no encontrado");
                    return ResponseEntity.notFound().build();
                }
            }

            if (!file.canRead()) {
                System.out.println("❌ No se puede leer el archivo");
                return ResponseEntity.status(403).build();
            }

            // Determinar el tipo MIME
            String mimeType = Files.probeContentType(file.toPath());
            if (mimeType == null) {
                mimeType = "audio/mpeg";
            }

            System.out.println("✅ Sirviendo archivo: " + file.getName() + " | Tamaño: " + file.length() + " bytes");

            // Usar FileSystemResource para mejor manejo
            Resource resource = new FileSystemResource(file);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()))
                    .body(resource);

        } catch (Exception e) {
            System.err.println("❌ Error en streaming: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/check/{filename:.+}")
    public Map<String, Object> check(@PathVariable String filename) {
        Map<String, Object> res = new HashMap<>();
        try {
            String decoded = URLDecoder.decode(filename, StandardCharsets.UTF_8.toString());
            String cleanName = decoded.replace("%20", " ").replace("_", " ");
            Path filePath = Paths.get(ruta).resolve(cleanName).normalize();

            File file = filePath.toFile();
            boolean exists = file.exists();

            res.put("exists", exists);
            res.put("size", exists ? file.length() : 0);
            res.put("path", filePath.toString());
            res.put("canRead", exists ? file.canRead() : false);
            res.put("filename_original", filename);
            res.put("filename_decoded", decoded);
            res.put("filename_clean", cleanName);

            if (exists && file.canRead()) {
                try {
                    String mime = Files.probeContentType(filePath);
                    res.put("mimeType", mime != null ? mime : "audio/mpeg");
                } catch (Exception e) {
                    res.put("mimeType", "audio/mpeg");
                }
            }

            System.out.println("🔍 Check: " + filePath + " | exists=" + exists);

        } catch (Exception e) {
            res.put("exists", false);
            res.put("error", e.getMessage());
        }
        return res;
    }

    // Endpoint alternativo para probar directamente con el nombre del archivo
    @GetMapping("/direct")
    public ResponseEntity<Resource> streamDirect(@RequestParam String file) {
        return stream(file);
    }
}