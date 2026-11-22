package com.bpm.spacemusic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Avisos al instante sin servicios de Google, con UnifiedPush: si el teléfono tiene una app «distribuidora»
 * (por ejemplo ntfy), SpaceMusic se registra en ella y recibe una dirección; el servidor manda un aviso vacío
 * a esa dirección cuando llega un mensaje y aquí se consulta el resumen (`Revisar`) para mostrar la notificación.
 * Sin distribuidora sigue la revisión cada ~15 minutos.
 */
public class Push extends BroadcastReceiver {

    static final String REGISTRAR = "org.unifiedpush.android.distributor.REGISTER";
    static final String NUEVA_DIRECCION = "org.unifiedpush.android.connector.NEW_ENDPOINT";
    static final String MENSAJE = "org.unifiedpush.android.connector.MESSAGE";
    static final String BAJA = "org.unifiedpush.android.connector.UNREGISTERED";
    static final String FALLO = "org.unifiedpush.android.connector.REGISTRATION_FAILED";

    /** Paquete de la primera app distribuidora instalada (o null). */
    static String distribuidora(Context c) {
        try {
            List<ResolveInfo> l = c.getPackageManager().queryBroadcastReceivers(new Intent(REGISTRAR), 0);
            for (ResolveInfo r : l) {
                if (r.activityInfo != null && !c.getPackageName().equals(r.activityInfo.packageName)) return r.activityInfo.packageName;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** «sin-distribuidora», «pendiente» (esperando dirección) o «activo». */
    static String estado(Context c) {
        SharedPreferences p = c.getSharedPreferences(Revisar.PREFS, Context.MODE_PRIVATE);
        if (!p.getString("pushEndpoint", "").isEmpty()) return p.getBoolean("pushEnviado", false) ? "activo" : "pendiente";
        return distribuidora(c) == null ? "sin-distribuidora" : "pendiente";
    }

    /** Se registra en la distribuidora (al abrir la app). */
    static void registrar(Context c) {
        String d = distribuidora(c);
        if (d == null) return;
        SharedPreferences p = c.getSharedPreferences(Revisar.PREFS, Context.MODE_PRIVATE);
        String token = p.getString("pushToken", "");
        if (token.isEmpty()) { token = UUID.randomUUID().toString(); p.edit().putString("pushToken", token).apply(); }
        Intent i = new Intent(REGISTRAR);
        i.setPackage(d);
        i.putExtra("token", token);
        i.putExtra("application", c.getPackageName());
        i.putExtra("message", "SpaceMusic: mensajes y avisos al instante");
        try { c.sendBroadcast(i); } catch (Exception ignored) { }
        enviarAlServidor(c);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || i.getAction() == null) return;
        SharedPreferences p = c.getSharedPreferences(Revisar.PREFS, Context.MODE_PRIVATE);
        String token = i.getStringExtra("token");
        if (token == null || !token.equals(p.getString("pushToken", ""))) return; // no es para esta app
        switch (i.getAction()) {
            case NUEVA_DIRECCION: {
                String endpoint = i.getStringExtra("endpoint");
                if (endpoint == null || !endpoint.startsWith("https://")) return;
                p.edit().putString("pushEndpoint", endpoint).putBoolean("pushEnviado", false).apply();
                enviarAlServidor(c);
                break;
            }
            case MENSAJE: {
                // Aviso vacío del servidor: se pide el resumen y se muestra lo nuevo
                final PendingResult pr = goAsync();
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    try { Revisar.revisar(app); } catch (Exception ignored) { }
                    pr.finish();
                }).start();
                break;
            }
            case BAJA:
            case FALLO:
                p.edit().remove("pushEndpoint").putBoolean("pushEnviado", false).apply();
                break;
            default:
                break;
        }
    }

    /** Manda la dirección al servidor de SpaceMusic (cuando ya hay sesión guardada). */
    static void enviarAlServidor(final Context c) {
        final SharedPreferences p = c.getSharedPreferences(Revisar.PREFS, Context.MODE_PRIVATE);
        final String endpoint = p.getString("pushEndpoint", "");
        final String token = p.getString("token", "");
        if (endpoint.isEmpty() || token.isEmpty() || p.getBoolean("pushEnviado", false)) return;
        new Thread(() -> {
            try {
                String base = p.getString("servidor", MainActivity.SERVIDOR_POR_DEFECTO).trim();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                HttpURLConnection con = (HttpURLConnection) new URL(base + "/api/push/destino").openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(10000);
                con.setReadTimeout(15000);
                con.setRequestProperty("Authorization", "Bearer " + token);
                con.setRequestProperty("Content-Type", "application/json");
                String cuerpo = "{\"endpoint\":" + json(endpoint) + ",\"dispositivo\":" + json(Build.MODEL == null ? "Android" : Build.MODEL) + "}";
                try (OutputStream o = con.getOutputStream()) { o.write(cuerpo.getBytes(StandardCharsets.UTF_8)); }
                if (con.getResponseCode() == 200) p.edit().putBoolean("pushEnviado", true).apply();
            } catch (Exception ignored) { /* se reintenta al volver a abrir la app */ }
        }).start();
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
