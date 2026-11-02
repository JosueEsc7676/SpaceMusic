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
    private boolean sonando = false;
    private boolean errorMostrado = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

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
        web.setWebChromeClient(new WebChromeClient());
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

        @JavascriptInterface
        public String version() {
            return "1.0";
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
        super.onDestroy();
    }
}
