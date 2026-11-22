package com.bpm.spacemusic.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * La TV queda «lista para recibir música» mientras está encendida, aunque la app esté cerrada
 * (como Spotify Connect): arranca con la TV, aparece en «Dispositivos» de tus otros equipos y, cuando
 * le mandan música, abre el reproductor y la pone a sonar.
 * <p>
 * Mientras la app está abierta la vista web se anuncia sola; este servicio solo la mantiene despierta
 * (Android congela las apps en segundo plano sin un servicio así y la TV desaparecía de la lista).
 */
public class Escucha extends Service {

    static final String PREFS = "spacemusic";
    private static final String CANAL = "lista";
    private static final int AVISO = 7;
    private static final String SERVIDOR_POR_DEFECTO = "https://spacemusic.tail8ccfdf.ts.net";

    private volatile boolean vivo;
    private Thread presencia, ordenes;
    private volatile HttpURLConnection sse;

    static void iniciar(Context c) {
        try {
            Intent i = new Intent(c, Escucha.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { /* el sistema no lo permitió ahora: se intenta al abrir la app */ }
    }

    /** Despierta al servicio para que se anuncie ya (por ejemplo, justo después de cerrar la app). */
    static void avisar(Context c) { iniciar(c); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        enPrimerPlano();
        if (!vivo) {
            vivo = true;
            presencia = new Thread(this::bucleDePresencia, "tv-presencia");
            presencia.start();
            ordenes = new Thread(this::bucleDeOrdenes, "tv-ordenes");
            ordenes.start();
        } else if (presencia != null) {
            presencia.interrupt(); // se anuncia ya (sale de la espera)
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        vivo = false;
        try { if (sse != null) sse.disconnect(); } catch (Exception ignored) { }
        if (presencia != null) presencia.interrupt();
        if (ordenes != null) ordenes.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void enPrimerPlano() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Lista para recibir música", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CANAL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SpaceMusic")
                .setContentText("Lista para recibir música de tus otros dispositivos")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(AVISO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(AVISO, n);
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private String servidor() {
        String s = prefs().getString("servidor", SERVIDOR_POR_DEFECTO).trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ═══════════ Presencia: aparecer en «Dispositivos» (cada 25 s; el servidor quita a quien calla 75 s) ═══════════

    private void bucleDePresencia() {
        while (vivo) {
            try {
                if (!MainActivity.abierta()) anunciar();
            } catch (Exception ignored) { /* sin red: se reintenta */ }
            try { Thread.sleep(25_000); } catch (InterruptedException ignored) { /* despertado a propósito */ }
        }
    }

    private void anunciar() throws Exception {
        SharedPreferences p = prefs();
        String token = p.getString("token", ""), id = p.getString("dispositivo", "");
        if (token.isEmpty() || id.isEmpty()) return;
        JSONObject b = new JSONObject();
        b.put("dispositivo", id);
        b.put("nombre", p.getString("nombre", "TV"));
        b.put("plataforma", "tv");
        b.put("sonando", false);
        b.put("seq", System.currentTimeMillis());
        HttpURLConnection con = (HttpURLConnection) new URL(servidor() + "/api/control/presencia").openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(10_000);
        con.setReadTimeout(15_000);
        con.setRequestProperty("Authorization", "Bearer " + token);
        con.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = con.getOutputStream()) { o.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        int r = con.getResponseCode();
        if (r == 401) p.edit().remove("token").apply(); // sesión vencida: hay que entrar de nuevo en la TV
        con.disconnect();
    }

    // ═══════════ Órdenes: «reproducir aquí» desde otro dispositivo con la app cerrada ═══════════

    private void bucleDeOrdenes() {
        long espera = 2000;
        while (vivo) {
            try {
                SharedPreferences p = prefs();
                String token = p.getString("token", "");
                if (token.isEmpty() || MainActivity.abierta()) { Thread.sleep(5000); continue; }
                HttpURLConnection con = (HttpURLConnection) new URL(servidor() + "/api/eventos?t=" + URLEncoder.encode(token, "UTF-8")).openConnection();
                con.setConnectTimeout(10_000);
                con.setReadTimeout(90_000);
                con.setRequestProperty("Accept", "text/event-stream");
                sse = con;
                if (con.getResponseCode() != 200) throw new IllegalStateException("eventos " + con.getResponseCode());
                espera = 2000;
                try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    String evento = "message", linea;
                    while (vivo && (linea = in.readLine()) != null) {
                        if (linea.startsWith("event:")) evento = linea.substring(6).trim();
                        else if (linea.startsWith("data:")) {
                            if ("control-orden".equals(evento)) recibir(linea.substring(5).trim());
                            evento = "message";
                        }
                        if (MainActivity.abierta()) break; // la app ya está abierta: ella atiende las órdenes
                    }
                }
                con.disconnect();
            } catch (InterruptedException e) {
                if (!vivo) return;
            } catch (Exception e) {
                try { Thread.sleep(espera); } catch (InterruptedException ignored) { if (!vivo) return; }
                espera = Math.min(espera * 2, 30_000);
            }
        }
    }

    /** Orden para esta TV: se abre el reproductor con la orden (ella la ejecuta al cargar). */
    private void recibir(String datos) {
        try {
            JSONObject o = new JSONObject(datos);
            if (!o.optString("para").equals(prefs().getString("dispositivo", ""))) return;
            String accion = o.optString("accion");
            // Solo las que ponen música aquí abren la app; pausar o cambiar el volumen de una app cerrada no hace falta
            if (!accion.equals("reproducir") && !accion.equals("transferir") && !accion.equals("play")) return;
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("orden", datos);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    /** ¿Puede abrirse sola la app desde segundo plano? (Android 10+ pide «mostrar sobre otras apps»). */
    static boolean puedeAbrirse(Context c) {
        return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c);
    }
}
