package com.bpm.music.externo;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.services.BibliotecaService;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ¿Es la misma canción? Compara título, artista, duración y versión. Una versión en vivo, remix,
 * acústica, cover, karaoke, instrumental o acelerada NO es la original (y viceversa).
 */
public final class Coincidencias {

    private Coincidencias() {}

    public enum Resultado { MISMA, AMBIGUA, DISTINTA }

    private static final Pattern VERSION = Pattern.compile(
            "\\b(en vivo|live|remix|rmx|acustic[oa]|acoustic|cover|karaoke|instrumental|sped up|slowed|nightcore|8d|"
                    + "version|versión|edit|radio edit|extended|unplugged|demo|reprise|bachata version|cumbia version)\\b");
    private static final Pattern PARENTESIS = Pattern.compile("[\\(\\[]([^\\)\\]]*)[\\)\\]]");
    private static final Pattern FEAT = Pattern.compile("\\b(feat\\.?|ft\\.?|featuring|con|with)\\b.*$");

    /** Etiquetas de versión de un título ("Hilito (En Vivo)" → {en vivo}). */
    public static Set<String> versiones(String titulo) {
        String t = BibliotecaService.normalizar(titulo);
        Set<String> r = new TreeSet<>();
        Matcher m = VERSION.matcher(t);
        while (m.find()) {
            String v = m.group(1);
            // "remastered", "remaster" no son otra versión de la canción
            r.add(v.equals("rmx") ? "remix" : v.startsWith("acust") ? "acoustic" : v);
        }
        r.remove("version"); r.remove("versión"); r.remove("edit"); // sueltas no dicen nada
        return r;
    }

    /** Título base sin paréntesis, "feat." ni signos ("Propuesta Indecente (feat. X) [Remastered]" → "propuesta indecente"). */
    public static String base(String titulo) {
        String t = PARENTESIS.matcher(titulo == null ? "" : titulo).replaceAll(" ");
        t = t.replaceAll("\\s+-\\s+.*$", ""); // "Canción - Remastered 2011"
        t = BibliotecaService.normalizar(t);
        t = FEAT.matcher(t).replaceAll("");
        return t.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Artistas normalizados ("Bad Bunny, Jhay Cortez" / "Bad Bunny feat. X" → [bad bunny, jhay cortez]). */
    public static Set<String> artistas(String texto) {
        Set<String> r = new LinkedHashSet<>();
        if (texto == null) return r;
        for (String a : texto.split("(?i)\\s*(,|;|&| feat\\.? | ft\\.? | featuring | x | y | with )\\s*"))
            if (!a.isBlank()) r.add(BibliotecaService.normalizar(a.trim()));
        return r;
    }

    /**
     * Compara una canción externa (título, artistas, duración en segundos o null) con una local.
     * MISMA: mismo título base, versión igual, algún artista en común y duración a ±4 s (si se conoce).
     * AMBIGUA: casi (falta la duración o difiere un poco, o los artistas no coinciden del todo).
     */
    public static Resultado comparar(String titulo, Collection<String> artistasExt, Integer duracion, CancionDTO local) {
        if (local == null || titulo == null) return Resultado.DISTINTA;
        if (!base(titulo).equals(base(local.getNombre())) || base(titulo).isEmpty()) return Resultado.DISTINTA;
        if (!versiones(titulo).equals(versiones(local.getNombre()))) return Resultado.DISTINTA;
        Set<String> loc = artistas(local.getArtista());
        boolean artistaComun = false;
        for (String a : artistasExt == null ? List.<String>of() : artistasExt)
            for (String n : artistas(a)) if (loc.contains(n) || loc.stream().anyMatch(l -> l.contains(n) || n.contains(l))) artistaComun = true;
        if (!artistaComun) return Resultado.AMBIGUA;
        if (duracion == null || duracion <= 0 || local.getDuracionSegundos() <= 0) return Resultado.AMBIGUA;
        int dif = Math.abs(duracion - local.getDuracionSegundos());
        if (dif <= 4) return Resultado.MISMA;
        return dif <= 15 ? Resultado.AMBIGUA : Resultado.DISTINTA;
    }

    /** La mejor coincidencia en una lista de canciones locales (MISMA primero; si hay dos MISMA distintas, ambigua). */
    public static Map.Entry<CancionDTO, Resultado> buscar(String titulo, Collection<String> artistas, Integer duracion, Collection<CancionDTO> locales) {
        CancionDTO misma = null, ambigua = null;
        int mismas = 0;
        for (CancionDTO c : locales) {
            Resultado r = comparar(titulo, artistas, duracion, c);
            if (r == Resultado.MISMA) { mismas++; if (misma == null) misma = c; }
            else if (r == Resultado.AMBIGUA && ambigua == null) ambigua = c;
        }
        if (mismas == 1) return Map.entry(misma, Resultado.MISMA);
        if (mismas > 1) return Map.entry(misma, Resultado.AMBIGUA); // dos copias locales: revisión
        if (ambigua != null) return Map.entry(ambigua, Resultado.AMBIGUA);
        return null;
    }
}
