package com.bpm.spacemusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.app.Person;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;

/** Notificaciones de mensajes y avisos (nuevo seguidor, nuevos amigos…). */
final class Avisos {

    private static final String CANAL = "avisos";
    private static int siguienteId = 100;

    private Avisos() { }

    static void mostrar(Context c, String titulo, String texto, String ruta) { mostrar(c, titulo, texto, ruta, ""); }

    /** Con la foto de perfil (se baja en segundo plano; si no llega a tiempo, la notificación sale sin foto). */
    static void mostrar(final Context c, final String titulo, final String texto, final String ruta, final String foto) {
        if (foto == null || foto.isEmpty()) { publicar(c, titulo, texto, ruta, null); return; }
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Bitmap redonda = null;
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(foto).openConnection();
                con.setConnectTimeout(4000);
                con.setReadTimeout(5000);
                try (InputStream in = con.getInputStream()) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) redonda = circular(b);
                }
            } catch (Exception ignored) { /* sin foto */ }
            final Bitmap img = redonda;
            ui.post(() -> publicar(c, titulo, texto, ruta, img));
        }).start();
    }

    /** La foto recortada en círculo (como en las apps de mensajes). */
    private static Bitmap circular(Bitmap b) {
        int lado = Math.min(b.getWidth(), b.getHeight());
        Bitmap cuadrada = Bitmap.createBitmap(b, (b.getWidth() - lado) / 2, (b.getHeight() - lado) / 2, lado, lado);
        int t = Math.min(lado, 256);
        Bitmap chica = Bitmap.createScaledBitmap(cuadrada, t, t, true);
        Bitmap r = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(chica, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(r).drawCircle(t / 2f, t / 2f, t / 2f, p);
        return r;
    }

    /** «#/mensajes/ana» → «ana» (solo los avisos de mensajes son conversaciones). */
    private static String usuarioDeRuta(String ruta) {
        if (ruta == null) return null;
        String r = ruta.replace("#", "");
        if (!r.startsWith("/mensajes/")) return null;
        String u = r.substring("/mensajes/".length());
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u.isEmpty() ? null : u;
    }

    @SuppressWarnings("deprecation")
    private static void publicar(Context c, String titulo, String texto, String ruta, Bitmap foto) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Mensajes y avisos", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Mensajes de tus amigos y nuevos seguidores");
            nm.createNotificationChannel(ch);
        }
        Intent abrir = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("ruta", ruta == null ? "" : ruta);
        int id = siguienteId++;
        PendingIntent pi = PendingIntent.getActivity(c, id, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CANAL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_nota)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setColor(0xFF000000);
        String usuario = usuarioDeRuta(ruta);
        if (Build.VERSION.SDK_INT >= 30 && usuario != null) {
            // Android 11+: notificación de conversación. La foto de quien escribe ocupa el lugar del ícono (izquierda).
            Person.Builder pb = new Person.Builder().setName(titulo).setKey(usuario);
            if (foto != null) pb.setIcon(Icon.createWithBitmap(foto));
            Person persona = pb.build();
            String atajo = "chat-" + usuario;
            try {
                ShortcutManager sm = c.getSystemService(ShortcutManager.class);
                Intent abrirChat = new Intent(Intent.ACTION_VIEW, null, c, MainActivity.class).putExtra("ruta", ruta);
                ShortcutInfo.Builder si = new ShortcutInfo.Builder(c, atajo)
                        .setShortLabel(titulo)
                        .setLongLived(true)
                        .setPerson(persona)
                        .setIntent(abrirChat);
                si.setIcon(foto != null ? Icon.createWithAdaptiveBitmap(foto) : Icon.createWithResource(c, R.drawable.ic_nota));
                sm.pushDynamicShortcut(si.build());
                b.setShortcutId(atajo);
            } catch (Exception ignored) { /* sin atajo: sale igual con el estilo de mensajes */ }
            Notification.MessagingStyle estilo = new Notification.MessagingStyle(new Person.Builder().setName("Tú").build());
            estilo.addMessage(texto, System.currentTimeMillis(), persona);
            b.setStyle(estilo).setCategory(Notification.CATEGORY_MESSAGE);
        } else if (foto != null) b.setLargeIcon(foto);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        try { nm.notify(id, b.build()); } catch (Exception ignored) { /* sin permiso de notificaciones */ }
    }
}
