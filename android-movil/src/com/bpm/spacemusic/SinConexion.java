package com.bpm.spacemusic;

import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pantalla de respaldo sin conexión: cuando no se puede abrir el servidor ni la copia guardada
 * de la web, muestra las canciones descargadas en Música/SpaceMusic y las reproduce.
 * La página vive en una dirección inventada (https://sin-conexion.spacemusic/) que la app atiende
 * por dentro: nunca sale a internet.
 */
final class SinConexion {

    static final String HOST = "sin-conexion.spacemusic";
    static final String BASE = "https://" + HOST + "/";

    private SinConexion() { }

    static final class Cancion {
        long id;
        String titulo;
        String artista;
        long duracionMs;
    }

    /** Canciones guardadas por la app en la carpeta Música/SpaceMusic (las más nuevas primero). */
    static List<Cancion> canciones(Context c) {
        List<Cancion> lista = new ArrayList<>();
        Uri col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] campos = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME};
        String sel;
        String[] args;
        if (Build.VERSION.SDK_INT >= 29) {
            sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
            args = new String[]{"Music/SpaceMusic%"};
        } else {
            sel = MediaStore.MediaColumns.DATA + " LIKE ?";
            args = new String[]{"%/Music/SpaceMusic/%"};
        }
        try (Cursor k = c.getContentResolver().query(col, campos, sel, args, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (k == null) return lista;
            while (k.moveToNext()) {
                Cancion s = new Cancion();
                s.id = k.getLong(0);
                s.titulo = k.getString(1);
                s.artista = k.getString(2);
                s.duracionMs = k.getLong(3);
                // Los archivos se guardan como "Artista - Canción.mp3": sirve si no traen etiquetas
                String archivo = k.getString(4) == null ? "" : k.getString(4).replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
                int guion = archivo.indexOf(" - ");
                if (vacio(s.artista) && guion > 0) s.artista = archivo.substring(0, guion);
                if (vacio(s.titulo)) s.titulo = guion > 0 ? archivo.substring(guion + 3) : archivo;
                if (vacio(s.artista)) s.artista = "";
                lista.add(s);
            }
        } catch (Exception e) {
            android.util.Log.w("SpaceMusic", "No se pudieron leer las canciones: " + e);
        }
        return lista;
    }

    private static boolean vacio(String s) {
        return s == null || s.trim().isEmpty() || "<unknown>".equals(s);
    }

    /** La página de la pantalla sin conexión con la lista de canciones. */
    static String pagina(Context c, List<Cancion> lista, boolean faltaPermiso, boolean hayRed, String error) {
        String plantilla;
        try (InputStream in = c.getResources().openRawResource(R.raw.sin_conexion)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            plantilla = b.toString("UTF-8");
        } catch (IOException e) {
            return "<p>Sin conexión</p>";
        }
        StringBuilder h = new StringBuilder();
        if (faltaPermiso) {
            h.append("<div class=\"vacio\"><b>Permite el acceso a tu música</b><span>Para ver las canciones descargadas en el teléfono.</span>")
                    .append("<button onclick=\"SpaceMusicApp.permisoMusica()\">Permitir</button></div>");
        } else if (lista.isEmpty()) {
            h.append("<div class=\"vacio\"><b>No hay canciones descargadas</b><span>Cuando tengas internet, toca Descargar en una canción o playlist ")
                    .append("y quedará guardada en Música/SpaceMusic.</span></div>");
        } else {
            int i = 0;
            for (Cancion s : lista) {
                h.append("<button class=\"fila\" data-i=\"").append(i++).append("\" data-id=\"").append(s.id)
                        .append("\" data-t=\"").append(esc(s.titulo)).append("\" data-a=\"").append(esc(s.artista))
                        .append("\" data-d=\"").append(s.duracionMs / 1000).append("\"><span class=\"nota\">♪</span><span class=\"tt\"><b>")
                        .append(esc(s.titulo)).append("</b><small>").append(esc(s.artista)).append("</small></span><small class=\"dur\">")
                        .append(duracion(s.duracionMs)).append("</small></button>");
            }
        }
        return plantilla.replace("<!--CANTIDAD-->", lista.size() == 1 ? "1 canción" : lista.size() + " canciones")
                .replace("<!--LISTA-->", h.toString())
                .replace("<!--AVISO-->", hayRed
                        ? "El teléfono tiene internet pero no se pudo abrir el servidor. Se vuelve a intentar sola cada 15 segundos."
                        : "Sin conexión a internet. Aquí están las canciones descargadas en tu teléfono.")
                .replace("<!--DETALLE-->", error == null || error.trim().isEmpty() ? "" : "Detalle: " + esc(error))
                .replace("/*AUTO*/false", hayRed ? "true" : "false");
    }

    private static String duracion(long ms) {
        long s = ms / 1000;
        return s <= 0 ? "" : (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Entrega el audio de una canción guardada (con soporte para adelantar: respuestas parciales). */
    static WebResourceResponse audio(Context c, Uri url, Map<String, String> cabeceras) {
        try {
            List<String> partes = url.getPathSegments();
            if (partes.size() != 2 || !"cancion".equals(partes.get(0))) return null;
            long id = Long.parseLong(partes.get(1));
            Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            String tipo = c.getContentResolver().getType(u);
            if (tipo == null) tipo = "audio/mpeg";
            AssetFileDescriptor afd = c.getContentResolver().openAssetFileDescriptor(u, "r");
            if (afd == null) return null;
            long total = afd.getLength();
            InputStream in = afd.createInputStream();
            String rango = null;
            if (cabeceras != null) for (Map.Entry<String, String> e : cabeceras.entrySet()) {
                if ("range".equalsIgnoreCase(e.getKey())) rango = e.getValue();
            }
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Cache-Control", "no-store");
            if (rango != null && total > 0 && rango.startsWith("bytes=")) {
                String[] r = rango.substring(6).split("-", -1);
                long inicio = r[0].isEmpty() ? 0 : Long.parseLong(r[0].trim());
                long fin = r.length > 1 && !r[1].trim().isEmpty() ? Long.parseLong(r[1].trim()) : total - 1;
                fin = Math.min(fin, total - 1);
                if (inicio > fin) { in.close(); return null; }
                long saltado = 0;
                while (saltado < inicio) {
                    long n = in.skip(inicio - saltado);
                    if (n <= 0) break;
                    saltado += n;
                }
                long largo = fin - inicio + 1;
                h.put("Content-Range", "bytes " + inicio + "-" + fin + "/" + total);
                h.put("Content-Length", String.valueOf(largo));
                return new WebResourceResponse(tipo, null, 206, "Partial Content", h, new Limitado(in, largo));
            }
            if (total > 0) h.put("Content-Length", String.valueOf(total));
            return new WebResourceResponse(tipo, null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<String, String>(), null);
        }
    }

    /** Lee solo una parte del archivo (para las respuestas parciales). */
    private static final class Limitado extends FilterInputStream {
        private long quedan;

        Limitado(InputStream in, long largo) {
            super(in);
            quedan = largo;
        }

        @Override
        public int read() throws IOException {
            if (quedan <= 0) return -1;
            int b = super.read();
            if (b >= 0) quedan--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (quedan <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, quedan));
            if (n > 0) quedan -= n;
            return n;
        }
    }
}
