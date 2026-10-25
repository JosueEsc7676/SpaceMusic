package com.bpm.music.services;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee las etiquetas de los archivos de audio (MP3, FLAC, M4A, OGG, WAV)
 * en una sola pasada, más los archivos .lrc y cover.jpg de la misma carpeta.
 */
@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    public static final Pattern LRC_TIMESTAMP = Pattern.compile("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]");
    private static final Pattern NUMERO = Pattern.compile("(\\d{1,4})");
    private static final Pattern PISTA_EN_NOMBRE = Pattern.compile("^(\\d{1,3})[\\s._-]+(.+)$");
    private static final String[] PORTADAS_CARPETA = {
            "cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png",
            "front.jpg", "front.png", "album.jpg", "album.png"
    };

    static {
        java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    public record Metadatos(
            String titulo, String artista, String album, String genero,
            String letra, String letraSincronizada,
            int duracionSegundos, Integer numeroPista, Integer anio, Integer bitrate,
            String formato, byte[] portada) {}

    public Metadatos leer(File archivo) {
        String titulo = null, artista = null, album = null, genero = null, letraTag = null;
        Integer pista = null, anio = null, bitrate = null;
        int duracion = 0;
        byte[] portada = null;

        try {
            AudioFile af = AudioFileIO.read(archivo);
            AudioHeader header = af.getAudioHeader();
            if (header != null) {
                duracion = header.getTrackLength();
                long br = header.getBitRateAsNumber();
                if (br > 0) bitrate = (int) br;
            }
            Tag tag = af.getTag();
            if (tag != null) {
                titulo = limpio(tag.getFirst(FieldKey.TITLE));
                artista = primerArtista(limpio(primero(tag, FieldKey.ARTIST, FieldKey.ALBUM_ARTIST)));
                album = limpio(tag.getFirst(FieldKey.ALBUM));
                genero = limpiarGenero(tag.getFirst(FieldKey.GENRE));
                letraTag = tag.getFirst(FieldKey.LYRICS);
                pista = numero(tag.getFirst(FieldKey.TRACK), 1, 999);
                anio = numero(tag.getFirst(FieldKey.YEAR), 1000, 2999);
                Artwork art = tag.getFirstArtwork();
                if (art != null && art.getBinaryData() != null && art.getBinaryData().length > 0) {
                    portada = art.getBinaryData();
                }
            }
        } catch (Exception e) {
            log.debug("No se pudieron leer etiquetas de {}: {}", archivo.getName(), e.getMessage());
        }

        // ── Datos del nombre del archivo si faltan etiquetas ──
        String base = sinExtension(archivo.getName());
        Matcher mp = PISTA_EN_NOMBRE.matcher(base);
        if (mp.matches()) {
            if (pista == null) pista = numero(mp.group(1), 1, 999);
            base = mp.group(2);
        }
        if (titulo == null || artista == null) {
            String[] partes = base.split("\\s+-\\s+", 2);
            if (partes.length == 2) {
                if (artista == null) artista = limpio(partes[0]);
                if (titulo == null) titulo = limpio(partes[1]);
            }
        }
        if (titulo == null) titulo = limpio(base.replace('_', ' '));
        if (titulo == null) titulo = archivo.getName();
        if (artista == null) artista = "Desconocido";
        if (album == null) {
            // Si la carpeta no es la raíz, su nombre suele ser el álbum
            album = "Single";
        }
        if (genero == null) genero = "Desconocido";

        // ── Letras: etiqueta embebida o archivo .lrc al lado ──
        String letra = "", lrc = "";
        if (letraTag != null && !letraTag.isBlank()) {
            if (LRC_TIMESTAMP.matcher(letraTag).find()) lrc = letraTag.trim();
            else letra = limpiarLetra(letraTag);
        }
        String lrcArchivo = leerLrcAlLado(archivo);
        if (lrcArchivo != null) lrc = lrcArchivo;

        // ── Portada de la carpeta si no hay embebida ──
        if (portada == null) portada = leerPortadaCarpeta(archivo.getParentFile());

        return new Metadatos(titulo, artista, album, genero, letra, lrc, duracion,
                pista, anio, bitrate, extension(archivo.getName()), portada);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static String primero(Tag tag, FieldKey... keys) {
        for (FieldKey k : keys) {
            try {
                String v = tag.getFirst(k);
                if (v != null && !v.isBlank()) return v;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String limpio(String s) {
        if (s == null) return null;
        s = s.replace('\u0000', ' ').trim();
        if (s.isEmpty()) return null;
        return s.length() > 250 ? s.substring(0, 250) : s;
    }

    /**
     * Programas como spotDL juntan varios artistas con "/" ("Fred again../LATIN MAFIA").
     * Se usa el primero, salvo nombres cortos como "AC/DC".
     */
    static String primerArtista(String a) {
        if (a == null) return null;
        String[] partes = a.split("\\s*[/;]\\s*");
        if (partes.length < 2) return a;
        for (String p : partes) if (p.length() < 3) return a;
        return partes[0].trim();
    }

    /** "(17)" o "17" (códigos ID3v1) → nombre del género; deja el texto tal cual. */
    private static String limpiarGenero(String g) {
        g = limpio(g);
        if (g == null) return null;
        Matcher m = Pattern.compile("^\\((\\d+)\\)(.*)$").matcher(g);
        if (m.matches()) {
            String resto = m.group(2).trim();
            if (!resto.isEmpty()) return resto;
            return generoId3(Integer.parseInt(m.group(1)));
        }
        if (g.matches("\\d{1,3}")) return generoId3(Integer.parseInt(g));
        return g;
    }

    private static final String[] GENEROS_ID3 = {
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
            "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop",
            "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
            "Sound Clip", "Gospel", "Noise", "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative",
            "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial",
            "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
            "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave",
            "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka",
            "Retro", "Musical", "Rock & Roll", "Hard Rock"
    };

    private static String generoId3(int i) {
        return (i >= 0 && i < GENEROS_ID3.length) ? GENEROS_ID3[i] : null;
    }

    private static Integer numero(String s, int min, int max) {
        if (s == null) return null;
        Matcher m = NUMERO.matcher(s);
        if (!m.find()) return null;
        try {
            int n = Integer.parseInt(m.group(1));
            return (n >= min && n <= max) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String limpiarLetra(String letra) {
        letra = letra.replace("\r\n", "\n").trim();
        letra = letra.replaceAll("(?is)^\\d*\\s*contributors?.*?lyrics", "");
        letra = letra.replaceAll("(?i)\\d*embed$", "");
        return letra.trim();
    }

    private static String leerLrcAlLado(File audio) {
        File dir = audio.getParentFile();
        if (dir == null) return null;
        File lrc = new File(dir, sinExtension(audio.getName()) + ".lrc");
        if (!lrc.isFile() || lrc.length() > 512 * 1024) return null;
        try {
            byte[] bytes = Files.readAllBytes(lrc.toPath());
            String texto = new String(bytes, StandardCharsets.UTF_8);
            if (texto.contains("�")) texto = new String(bytes, StandardCharsets.ISO_8859_1);
            if (texto.startsWith("﻿")) texto = texto.substring(1);
            return LRC_TIMESTAMP.matcher(texto).find() ? texto.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] leerPortadaCarpeta(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] archivos = dir.listFiles();
        if (archivos == null) return null;
        for (String nombre : PORTADAS_CARPETA) {
            for (File f : archivos) {
                if (f.isFile() && f.getName().equalsIgnoreCase(nombre) && f.length() < 10 * 1024 * 1024) {
                    try {
                        return Files.readAllBytes(f.toPath());
                    } catch (Exception ignored) { }
                }
            }
        }
        return null;
    }

    public static String sinExtension(String nombre) {
        int i = nombre.lastIndexOf('.');
        return i > 0 ? nombre.substring(0, i) : nombre;
    }

    public static String extension(String nombre) {
        int i = nombre.lastIndexOf('.');
        return i > 0 ? nombre.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }
}
