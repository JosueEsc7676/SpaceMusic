package com.bpm.spacemusic.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

/**
 * SpaceMusic para Android TV.
 * Abre la interfaz de TV del servidor (/tv.html) a pantalla completa y le
 * pasa las teclas del control remoto: flechas, OK, Atrás y teclas multimedia.
 */
public class MainActivity extends Activity {

    private static final String SERVIDOR_POR_DEFECTO = "https://spacemusic.tail8ccfdf.ts.net";
    private static final String PREFS = "spacemusic";

    private WebView web;
    private SharedPreferences prefs;
    /** La TV abierta (su vista web se anuncia sola); si no, el servicio `Escucha` la mantiene en «Dispositivos». */
    private static volatile MainActivity actual;
    /** Orden que llegó con la app cerrada (la ejecuta la web al cargar). */
    private String ordenPendiente;

    static boolean abierta() { MainActivity a = actual; return a != null && a.web != null; }
    private boolean sonando = false;
    private boolean errorMostrado = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        actual = this;
        ordenPendiente = getIntent() == null ? null : getIntent().getStringExtra("orden");
        // Lista para recibir música aunque se cierre la app (y no se congela en segundo plano)
        Escucha.iniciar(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        pantallaCompleta();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // la música suena sin tocar la pantalla
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " SpaceMusicTV/1.0");

        web.addJavascriptInterface(new Puente(), "SpaceMusicTV");
        // Depuración por ADB (chrome://inspect) y mensajes de la página en logcat
        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                android.util.Log.i("SpaceMusicTV", m.messageLevel() + ": " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // Solo se navega dentro del propio servidor
                Uri u = req.getUrl();
                Uri base = Uri.parse(servidor());
                return u.getHost() == null || !u.getHost().equals(base.getHost());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !errorMostrado) {
                    errorMostrado = true;
                    dialogoError(err.getDescription() != null ? err.getDescription().toString() : "Error de red");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                android.util.Log.i("SpaceMusicTV", "Página cargada: " + url);
                web.requestFocus();
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else cargar();
    }

    private String servidor() {
        String s = prefs.getString("servidor", SERVIDOR_POR_DEFECTO).trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String o = intent == null ? null : intent.getStringExtra("orden");
        if (o != null && web != null) js("window.tvOrden && window.tvOrden(" + org.json.JSONObject.quote(o) + ")", null);
    }

    /** Pide (una vez) el permiso para abrirse sola cuando le mandan música con la app cerrada. */
    private void pedirAbrirseSola() {
        if (Escucha.puedeAbrirse(this) || prefs.getBoolean("pidioAbrirse", false)) return;
        prefs.edit().putBoolean("pidioAbrirse", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Recibir música con la app cerrada")
                .setMessage("Para que la TV abra SpaceMusic sola cuando le mandas música desde el teléfono, permite «Mostrar sobre otras apps».")
                .setPositiveButton("Permitir", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        new AlertDialog.Builder(this).setMessage("Esta TV no tiene ese ajuste. Igual aparecerá en tus dispositivos mientras esté encendida.")
                                .setPositiveButton("Entendido", null).show();
                    }
                })
                .setNegativeButton("Ahora no", null)
                .show();
    }

    private void cargar() {
        errorMostrado = false;
        web.loadUrl(servidor() + "/tv.html");
    }

    private void pantallaCompleta() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ═══════════════════ Control remoto ═══════════════════

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        String accion = null;
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                accion = "playpause"; break;
            case KeyEvent.KEYCODE_MEDIA_NEXT: accion = "next"; break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: accion = "prev"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: accion = "ff"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: accion = "rew"; break;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO: accion = "menu"; break;
            case KeyEvent.KEYCODE_SETTINGS: if (e.getAction() == KeyEvent.ACTION_UP) dialogoServidor(); return true;
            default: break;
        }
        if (accion != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) js("window.tvKey && window.tvKey('" + accion + "')", null);
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                // La interfaz decide: cierra pantallas abiertas o devuelve false si ya está en el inicio
                js("(window.tvBack ? window.tvBack() : false)", v -> {
                    if (v == null || "false".equals(v) || "null".equals(v)) salir();
                });
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Al salir: si hay música sonando sigue en segundo plano; si no, se cierra. */
    private void salir() {
        if (sonando) moveTaskToBack(true);
        else finish();
    }

    private void cerrarDelTodo() {
        if (web != null) { web.stopLoading(); web.loadUrl("about:blank"); }
        finishAndRemoveTask();
        // Termina el proceso para que la música no siga sonando
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    private void js(String codigo, android.webkit.ValueCallback<String> cb) {
        web.evaluateJavascript(codigo, cb);
    }

    // ═══════════════════ Diálogos ═══════════════════

    private void dialogoError(String detalle) {
        new AlertDialog.Builder(this)
                .setTitle("No se pudo conectar")
                .setMessage("No se pudo abrir " + servidor() + "\n\n" + detalle
                        + "\n\nRevisa que el servidor esté encendido y que la TV tenga internet.")
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
                .setMessage("Ejemplo: https://spacemusic.tail8ccfdf.ts.net o http://192.168.10.165:8081")
                .setView(campo)
                .setPositiveButton("Guardar", (d, w) -> {
                    String v = campo.getText().toString().trim();
                    if (!v.startsWith("http")) v = "http://" + v;
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

    // ═══════════════════ Puente con la interfaz web ═══════════════════

    private class Puente {
        /** La web avisa si hay música sonando: la pantalla no se apaga mientras suena. */
        @JavascriptInterface
        public void setPlaying(final boolean v) {
            runOnUiThread(() -> {
                sonando = v;
                if (v) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(MainActivity.this::dialogoServidor);
        }

        /** Cierra la app por completo (no queda en segundo plano). */
        @JavascriptInterface
        public void cerrarApp() {
            runOnUiThread(MainActivity.this::cerrarDelTodo);
        }

        /** La web comparte su sesión, su identificador y su nombre: así el servicio anuncia la TV con la app cerrada. */
        @JavascriptInterface
        public void guardarSesion(String token, String dispositivo, String nombre) {
            if (token == null || !token.matches("[A-Za-z0-9._-]{20,4096}") || dispositivo == null || !dispositivo.matches("[A-Za-z0-9_-]{8,64}")) return;
            prefs.edit().putString("token", token).putString("dispositivo", dispositivo)
                    .putString("nombre", nombre == null || nombre.isEmpty() ? "TV" : nombre.substring(0, Math.min(60, nombre.length()))).apply();
            runOnUiThread(MainActivity.this::pedirAbrirseSola);
        }

        /** Al cerrar sesión en la TV: deja de anunciarse. */
        @JavascriptInterface
        public void olvidarSesion() { prefs.edit().remove("token").apply(); }

        /** La orden con la que se abrió la app (solo una vez). */
        @JavascriptInterface
        public String ordenPendiente() {
            String o = ordenPendiente;
            ordenPendiente = null;
            return o == null ? "" : o;
        }

        /** Trae la app al frente (cuando le mandan música mientras se ve otra app). */
        @JavascriptInterface
        public void alFrente() {
            runOnUiThread(() -> {
                try {
                    startActivity(new android.content.Intent(MainActivity.this, MainActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public String version() {
            return "1.3";
        }

        /** Nombre del televisor (el que se le puso en Ajustes, o el modelo): así aparece en tus otros dispositivos. */
        @JavascriptInterface
        public String dispositivo() {
            String n = null;
            try { n = android.provider.Settings.Global.getString(getContentResolver(), "device_name"); } catch (Exception ignored) { }
            if (n == null || n.trim().isEmpty()) n = android.os.Build.MODEL;
            return n == null ? "" : n.trim();
        }
    }

    // ═══════════════════ Ciclo de vida ═══════════════════

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) pantallaCompleta();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        if (actual == this) actual = null;
        // La app se cerró: el servicio vuelve a anunciar la TV enseguida
        Escucha.avisar(this);
        super.onDestroy();
    }
}
