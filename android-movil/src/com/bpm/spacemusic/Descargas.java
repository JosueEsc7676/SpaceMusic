package com.bpm.spacemusic;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.CookieManager;
import android.widget.Toast;

/**
 * Descargas con el gestor del sistema:
 *  - canciones → carpeta Música/SpaceMusic del teléfono (aparecen en "Descargas" y en otras apps de música),
 *  - actualizaciones de la app → se descargan y se abre el instalador.
 */
final class Descargas {

    static final int PERMISO_ALMACENAMIENTO = 52;
    private static long idActualizacion = -1;

    private Descargas() { }

    /** Android 9 o anterior necesita permiso para escribir en Música. */
    static boolean pedirPermisoSiHaceFalta(Activity a) {
        if (Build.VERSION.SDK_INT >= 29) return true;
        if (a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return true;
        a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISO_ALMACENAMIENTO);
        Toast.makeText(a, "Permite el acceso y vuelve a tocar Descargar", Toast.LENGTH_LONG).show();
        return false;
    }

    /** Guarda una canción en Música/SpaceMusic. */
    static void cancion(Activity a, String url, String nombreArchivo) {
        if (!pedirPermisoSiHaceFalta(a)) return;
        String nombre = limpiar(nombreArchivo);
        if (!nombre.toLowerCase().matches(".*\\.(mp3|m4a|flac|ogg|opus|wav)$")) nombre += ".mp3";
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(nombre)
                    .setDescription("SpaceMusic")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "SpaceMusic/" + nombre);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            if (Build.VERSION.SDK_INT < 29) r.allowScanningByMediaScanner();
            ((DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
        } catch (Exception e) {
            Toast.makeText(a, "No se pudo descargar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Guarda una imagen PNG (versos, dedicatoria, tarjeta) en Imágenes/SpaceMusic: aparece en la galería.
     * Antes la web intentaba un enlace de descarga «blob:» que la vista web de Android no sabe guardar.
     * Devuelve la dirección de la imagen guardada (para compartirla) o null si falló.
     */
    static Uri imagen(Activity a, byte[] datos, String nombreArchivo) {
        String nombre = limpiar(nombreArchivo);
        if (!nombre.toLowerCase().endsWith(".png")) nombre += ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, nombre);
                v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceMusic");
                v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
                android.content.ContentResolver cr = a.getContentResolver();
                Uri uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (java.io.OutputStream o = cr.openOutputStream(uri)) {
                    if (o == null) return null;
                    o.write(datos);
                }
                v.clear();
                v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                return uri;
            }
            if (!pedirPermisoSiHaceFalta(a)) return null;
            java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SpaceMusic");
            if (!dir.exists() && !dir.mkdirs()) return null;
            java.io.File f = new java.io.File(dir, nombre);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(datos); }
            android.media.MediaScannerConnection.scanFile(a, new String[]{f.getAbsolutePath()}, new String[]{"image/png"}, null);
            return Uri.fromFile(f);
        } catch (Exception e) {
            android.util.Log.w("SpaceMusic", "No se pudo guardar la imagen: " + e);
            return null;
        }
    }

    /** Descarga la versión nueva de la app y abre el instalador al terminar. */
    static void actualizar(final Activity a, String url) {
        DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("Actualización de SpaceMusic")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, "SpaceMusic-" + System.currentTimeMillis() + ".apk");
            idActualizacion = dm.enqueue(r);
            Toast.makeText(a, "Descargando la actualización…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(a, "No se pudo descargar la actualización", Toast.LENGTH_LONG).show();
            return;
        }
        BroadcastReceiver fin = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != idActualizacion) return;
                try { a.unregisterReceiver(this); } catch (Exception ignored) { }
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) {
                    Toast.makeText(a, "La descarga de la actualización falló", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent instalar = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { a.startActivity(instalar); } catch (Exception e) {
                    Toast.makeText(a, "Abre la actualización desde la notificación de descargas", Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(fin, f, Context.RECEIVER_EXPORTED);
        else a.registerReceiver(fin, f);
    }

    private static String limpiar(String s) {
        if (s == null || s.trim().isEmpty()) return "cancion";
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
