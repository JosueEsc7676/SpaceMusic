package com.bpm.music.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Ajuste compartido LRC: positivo retrasa la letra; no altera tiempos ni palabras originales. */
public final class SincroniaLetra {
    private SincroniaLetra() {}
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    public static double segundos(String lrc) {
        var m = OFFSET.matcher(lrc == null ? "" : lrc);
        if (!m.find()) return 0;
        double n = -Double.parseDouble(m.group(1)) / 1000;
        return Double.isFinite(n) && n != 0 ? n : 0;
    }

    public static String revision(String lrc) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((lrc == null ? "" : lrc).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String ajustar(String lrc, Double segundos) {
        if (segundos == null || !Double.isFinite(segundos) || Math.abs(segundos) > 60)
            throw new IllegalArgumentException("El ajuste debe estar entre −60 y +60 segundos");
        if (lrc == null || !MetadataService.LRC_TIMESTAMP.matcher(lrc).find())
            throw new IllegalArgumentException("Esta canción no tiene letra sincronizada");
        return "[offset:" + Math.round(-segundos * 1000) + "]\n" + OFFSET.matcher(lrc).replaceAll("").strip();
    }
}
