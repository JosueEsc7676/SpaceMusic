package com.bpm.music.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/** Guardado seguro de imágenes subidas, miniaturas de portadas e imágenes generadas. */
@Service
public class ImagenService {

    private static final Logger log = LoggerFactory.getLogger(ImagenService.class);
    private static final Pattern NOMBRE_SEGURO = Pattern.compile("^[A-Za-z0-9._-]{1,120}$");
    private static final long MAX_BYTES = 8 * 1024 * 1024;

    private final Path base;

    public ImagenService(@Value("${music.playlist-images-path:./uploads}") String ruta) {
        this.base = Path.of(ruta).toAbsolutePath().normalize();
    }

    public Path carpeta(String sub) {
        Path p = sub == null ? base : base.resolve(sub).normalize();
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            log.warn("No se pudo crear {}: {}", p, e.getMessage());
        }
        return p;
    }

    /**
     * Guarda una imagen subida, redimensionada a un máximo de {@code lado} px.
     * Devuelve el nombre del archivo guardado. Rechaza lo que no sea imagen.
     */
    public String guardar(MultipartFile file, String subcarpeta, String prefijo, int lado) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("No se recibió ninguna imagen");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("La imagen no puede pasar de 8 MB");
        byte[] datos = file.getBytes();
        Path dir = carpeta(subcarpeta);
        String nombre = prefijo + "_" + UUID.randomUUID().toString().substring(0, 12);

        byte[] jpg = redimensionar(datos, lado);
        if (jpg != null) {
            nombre += ".jpg";
            Files.write(dir.resolve(nombre), jpg);
            return nombre;
        }
        // Formatos que Java no decodifica pero el navegador sí (WebP, GIF animado)
        String ext = detectarFormato(datos);
        if (ext == null) throw new IllegalArgumentException("Formato no soportado. Usa JPG, PNG, GIF o WebP");
        nombre += "." + ext;
        Files.write(dir.resolve(nombre), datos);
        return nombre;
    }

    /** Ruta segura dentro de una subcarpeta (evita ../../ en el nombre). */
    public Path resolver(String subcarpeta, String nombre) {
        if (nombre == null || !NOMBRE_SEGURO.matcher(nombre).matches()) return null;
        Path dir = subcarpeta == null ? base : base.resolve(subcarpeta).normalize();
        Path p = dir.resolve(nombre).normalize();
        return (p.startsWith(dir) && Files.isRegularFile(p)) ? p : null;
    }

    public void borrar(String subcarpeta, String nombre) {
        Path p = resolver(subcarpeta, nombre);
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) { }
        }
    }

    public static String tipoContenido(String nombre) {
        String n = nombre.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    // ═══════════════════ Miniaturas de portadas ═══════════════════

    /** Miniatura JPEG cacheada en disco de la portada de una canción. */
    public byte[] miniaturaPortada(Long id, byte[] original, int lado) {
        Path cache = carpeta("cache/portadas").resolve(id + "_" + lado + ".jpg");
        try {
            if (Files.isRegularFile(cache)) return Files.readAllBytes(cache);
            byte[] mini = redimensionar(original, lado);
            if (mini == null) return null;
            Files.write(cache, mini);
            return mini;
        } catch (IOException e) {
            return null;
        }
    }

    public void invalidarMiniaturas(Long id) {
        for (int lado : new int[]{64, 160, 300, 640}) {
            try {
                Files.deleteIfExists(carpeta("cache/portadas").resolve(id + "_" + lado + ".jpg"));
            } catch (IOException ignored) { }
        }
    }

    /** Redimensiona y recomprime a JPEG. Devuelve null si no es una imagen legible. */
    public byte[] redimensionar(byte[] datos, int lado) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(datos));
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            double escala = Math.min(1.0, (double) lado / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * escala)), nh = Math.max(1, (int) Math.round(h * escala));
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.86f);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(out, null, null), param);
            } finally {
                writer.dispose();
            }
            return bos.toByteArray();
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static String detectarFormato(byte[] d) {
        if (d.length < 12) return null;
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return "gif";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "webp";
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return "jpg";
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        return null;
    }

    public static String tipoDeBytes(byte[] d) {
        String f = detectarFormato(d);
        return f == null ? "image/jpeg" : tipoContenido("x." + f);
    }

    // ═══════════════════ Portada generada ═══════════════════

    /** Imagen SVG con degradado y una nota musical, distinta para cada canción. */
    public static byte[] portadaGenerada(long semilla, String texto) {
        int hue = (int) Math.floorMod(semilla * 47L, 360L);
        int hue2 = (hue + 40) % 360;
        String inicial = (texto == null || texto.isBlank()) ? "" : texto.trim().substring(0, 1).toUpperCase();
        inicial = inicial.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 300">
              <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="hsl(%d,55%%,38%%)"/><stop offset="1" stop-color="hsl(%d,60%%,16%%)"/>
              </linearGradient></defs>
              <rect width="300" height="300" fill="url(#g)"/>
              <g fill="none" stroke="rgba(255,255,255,.55)" stroke-width="10" stroke-linecap="round" stroke-linejoin="round">
                <path d="M128 196V96l78-16v100"/>
                <circle cx="108" cy="196" r="20" fill="rgba(255,255,255,.55)" stroke="none"/>
                <circle cx="186" cy="180" r="20" fill="rgba(255,255,255,.55)" stroke="none"/>
              </g>
              <text x="24" y="276" font-family="system-ui,sans-serif" font-size="34" font-weight="700" fill="rgba(255,255,255,.35)">%s</text>
            </svg>""".formatted(hue, hue2, inicial);
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    public File base() {
        return base.toFile();
    }
}
