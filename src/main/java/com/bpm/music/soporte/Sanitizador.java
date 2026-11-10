package com.bpm.music.soporte;

import java.util.regex.Pattern;

/**
 * Limpia textos técnicos (mensajes de error, rutas, trazas) antes de guardarlos o mostrarlos en Soporte:
 * nunca deben quedar tokens, contraseñas, cabeceras de autorización ni correos completos.
 */
public final class Sanitizador {

    private Sanitizador() { }

    private static final Pattern TOKEN_URL = Pattern.compile("([?&](?:t|token|access_token|key|clave)=)[^&\\s\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("(Bearer\\s+)[A-Za-z0-9._\\-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}");
    private static final Pattern SECRETO = Pattern.compile("((?:password|passwd|contrase(?:ñ|n)a|secret|secreto|api[_-]?key|cookie)\\s*[=:]\\s*)[^\\s,;&\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CORREO = Pattern.compile("([A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]*(@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})");

    /** Texto seguro para guardar en registros de soporte (recortado a {@code max} caracteres). */
    public static String limpiar(String s, int max) {
        if (s == null) return "";
        String r = TOKEN_URL.matcher(s).replaceAll("$1[oculto]");
        r = BEARER.matcher(r).replaceAll("$1[oculto]");
        r = JWT.matcher(r).replaceAll("[token oculto]");
        r = SECRETO.matcher(r).replaceAll("$1[oculto]");
        r = CORREO.matcher(r).replaceAll("$1***$2");
        return r.length() > max ? r.substring(0, max) + "…" : r;
    }

    /** Ruta de una petición sin identificadores ("/api/canciones/123/audio" → "/api/canciones/{id}/audio"). */
    public static String patronRuta(String ruta) {
        if (ruta == null) return "";
        String r = ruta.split("\\?")[0];
        r = r.replaceAll("/\\d+(?=/|$)", "/{id}");
        // Nombres de álbum, artista, usuario… en rutas conocidas
        r = r.replaceAll("^(/api/(?:artistas|perfiles|mensajes|generos|letras|portada/db))/[^/]+", "$1/{x}");
        return r.length() > 120 ? r.substring(0, 120) : r;
    }

    /** IP recortada (privacidad): 192.168.10.18 → 192.168.10.x */
    public static String ipParcial(String ip) {
        if (ip == null || ip.isBlank()) return "";
        if (ip.contains(".")) {
            int i = ip.lastIndexOf('.');
            return ip.substring(0, i) + ".x";
        }
        int i = ip.lastIndexOf(':');
        return i > 0 ? ip.substring(0, i) + ":x" : "x";
    }
}
