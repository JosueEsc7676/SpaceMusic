package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Edición de canciones, portadas y subida de archivos nuevos. */
@Service
public class CancionService {

    private static final Logger log = LoggerFactory.getLogger(CancionService.class);

    private final CancionRepository repo;
    private final BibliotecaService biblioteca;
    private final EscanerService escaner;
    private final ImagenService imagenes;

    public CancionService(CancionRepository repo, BibliotecaService biblioteca,
                          EscanerService escaner, ImagenService imagenes) {
        this.repo = repo;
        this.biblioteca = biblioteca;
        this.escaner = escaner;
        this.imagenes = imagenes;
    }

    /** Archivo de audio de una canción, o null si no se encuentra en disco. */
    public File archivo(Long id) {
        String ruta = repo.findRutaById(id).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        return escaner.resolverArchivo(ruta);
    }

    @Transactional
    public CancionDTO editar(Long id, Map<String, Object> datos) {
        Cancion c = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        String nombre = texto(datos.get("nombre"), 250);
        String artista = texto(datos.get("artista"), 250);
        if (nombre != null) {
            if (nombre.isBlank()) throw new IllegalArgumentException("El título no puede quedar vacío");
            c.setNombre(nombre);
        }
        if (artista != null) c.setArtista(artista.isBlank() ? "Desconocido" : artista);
        String album = texto(datos.get("album"), 250);
        if (album != null) c.setAlbum(album.isBlank() ? "Single" : album);
        String genero = texto(datos.get("genero"), 100);
        if (genero != null) c.setGenero(genero.isBlank() ? "Desconocido" : genero);
        if (datos.containsKey("anio")) c.setAnio(entero(datos.get("anio"), 1000, 2999));
        if (datos.containsKey("numeroPista")) c.setNumeroPista(entero(datos.get("numeroPista"), 1, 999));
        repo.save(c);
        biblioteca.invalidar();
        return biblioteca.requerir(id);
    }

    @Transactional
    public CancionDTO cambiarPortada(Long id, MultipartFile file) throws IOException {
        biblioteca.requerir(id);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("No se recibió ninguna imagen");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("La imagen no puede pasar de 10 MB");
        byte[] jpg = imagenes.redimensionar(file.getBytes(), 1200);
        if (jpg == null) throw new IllegalArgumentException("No se pudo leer la imagen (usa JPG o PNG)");
        repo.actualizarPortada(id, jpg, ImagenService.huella(jpg));
        imagenes.invalidarMiniaturas(id);
        biblioteca.invalidar();
        return biblioteca.requerir(id);
    }

    /**
     * Guarda archivos de audio subidos en la carpeta "Subidas" de la
     * biblioteca y los agrega de inmediato.
     */
    public List<Map<String, Object>> subir(List<MultipartFile> archivos) {
        File raiz = escaner.raiz();
        if (!raiz.isDirectory()) throw new IllegalArgumentException("La carpeta de música no está disponible en el servidor");
        Path destino = raiz.toPath().resolve("Subidas");
        try {
            Files.createDirectories(destino);
        } catch (IOException e) {
            throw new IllegalArgumentException("No se puede escribir en la carpeta de música");
        }
        List<Map<String, Object>> resultado = new ArrayList<>();
        for (MultipartFile mf : archivos) {
            Map<String, Object> r = new LinkedHashMap<>();
            String original = Optional.ofNullable(mf.getOriginalFilename()).orElse("cancion");
            r.put("archivo", original);
            try {
                String ext = MetadataService.extension(original);
                if (!EscanerService.EXTENSIONES.contains(ext)) throw new IllegalArgumentException("Formato no soportado (." + ext + ")");
                if (mf.getSize() < 32 * 1024) throw new IllegalArgumentException("El archivo es demasiado pequeño");
                String nombre = nombreSeguro(MetadataService.sinExtension(original)) + "." + ext;
                Path p = destino.resolve(nombre);
                int n = 1;
                while (Files.exists(p)) p = destino.resolve(nombreSeguro(MetadataService.sinExtension(original)) + " (" + (++n) + ")." + ext);
                try (InputStream in = mf.getInputStream()) {
                    Files.copy(in, p, StandardCopyOption.REPLACE_EXISTING);
                }
                Cancion c = escaner.importarArchivo(p.toFile());
                r.put("ok", true);
                r.put("cancion", biblioteca.porId(c.getId()).orElse(null));
            } catch (Exception e) {
                r.put("ok", false);
                r.put("error", e.getMessage());
                log.warn("Error subiendo {}: {}", original, e.getMessage());
            }
            resultado.add(r);
        }
        return resultado;
    }

    private static String nombreSeguro(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").replaceAll("\\.{2,}", ".").trim();
        if (r.isEmpty() || r.equals(".")) r = "cancion";
        return r.length() > 150 ? r.substring(0, 150) : r;
    }

    private static String texto(Object o, int max) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Integer entero(Object o, int min, int max) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            int n = Integer.parseInt(o.toString().trim());
            if (n < min || n > max) throw new IllegalArgumentException("Valor fuera de rango: " + n);
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Número inválido: " + o);
        }
    }
}
