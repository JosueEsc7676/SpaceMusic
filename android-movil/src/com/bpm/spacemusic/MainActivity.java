package com.bpm.spacemusic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.webkit.WebResourceResponse;
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
    /** Ya se intentó abrir la copia guardada de la web (sin conexión con el servidor). */
    private boolean probandoCopia = false;
    /** Se está mostrando la pantalla de respaldo con las canciones del teléfono. */
    private boolean enSinConexion = false;
    /** La web avisó que ya arrancó (SpaceMusicApp.lista()). */
    private volatile boolean paginaLista = false;
    /** Reintentos de abrir el servidor en este arranque y el último error (se muestra en la pantalla sin conexión). */
    private int intentosRed = 0;
    private String ultimoError = "";
    private ConnectivityManager.NetworkCallback vigilaRed;
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
        // Cuando cambia la salida (audífonos, Bluetooth…), la web lo muestra en «Dispositivos»
        Salidas.vigilar(this, json -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.smSalida && window.smSalida(" + json + ")", null);
        }));
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

            /** Mensajes de voz: la web pide el micrófono; se concede solo a nuestro servidor y con el permiso de Android. */
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest pedido) {
                runOnUiThread(() -> {
                    boolean audio = false;
                    for (String r : pedido.getResources()) if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                    if (!audio || !mismoServidor(pedido.getOrigin().toString() + "/")) { pedido.deny(); return; }
                    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        microfonoPendiente = pedido;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISO_MICROFONO);
                        return;
                    }
                    pedido.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                });
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                Uri base = Uri.parse(servidor());
                // Google no deja iniciar sesión dentro de una app: «Continuar con…» se abre en el navegador
                if (u.getHost() != null && u.getHost().equals(base.getHost()) && u.getPath() != null && u.getPath().startsWith("/api/auth/oauth/")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                if (u.getHost() != null && u.getHost().equals(base.getHost())) return false;
                // Enlaces externos: se abren en el navegador
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                // Audio de la pantalla sin conexión: se lee del teléfono, nunca sale a internet
                if (SinConexion.HOST.equals(req.getUrl().getHost())) {
                    return SinConexion.audio(MainActivity.this, req.getUrl(), req.getRequestHeaders());
                }
                return null;
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                // Nunca se acepta un certificado no válido; solo se anota para mostrarlo en la pantalla sin conexión
                ultimoError = "Certificado no aceptado por el teléfono (tipo " + error.getPrimaryError() + ")";
                handler.cancel();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame() || errorMostrado || enSinConexion) return;
                ultimoError = (err.getDescription() == null ? "" : err.getDescription().toString()) + " (" + err.getErrorCode() + ")";
                // Con internet, un fallo al abrir suele ser pasajero (la red se está levantando, el servidor tardó):
                // se reintenta hasta 3 veces antes de abrir la copia guardada o la pantalla sin conexión
                if (!probandoCopia && intentosRed < 3 && hayInternet()) {
                    intentosRed++;
                    view.postDelayed(() -> { if (web != null && !enSinConexion) web.loadUrl(servidor() + "/"); }, 1500L * intentosRed);
                    return;
                }
                if (!probandoCopia) {
                    // Sin conexión con el servidor: abrir la copia de SpaceMusic guardada en el teléfono
                    // (ahí funcionan el modo sin conexión y las canciones descargadas en la app)
                    probandoCopia = true;
                    view.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                    paginaLista = false;
                    view.post(() -> web.loadUrl(servidor() + "/"));
                    vigilarArranque();
                    return;
                }
                // Ni siquiera hay copia guardada: pantalla de respaldo con la música del teléfono
                errorMostrado = true;
                mostrarSinConexion();
            }
        });

        // Enlaces de descarga (botón "Descargar" de la web): se guardan en Música/SpaceMusic
        web.setDownloadListener((url, agente, disposicion, tipo, largo) -> {
            if (mismoServidor(url)) Descargas.cancion(this, url, android.webkit.URLUtil.guessFileName(url, disposicion, tipo));
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else cargar();
        pedirPermisoNotificaciones();
        vigilarRed();
        // Mensajes y avisos con la app cerrada (revisión periódica de Android)
        Revisar.programar(this);
        // Avisos al instante con UnifiedPush (si hay una app distribuidora como ntfy)
        Push.registrar(this);
        // Si la app se abrió desde una notificación, ir a esa pantalla cuando cargue
        final Intent inicio = getIntent();
        web.postDelayed(() -> abrirRuta(inicio), 4000);
        revisarAlarma(inicio);
    }

    /** Al tocar una notificación con la app abierta. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        abrirRuta(intent);
        revisarAlarma(intent);
    }

    /** Despertador: encender la pantalla (también sobre la pantalla de bloqueo) y avisar a la web para que suene. */
    private boolean alarmaPendiente = false;

    private void revisarAlarma(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(Despertador.EXTRA, false)) return;
        intent.removeExtra(Despertador.EXTRA);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("alarma").apply();
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        alarmaPendiente = true;
        if (paginaLista) sonarAlarma();
    }

    private void sonarAlarma() {
        if (!alarmaPendiente || web == null) return;
        alarmaPendiente = false;
        web.evaluateJavascript("window.smAlarma && window.smAlarma()", null);
        // La pantalla puede volver a apagarse sola después de un rato
        web.postDelayed(() -> getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON), 10 * 60_000L);
    }

    /** Vuelta del inicio de sesión con Google, Facebook o Apple: la web termina de entrar con el token. */
    private boolean volverDeOAuth(Intent intent) {
        Uri d = intent == null ? null : intent.getData();
        if (d == null || !"spacemusic".equals(d.getScheme()) || !"oauth".equals(d.getHost()) || web == null) return false;
        String token = d.getQueryParameter("token"), error = d.getQueryParameter("error");
        String q = token != null && token.matches("[A-Za-z0-9._-]{20,4096}") ? "token=" + Uri.encode(token)
                : "error=" + Uri.encode(error == null ? "No se pudo iniciar sesión" : error.length() > 300 ? error.substring(0, 300) : error);
        web.loadUrl(servidor() + "/login.html#oauth&" + q);
        return true;
    }

    private void abrirRuta(Intent intent) {
        if (volverDeOAuth(intent)) return;
        String ruta = intent == null ? null : intent.getStringExtra("ruta");
        if (ruta == null || ruta.isEmpty() || web == null) return;
        // Solo pantallas de la app ("#/mensajes/ana"…): nada de código desde otras apps
        if (!ruta.matches("#/[\\w\\-/%?=&.]{0,160}")) return;
        String r = ruta;
        web.evaluateJavascript("window.smAbrir ? window.smAbrir('" + r + "') : (location.hash='" + r + "')", null);
    }

    /** true si la dirección es del servidor de SpaceMusic (con conexión segura). */
    private static final int PERMISO_MICROFONO = 61;
    private android.webkit.PermissionRequest microfonoPendiente;

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
        enSinConexion = false;
        intentosRed = 0;
        // Sin internet se abre directamente la copia guardada (no hay que esperar a que falle)
        probandoCopia = !hayInternet();
        web.getSettings().setCacheMode(probandoCopia ? WebSettings.LOAD_CACHE_ELSE_NETWORK : WebSettings.LOAD_DEFAULT);
        paginaLista = false;
        web.loadUrl(servidor() + "/");
        if (probandoCopia) vigilarArranque();
    }

    /** Sin internet: si la web no arranca en unos segundos, se muestran las canciones del teléfono (nunca pantalla negra). */
    private void vigilarArranque() {
        web.postDelayed(() -> {
            if (web != null && !paginaLista && !enSinConexion && !errorMostrado) {
                errorMostrado = true;
                mostrarSinConexion();
            }
        }, 12000);
    }

    private boolean hayInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    /** Cuando vuelve la conexión: la web vuelve a pedir lo nuevo y, si estaba la pantalla de respaldo, se abre SpaceMusic. */
    private void vigilarRed() {
        if (Build.VERSION.SDK_INT < 24) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        vigilaRed = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    if (web == null) return;
                    // Volvió internet: lo nuevo se pide otra vez al servidor (las respuestas de la API nunca se guardan,
                    // así que mientras tanto la copia solo sirvió la interfaz)
                    web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                    if (enSinConexion) cargar();
                });
            }
        };
        try { cm.registerDefaultNetworkCallback(vigilaRed); } catch (Exception ignored) { vigilaRed = null; }
    }

    private boolean permisoMusica() {
        String p = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO" : Manifest.permission.READ_EXTERNAL_STORAGE;
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void pedirPermisoMusica() {
        String p = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO" : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{p}, 8);
    }

    /** Pantalla de respaldo: canciones descargadas en Música/SpaceMusic, para escuchar sin conexión. */
    void mostrarSinConexion() {
        enSinConexion = true;
        boolean permiso = permisoMusica();
        String html = SinConexion.pagina(this, permiso ? SinConexion.canciones(this) : new java.util.ArrayList<SinConexion.Cancion>(), !permiso,
                hayInternet(), ultimoError);
        web.loadDataWithBaseURL(SinConexion.BASE, html, "text/html", "UTF-8", null);
        if (!permiso) pedirPermisoMusica();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(req, permisos, resultados);
        if (req == PERMISO_MICROFONO && microfonoPendiente != null) {
            boolean ok = resultados.length > 0 && resultados[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) microfonoPendiente.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else microfonoPendiente.deny();
            microfonoPendiente = null;
        }
        if (req == 8 && enSinConexion && permisoMusica()) mostrarSinConexion();
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

        /**
         * Guarda una imagen (PNG en base64) en la galería, en Imágenes/SpaceMusic.
         * Con compartir=true abre después la hoja de compartir de Android (WhatsApp, Instagram…).
         * Devuelve "ok" si se guardó.
         */
        @JavascriptInterface
        public String guardarImagen(String base64, String nombre, boolean compartir) {
            if (base64 == null || base64.length() > 30_000_000) return "";
            final byte[] datos;
            try { datos = android.util.Base64.decode(base64, android.util.Base64.DEFAULT); } catch (Exception e) { return ""; }
            // PNG de verdad: empieza con la firma 89 50 4E 47
            if (datos.length < 8 || (datos[0] & 0xff) != 0x89 || datos[1] != 'P' || datos[2] != 'N' || datos[3] != 'G') return "";
            final Uri uri = Descargas.imagen(MainActivity.this, datos, nombre == null ? "spacemusic.png" : nombre);
            if (uri == null) return "";
            if (compartir && Build.VERSION.SDK_INT >= 29) {
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND).setType("image/png")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Compartir imagen"));
                    } catch (Exception ignored) { }
                });
            }
            return "ok";
        }

        /** Notificación del sistema (mensaje nuevo, nuevo seguidor…). Al tocarla se abre esa pantalla. */
        @JavascriptInterface
        public void notificar(String titulo, String texto, String ruta) {
            runOnUiThread(() -> Avisos.mostrar(MainActivity.this, titulo, texto, ruta));
        }

        /**
         * Línea de la letra que suena (y la siguiente) para la pantalla de bloqueo. Vacía: sin letra en este momento.
         */
        @JavascriptInterface
        public void letra(String linea, String siguiente) {
            Intent i = new Intent(MainActivity.this, ReproductorService.class);
            i.setAction(ReproductorService.ACCION_LETRA);
            i.putExtra("linea", linea == null ? "" : linea.length() > 300 ? linea.substring(0, 300) : linea);
            i.putExtra("siguiente", siguiente == null ? "" : siguiente.length() > 300 ? siguiente.substring(0, 300) : siguiente);
            try { startService(i); } catch (Exception e) { android.util.Log.w("SpaceMusic", "Letra: " + e); }
        }

        /** Estado de los avisos al instante: «sin-distribuidora», «pendiente» o «activo». */
        @JavascriptInterface
        public String pushEstado() { return Push.estado(MainActivity.this); }

        /** Vuelve a pedir el registro de avisos al instante (después de instalar ntfy). */
        @JavascriptInterface
        public void pushRegistrar() { runOnUiThread(() -> Push.registrar(MainActivity.this)); }

        /** true con wifi (o red sin límite de datos) y el teléfono cargando: para las descargas automáticas. */
        @JavascriptInterface
        public boolean wifiYCargando() {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                boolean sinLimite = cm != null && !cm.isActiveNetworkMetered();
                android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                int estado = b == null ? -1 : b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                boolean cargando = estado == android.os.BatteryManager.BATTERY_STATUS_CHARGING || estado == android.os.BatteryManager.BATTERY_STATUS_FULL;
                return sinLimite && cargando;
            } catch (Exception e) { return false; }
        }

        /** Notificación de un mensaje con la foto de perfil de quien escribe (solo fotos de nuestro servidor). */
        @JavascriptInterface
        public void notificarConFoto(String titulo, String texto, String ruta, String foto) {
            final String f = foto != null && mismoServidor(foto) ? foto : "";
            runOnUiThread(() -> Avisos.mostrar(MainActivity.this, titulo, texto, ruta, f));
        }

        /** La web avisa que ya arrancó. */
        @JavascriptInterface
        public void lista() {
            paginaLista = true;
            runOnUiThread(MainActivity.this::sonarAlarma);
        }

        /** Despertador: el teléfono abre la app a esa hora (milisegundos). */
        @JavascriptInterface
        public void programarAlarma(long cuando, String texto) {
            if (cuando > System.currentTimeMillis()) Despertador.programar(MainActivity.this, cuando);
        }

        @JavascriptInterface
        public void cancelarAlarma() {
            Despertador.cancelar(MainActivity.this);
        }

        /** Pantalla sin conexión: volver a intentar abrir el servidor. */
        @JavascriptInterface
        public void reintentar() {
            runOnUiThread(MainActivity.this::cargar);
        }

        /** Pantalla sin conexión: pedir permiso para ver la música del teléfono. */
        @JavascriptInterface
        public void permisoMusica() {
            runOnUiThread(MainActivity.this::pedirPermisoMusica);
        }

        /** Hoja de compartir de Android para un texto (la vista web no tiene navigator.share). */
        @JavascriptInterface
        public void compartir(String texto) {
            if (texto == null || texto.length() > 2000) return;
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto);
                    startActivity(Intent.createChooser(i, "Compartir"));
                } catch (Exception ignored) { }
            });
        }

        /** Salida de audio actual (lo que el sistema tiene elegido para la música), en JSON. */
        @JavascriptInterface
        public String salidaAudio() {
            return Salidas.actual(MainActivity.this);
        }

        /** Abre el selector de salida de medios del sistema (o el mejor respaldo de esta versión de Android). */
        @JavascriptInterface
        public String elegirSalida() {
            final String[] r = { "" };
            final java.util.concurrent.CountDownLatch listo = new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(() -> { try { r[0] = Salidas.elegir(MainActivity.this); } finally { listo.countDown(); } });
            try { listo.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return r[0];
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

    /** true mientras la vista web está viva (ella avisa al instante; la revisión periódica no repite). */
    static boolean abierta() {
        MainActivity a = actual.get();
        return a != null && a.web != null;
    }

    /** Guarda la sesión de la web para la revisión de mensajes con la app cerrada (`Revisar`). */
    private void guardarSesion() {
        if (web == null) return;
        try {
            web.evaluateJavascript("(function(){try{return localStorage.getItem('sm_token')||''}catch(e){return ''}})()", v -> {
                String t = v == null ? "" : v.replace("\"", "").trim();
                if (t.equals("null")) t = "";
                if (t.isEmpty() || t.matches("[A-Za-z0-9._-]{20,4096}")) {
                    boolean nuevo = !t.equals(prefs.getString("token", ""));
                    prefs.edit().putString("token", t).apply();
                    // Con sesión nueva, la dirección de avisos se vuelve a registrar en el servidor con esta cuenta
                    if (nuevo && !t.isEmpty()) { prefs.edit().putBoolean("pushEnviado", false).apply(); Push.enviarAlServidor(MainActivity.this); }
                }
            });
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.postDelayed(this::guardarSesion, 5000);
    }

    @Override
    protected void onStop() {
        guardarSesion();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (vigilaRed != null && Build.VERSION.SDK_INT >= 24) {
            try { ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(vigilaRed); } catch (Exception ignored) { }
        }
        Salidas.dejarDeVigilar(this);
        stopService(new Intent(this, ReproductorService.class));
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
