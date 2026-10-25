package com.bpm.music.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostico")
public class DiagnosticoController {

    @Value("${music.path}")
    private String rutaMusica;

    @GetMapping
    public Map<String, Object> diagnosticar() {
        Map<String, Object> resultado = new HashMap<>();
        List<Map<String, String>> archivosEncontrados = new ArrayList<>();

        resultado.put("ruta_configurada", rutaMusica);

        File carpeta = new File(rutaMusica);
        resultado.put("carpeta_existe", carpeta.exists());
        resultado.put("carpeta_es_directorio", carpeta.isDirectory());

        if (carpeta.exists() && carpeta.isDirectory()) {
            File[] mp3s = carpeta.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp3"));

            if (mp3s != null) {
                resultado.put("total_mp3_encontrados", mp3s.length);

                for (File mp3 : mp3s) {
                    Map<String, String> info = new HashMap<>();
                    info.put("nombre_real", mp3.getName());
                    info.put("tamaño_bytes", String.valueOf(mp3.length()));
                    info.put("ruta_absoluta", mp3.getAbsolutePath());
                    info.put("legible", String.valueOf(mp3.canRead()));
                    archivosEncontrados.add(info);
                }
            } else {
                resultado.put("total_mp3_encontrados", 0);
            }
        }

        resultado.put("archivos", archivosEncontrados);

        // Probar acceder a la carpeta montada con rclone
        try {
            Path testPath = Paths.get(rutaMusica);
            resultado.put("permiso_lectura", Files.isReadable(testPath));
            resultado.put("permiso_ejecucion", Files.isExecutable(testPath));
        } catch (Exception e) {
            resultado.put("error_acceso", e.getMessage());
        }

        return resultado;
    }

    @GetMapping("/test-stream/{filename}")
    public Map<String, Object> testStream(@PathVariable String filename) {
        Map<String, Object> resultado = new HashMap<>();

        try {
            Path filePath = Paths.get(rutaMusica).resolve(filename).normalize();
            resultado.put("ruta_buscada", filePath.toString());
            resultado.put("existe", Files.exists(filePath));

            if (Files.exists(filePath)) {
                resultado.put("es_archivo_regular", Files.isRegularFile(filePath));
                resultado.put("tamaño", Files.size(filePath));
                resultado.put("legible", Files.isReadable(filePath));
                resultado.put("content_type", Files.probeContentType(filePath));
            }
        } catch (Exception e) {
            resultado.put("error", e.getMessage());
        }

        return resultado;
    }
}