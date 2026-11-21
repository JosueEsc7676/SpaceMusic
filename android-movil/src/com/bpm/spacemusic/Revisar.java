package com.bpm.spacemusic;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mensajes y avisos con la app cerrada: Android ejecuta esta revisión cada ~15 minutos (el mínimo que permite
 * el sistema sin servicios de Google), también tras reiniciar el teléfono. Si la app está abierta no avisa:
 * la web ya muestra las notificaciones al instante y aquí solo se anota lo visto para no repetirlo.
 */
public class Revisar extends JobService {

    private static final int ID = 7301;
    static final String PREFS = "spacemusic";

    /** Programa la revisión periódica (se llama al abrir la app; persiste tras reiniciar). */
    static void programar(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == ID) return;
            JobInfo info = new JobInfo.Builder(ID, new ComponentName(c, Revisar.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Exception ignored) { /* sin revisión en segundo plano */ }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try { revisar(getApplicationContext()); } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    /** Consulta el resumen de avisos y muestra lo nuevo (mensaje con la foto de quien escribe). */
    static void revisar(Context c) throws Exception {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = p.getString("token", "");
        if (token.isEmpty()) return;
        String base = p.getString("servidor", MainActivity.SERVIDOR_POR_DEFECTO).trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection con = (HttpURLConnection) new URL(base + "/api/notificaciones/resumen").openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        con.setRequestProperty("Authorization", "Bearer " + token);
        if (con.getResponseCode() == 401) { p.edit().remove("token").apply(); return; }
        if (con.getResponseCode() != 200) return;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = con.getInputStream()) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        }
        JSONObject r = new JSONObject(out.toString("UTF-8"));
        // Con la app abierta la web avisa al instante: aquí solo se anota para no repetir
        boolean avisar = !MainActivity.abierta();
        long vistoM = p.getLong("avisoM", -1), vistoN = p.getLong("avisoN", -1);
        SharedPreferences.Editor e = p.edit();
        JSONObject m = r.optJSONObject("ultimoMensaje");
        if (m != null && m.optLong("id") > vistoM) {
            e.putLong("avisoM", m.optLong("id"));
            if (avisar && vistoM >= 0) {
                String foto = m.optString("foto", "");
                if (!foto.isEmpty() && foto.startsWith("/")) foto = base + foto;
                if (!foto.startsWith(base)) foto = "";
                Avisos.mostrar(c, m.optString("de", "SpaceMusic"), m.optString("texto", ""), "#/mensajes/" + m.optString("username"), foto);
            }
        } else if (vistoM < 0) e.putLong("avisoM", 0);
        JSONObject n = r.optJSONObject("ultimaNotificacion");
        if (n != null && n.optLong("id") > vistoN) {
            e.putLong("avisoN", n.optLong("id"));
            if (avisar && vistoN >= 0) {
                String ruta = n.optString("ruta", "");
                Avisos.mostrar(c, "SpaceMusic", n.optString("texto", ""), ruta.isEmpty() ? "#/" : ruta);
            }
        } else if (vistoN < 0) e.putLong("avisoN", 0);
        e.apply();
    }
}
