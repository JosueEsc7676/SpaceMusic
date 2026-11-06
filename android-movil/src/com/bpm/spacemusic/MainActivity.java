package com.bpm.spacemusic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import java.lang.ref.WeakReference;

/**
 * SpaceMusic para teléfonos Android.
 *
 * Muestra la versión móvil de la web (mismo diseño) y añade lo que un navegador no puede:
 *  - la música sigue sonando con la pantalla bloqueada o en otra app (ReproductorService),
 *  - controles en la notificación, la pantalla de bloqueo y los auriculares,
 *  - abre sin conexión con lo guardado en el dispositivo (service worker de la web).
 */
public class MainActivity extends Activity {

    static final String SERVIDOR_POR_DEFECTO = "https://spacemusic.tail8ccfdf.ts.net";
    private static final String PREFS = "spacemusic";
    private static final int ELEGIR_ARCHIVO = 41;

    /** La notificación envía las órdenes a la web a través de esta referencia. */
    static WeakReference<MainActivity> actual = new WeakReference<>(null);

    private WebView web;
    private SharedPreferences prefs;
    private boolean errorMostrado = false;
    private ValueCallback<Uri[]> archivoPendiente;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        actual = new WeakReference<>(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // el sonido de inicio y la reproducción automática funcionan
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);                           // el diseño no cambia con el tamaño de letra del sistema
        s.setUserAgentString(s.getUserAgentString() + " SpaceMusicApp/1.0");

        // Seguridad: la web no puede leer archivos del teléfono y nadie puede inspeccionar la app por USB
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Puente(), "SpaceMusicApp");
        WebView.setWebContentsDebuggingEnabled(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                // Subir canciones, portadas o la foto de perfil desde el teléfono
                if (archivoPendiente != null) archivoPendiente.onReceiveValue(null);
                archivoPendiente = cb;
                try {
                    startActivityForResult(p.createIntent(), ELEGIR_ARCHIVO);
                } catch (Exception e) {
                    archivoPendiente = null;
                    return false;
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                Uri base = Uri.parse(servidor());
                if (u.getHost() != null && u.getHost().equals(base.getHost())) return false;
                // Enlaces externos: se abren en el navegador
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !errorMostrado) {
                    errorMostrado = true;
                    dialogoError(err.getDescription() != null ? err.getDescription().toString() : "Error de red");
                }
            }
        });

        // Enlaces de descarga (botón "Descargar" de la web): se guardan en Música/SpaceMusic
        web.setDownloadListener((url, agente, disposicion, tipo, largo) -> {
            if (mismoServidor(url)) Descargas.cancion(this, url, android.webkit.URLUtil.guessFileName(url, disposicion, tipo));
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else cargar();
        pedirPermisoNotificaciones();
        // Si la app se abrió desde una notificación, ir a esa pantalla cuando cargue
        final Intent inicio = getIntent();
        web.postDelayed(() -> abrirRuta(inicio), 4000);
    }

    /** Al tocar una notificación con la app abierta. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        abrirRuta(intent);
    }

    private void abrirRuta(Intent intent) {
        String ruta = intent == null ? null : intent.getStringExtra("ruta");
        if (ruta == null || ruta.isEmpty() || web == null) return;
        // Solo pantallas de la app ("#/mensajes/ana"…): nada de código desde otras apps
        if (!ruta.matches("#/[\\w\\-/%?=&.]{0,160}")) return;
        String r = ruta;
        web.evaluateJavascript("window.smAbrir ? window.smAbrir('" + r + "') : (location.hash='" + r + "')", null);
    }

    /** true si la dirección es del servidor de SpaceMusic (con conexión segura). */
    boolean mismoServidor(String url) {
        try {
            Uri u = Uri.parse(url), base = Uri.parse(servidor());
            return "https".equals(u.getScheme()) && u.getHost() != null && u.getHost().equalsIgnoreCase(base.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    String servidor() {
        String s = prefs.getString("servidor", SERVIDOR_POR_DEFECTO).trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void cargar() {
        errorMostrado = false;
        web.loadUrl(servidor() + "/");
    }

    private void pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    /** Envía una orden del reproductor a la web (desde la notificación o los auriculares). */
    void orden(String accion, long valor) {
        if (web == null) return;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.smRemote && window.smRemote('" + accion + "', " + valor + ")", null));
    }

    // ═══════════════════ Botón Atrás ═══════════════════

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("(window.smBack ? window.smBack() : false)", v -> {
                    // En el inicio: la app pasa a segundo plano y la música sigue sonando
                    if (v == null || "false".equals(v) || "null".equals(v)) moveTaskToBack(true);
                });
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == ELEGIR_ARCHIVO && archivoPendiente != null) {
            archivoPendiente.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            archivoPendiente = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    // ═══════════════════ Diálogos ═══════════════════

    private void dialogoError(String detalle) {
        new AlertDialog.Builder(this)
                .setTitle("No se pudo conectar")
                .setMessage("No se pudo abrir " + servidor() + "\n\n" + detalle
                        + "\n\nRevisa tu conexión a internet. Las canciones guardadas en el teléfono siguen disponibles cuando la app ya se abrió antes.")
                .setPositiveButton("Reintentar", (d, w) -> cargar())
                .setNeutralButton("Cambiar servidor", (d, w) -> dialogoServidor())
                .setNegativeButton("Salir", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void dialogoServidor() {
        final EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        campo.setText(servidor());
        campo.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Dirección del servidor")
                .setMessage("Ejemplo: https://spacemusic.tail8ccfdf.ts.net")
                .setView(campo)
                .setPositiveButton("Guardar", (d, w) -> {
                    String v = campo.getText().toString().trim();
                    if (v.startsWith("http://")) v = "https://" + v.substring(7); // siempre conexión segura
                    if (!v.startsWith("https://")) v = "https://" + v;
                    prefs.edit().putString("servidor", v).apply();
                    cargar();
                })
                .setNeutralButton("Por defecto", (d, w) -> {
                    prefs.edit().remove("servidor").apply();
                    cargar();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ═══════════════════ Puente con la web ═══════════════════

    private class Puente {
        /** Estado del reproductor: actualiza la notificación y la pantalla de bloqueo. */
        @JavascriptInterface
        public void estado(boolean sonando, String titulo, String artista, String album, String portada,
                           int duracion, int posicion) {
            Intent i = new Intent(MainActivity.this, ReproductorService.class);
            i.setAction(ReproductorService.ACCION_ESTADO);
            i.putExtra("sonando", sonando);
            i.putExtra("titulo", titulo);
            i.putExtra("artista", artista);
            i.putExtra("album", album);
            i.putExtra("portada", portada);
            i.putExtra("duracion", duracion);
            i.putExtra("posicion", posicion);
            try {
                if (sonando && Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception e) {
                android.util.Log.w("SpaceMusic", "No se pudo actualizar el reproductor: " + e);
            }
        }

        /** No hay nada en reproducción: se quita la notificación. */
        @JavascriptInterface
        public void detener() {
            stopService(new Intent(MainActivity.this, ReproductorService.class));
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(MainActivity.this::dialogoServidor);
        }

        /** Cierra la app por completo: para la música, quita la notificación y termina el proceso. */
        @JavascriptInterface
        public void cerrarApp() {
            runOnUiThread(MainActivity.this::cerrarDelTodo);
        }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
        }

        /** Número de versión de la app instalada (para saber si hay una actualización). */
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public int versionCode() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
        }

        /** Guarda una canción en la carpeta Música/SpaceMusic del teléfono. */
        @JavascriptInterface
        public void guardarArchivo(String url, String nombre) {
            if (!mismoServidor(url)) return; // solo canciones de nuestro servidor
            runOnUiThread(() -> Descargas.cancion(MainActivity.this, url, nombre));
        }

        /** Notificación del sistema (mensaje nuevo, nuevo seguidor…). Al tocarla se abre esa pantalla. */
        @JavascriptInterface
        public void notificar(String titulo, String texto, String ruta) {
            runOnUiThread(() -> Avisos.mostrar(MainActivity.this, titulo, texto, ruta));
        }

        /** Descarga e instala la versión nueva de la app. */
        @JavascriptInterface
        public void actualizar(String url) {
            if (!mismoServidor(url)) return; // la actualización solo puede venir de nuestro servidor
            runOnUiThread(() -> Descargas.actualizar(MainActivity.this, url));
        }
    }

    void cerrarDelTodo() {
        stopService(new Intent(this, ReproductorService.class));
        if (web != null) { web.stopLoading(); web.loadUrl("about:blank"); }
        finishAndRemoveTask();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    // ═══════════════════ Ciclo de vida ═══════════════════
    // La vista web NO se pausa al salir de la app: así la música sigue sonando.

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, ReproductorService.class));
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
