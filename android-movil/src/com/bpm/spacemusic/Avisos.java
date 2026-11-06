package com.bpm.spacemusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Notificaciones de mensajes y avisos (nuevo seguidor, nuevos amigos…). */
final class Avisos {

    private static final String CANAL = "avisos";
    private static int siguienteId = 100;

    private Avisos() { }

    @SuppressWarnings("deprecation")
    static void mostrar(Context c, String titulo, String texto, String ruta) {
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
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        try { nm.notify(id, b.build()); } catch (Exception ignored) { /* sin permiso de notificaciones */ }
    }
}
