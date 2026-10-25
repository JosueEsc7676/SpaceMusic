// config/DataLoader.java - Soporte MP3 + FLAC
package com.bpm.music.config;

import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.services.Mp3MetadataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

@Configuration
@EnableScheduling
public class DataLoader {

    @Autowired
    private CancionRepository repo;

    @Autowired
    private Mp3MetadataService metadataService;

    @Autowired
    private DataSource dataSource;

    @Value("${music.path:E:/}")
    private String ruta;

    private boolean escaneando = false;
    private int erroresConsecutivos = 0;
    private static final int MAX_ERRORES_CONSECUTIVOS = 3;

    // ✅ Extensiones de audio soportadas
    private static final String[] EXTENSIONES_SOPORTADAS = { ".mp3", ".flac" };

    private static final String[] CARPETAS_EXCLUIDAS = {
            "$RECYCLE.BIN", "System Volume Information", "temp", "tmp",
            "cache", "thumb", "Thumbs.db", ".spotlight", ".Trashes",
            "lost+found", "node_modules", ".git", ".svn", "__MACOSX"
    };

    @Bean
    public CommandLineRunner cargarCancionesInicial() {
        return args -> {
            System.out.println("🚀 Cargando canciones iniciales...");
            verificarConexion();
            escanearYCargarCanciones();
        };
    }

    @Scheduled(fixedDelay = 180000, timeUnit = TimeUnit.SECONDS, initialDelay = 30)
    public void escanearPeriodicamente() {
        if (!escaneando) {
            if (!verificarConexion()) {
                System.err.println("⚠️ No hay conexión a BD, reintentando más tarde...");
                return;
            }
            escanearYCargarCanciones();
        }
    }

    private boolean verificarConexion() {
        try {
            Connection conn = dataSource.getConnection();
            boolean isValid = conn.isValid(5);
            conn.close();
            if (isValid) {
                if (erroresConsecutivos > 0) {
                    System.out.println("✅ Conexión a BD restablecida");
                    erroresConsecutivos = 0;
                }
                return true;
            }
            return false;
        } catch (SQLException e) {
            System.err.println("❌ Error de conexión a BD: " + e.getMessage());
            erroresConsecutivos++;
            return false;
        }
    }

    private boolean esCarpetaExcluida(String nombre) {
        for (String excluida : CARPETAS_EXCLUIDAS) {
            if (nombre.equalsIgnoreCase(excluida) || nombre.contains(excluida)) {
                return true;
            }
        }
        return false;
    }

    // ✅ Renombrado y generalizado para MP3 + FLAC
    private boolean esArchivoAudioValido(File archivo) {
        String nombre = archivo.getName().toLowerCase();

        // Verificar extensión soportada
        boolean extensionValida = false;
        for (String ext : EXTENSIONES_SOPORTADAS) {
            if (nombre.endsWith(ext)) {
                extensionValida = true;
                break;
            }
        }
        if (!extensionValida) return false;

        // Ignorar archivos temporales o del sistema
        if (nombre.startsWith("~") || nombre.startsWith(".") || nombre.contains("temp")) {
            return false;
        }

        // FLAC puede ser más liviano que MP3 para el mismo contenido,
        // pero archivos menores a 100KB casi seguro son corruptos
        if (archivo.length() < 100 * 1024) {
            System.out.println("   ⚠️ Archivo muy pequeño, ignorado: " + archivo.getName());
            return false;
        }

        return true;
    }

    private synchronized void escanearYCargarCanciones() {
        if (escaneando) {
            System.out.println("⚠️ Escaneo en curso, omitiendo...");
            return;
        }

        escaneando = true;

        if (erroresConsecutivos >= MAX_ERRORES_CONSECUTIVOS) {
            System.err.println("⚠️ Demasiados errores consecutivos, esperando 30 segundos...");
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            erroresConsecutivos = 0;
            escaneando = false;
            return;
        }

        try {
            File carpeta = new File(ruta);
            System.out.println("📁 Buscando música en: " + carpeta.getAbsolutePath());

            if (!carpeta.exists() || !carpeta.isDirectory()) {
                System.err.println("❌ Ruta inválida: " + ruta);
                return;
            }

            java.util.List<File> archivosAudio = new java.util.ArrayList<>();
            buscarAudioRecursivoConFiltro(carpeta, archivosAudio);

            System.out.println("📊 Total archivos de audio encontrados: " + archivosAudio.size());
            System.out.println("📊 Canciones actuales en BD: " + repo.count());

            int nuevas = 0, existentes = 0, errores = 0;

            for (File file : archivosAudio) {
                try {
                    if (repo.existsByRutaArchivo(file.getAbsolutePath())) {
                        existentes++;
                        continue;
                    }

                    System.out.println("\n📁 Nueva canción: " + file.getName());

                    Map<String, String> metadatos = metadataService.extraerMetadatos(file);

                    String titulo  = metadatos.getOrDefault("titulo", "");
                    String artista = metadatos.getOrDefault("artista", "");
                    String album   = metadatos.getOrDefault("album", "");
                    String genero  = metadatos.getOrDefault("genero", "");
                    String letra   = metadatos.getOrDefault("letra", "");
                    String letraSincronizada = metadatos.getOrDefault("letraSincronizada", "");

                    int duracion;
                    try {
                        duracion = Integer.parseInt(metadatos.getOrDefault("duracionSegundos", "0"));
                    } catch (Exception e) {
                        duracion = 0;
                    }

                    String nombreLimpio = limpiarNombreArchivo(file.getName());
                    if (titulo == null || titulo.isEmpty())  titulo  = nombreLimpio;
                    if (artista == null || artista.isEmpty()) artista = "Desconocido";
                    if (album == null || album.isEmpty())    album   = "Single";
                    if (genero == null || genero.isEmpty())  genero  = "Desconocido";

                    byte[] portada = metadataService.extraerPortada(file);

                    Cancion nuevaCancion = Cancion.builder()
                            .nombre(titulo)
                            .artista(artista)
                            .album(album)
                            .genero(genero)
                            .rutaArchivo(file.getAbsolutePath())
                            .portada(portada)
                            .portadaUrl("")
                            .reproducciones(0)
                            .favorito(false)
                            .duracionSegundos(duracion)
                            .letra(letra != null ? letra : "")
                            .letraSincronizada(letraSincronizada != null ? letraSincronizada : "")
                            .build();

                    repo.save(nuevaCancion);
                    System.out.println("✅ Agregada: " + artista + " - " + titulo
                            + " [" + getExtension(file.getName()).toUpperCase() + "]");
                    nuevas++;

                } catch (Exception e) {
                    System.err.println("❌ Error: " + file.getName() + " - " + e.getMessage());
                    errores++;
                }
            }

            System.out.println("\n📊 RESUMEN:");
            System.out.println("   - Nuevas: "      + nuevas);
            System.out.println("   - Existentes: "  + existentes);
            System.out.println("   - Errores: "     + errores);
            System.out.println("   - Total BD: "    + repo.count());

            if (errores == 0) erroresConsecutivos = 0;

        } catch (Exception e) {
            System.err.println("❌ Error grave: " + e.getMessage());
            erroresConsecutivos++;
        } finally {
            escaneando = false;
        }
    }

    // ✅ Renombrado de buscarMP3RecursivoConFiltro → buscarAudioRecursivoConFiltro
    private void buscarAudioRecursivoConFiltro(File directorio, java.util.List<File> lista) {
        File[] archivos = directorio.listFiles();
        if (archivos == null) return;

        for (File archivo : archivos) {
            if (archivo.isDirectory()) {
                if (!esCarpetaExcluida(archivo.getName())) {
                    buscarAudioRecursivoConFiltro(archivo, lista);
                }
            } else if (esArchivoAudioValido(archivo)) {
                lista.add(archivo);
            }
        }
    }

    private String limpiarNombreArchivo(String nombre) {
        try {
            // Quitar la extensión (.mp3 o .flac)
            int lastDot = nombre.lastIndexOf('.');
            String sinExt = lastDot > 0 ? nombre.substring(0, lastDot) : nombre;
            return sinExt.replace("_", " ").replace(".", " ").trim();
        } catch (Exception e) {
            return nombre.replace(".mp3", "").replace(".flac", "").replace("_", " ");
        }
    }

    // ✅ Helper: devuelve la extensión sin el punto
    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(idx + 1).toLowerCase() : "";
    }
}
