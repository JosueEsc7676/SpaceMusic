// services/Mp3MetadataService.java
// ✅ Soporta MP3 y FLAC — jaudiotagger maneja ambos formatos nativamente
package com.bpm.music.services;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class Mp3MetadataService {

    // ✅ Silenciar el logger de jaudiotagger que es muy verbose
    static {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.SEVERE);
    }

    /**
     * Extrae metadatos de archivos MP3 y FLAC.
     * jaudiotagger soporta ambos formatos sin configuración extra.
     */
    public Map<String, String> extraerMetadatos(File archivo) {
        Map<String, String> metadatos = new HashMap<>();
        metadatos.put("titulo",            "");
        metadatos.put("artista",           "");
        metadatos.put("album",             "");
        metadatos.put("genero",            "");
        metadatos.put("letra",             "");
        metadatos.put("letraSincronizada", "");
        metadatos.put("duracionSegundos",  "0");

        try {
            if (!archivo.exists() || !archivo.canRead()) {
                System.err.println("   ❌ Archivo no accesible: " + archivo.getName());
                return metadatos;
            }

            // ✅ AudioFileIO.read() detecta el formato automáticamente
            // soporta: MP3, FLAC, OGG, M4A, WAV, AIFF, WMA, DSF, OPUS
            AudioFile audioFile = AudioFileIO.read(archivo);
            Tag tag = audioFile.getTag();

            if (tag != null) {
                String titulo  = tag.getFirst(FieldKey.TITLE);
                String artista = tag.getFirst(FieldKey.ARTIST);
                String album   = tag.getFirst(FieldKey.ALBUM);
                String genero  = tag.getFirst(FieldKey.GENRE);
                String letra   = tag.getFirst(FieldKey.LYRICS);

                if (titulo  != null && !titulo.isEmpty())  metadatos.put("titulo",  titulo);
                if (artista != null && !artista.isEmpty()) metadatos.put("artista", artista);
                if (album   != null && !album.isEmpty())   metadatos.put("album",   album);
                if (genero  != null && !genero.isEmpty())  metadatos.put("genero",  genero);
                if (letra   != null && !letra.isEmpty())   metadatos.put("letra",   limpiarLetra(letra));
            }

            int duracion = audioFile.getAudioHeader().getTrackLength();
            metadatos.put("duracionSegundos", String.valueOf(duracion));

            String formato = getFormato(archivo.getName());
            System.out.println("   📊 Metadatos [" + formato + "]:");
            System.out.println("      Título: "   + metadatos.get("titulo"));
            System.out.println("      Artista: "  + metadatos.get("artista"));
            System.out.println("      Álbum: "    + metadatos.get("album"));
            System.out.println("      Duración: " + duracion + "s");

        } catch (Exception e) {
            System.err.println("   ❌ Error leyendo metadatos de "
                    + archivo.getName() + ": " + e.getMessage());
        }

        return metadatos;
    }

    /**
     * Extrae la portada embebida. Funciona igual para MP3 (ID3) y FLAC (VorbisComment).
     */
    public byte[] extraerPortada(File archivo) {
        try {
            AudioFile audioFile = AudioFileIO.read(archivo);
            Tag tag = audioFile.getTag();

            if (tag != null) {
                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null) {
                    byte[] imageData = artwork.getBinaryData();
                    if (imageData != null && imageData.length > 0) {
                        System.out.println("   🖼️ Portada encontrada: " + imageData.length + " bytes");
                        return imageData;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("   📷 Sin portada en: " + archivo.getName());
        }
        return null;
    }

    // =============================================
    // HELPERS
    // =============================================

    private String limpiarLetra(String letra) {
        if (letra == null) return "";
        letra = letra.trim();
        letra = letra.replaceAll("(?i)contributors.*?lyrics", "");
        letra = letra.replaceAll("(?i)lyrics by.*", "");
        letra = letra.replaceAll("\\[.*?\\]", "");
        return letra.trim();
    }

    private String getFormato(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(idx + 1).toUpperCase() : "UNKNOWN";
    }
}
