package com.bpm.spacemusic;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Despertador de SpaceMusic: el teléfono abre la app a la hora elegida (aunque esté cerrada o con la pantalla
 * apagada) y la web pone la música con el volumen subiendo poco a poco. La hora se guarda para volver a
 * programarla si el teléfono se reinicia.
 */
public class Despertador extends BroadcastReceiver {

    private static final String PREFS = "spacemusic";
    static final String EXTRA = "alarma";

    private static PendingIntent abrirApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .putExtra(EXTRA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 77, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void programar(Context c, long cuando) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("alarma", cuando).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = abrirApp(c);
        boolean exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exacta) am.setAlarmClock(new AlarmManager.AlarmClockInfo(cuando, pi), pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi); // sin permiso de alarma exacta: puede tardar unos minutos
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
        }
    }

    static void cancelar(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("alarma").apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(abrirApp(c));
    }

    /** Al reiniciar el teléfono las alarmas se borran: se vuelve a poner la que estaba pendiente. */
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        long cuando = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("alarma", 0);
        if (cuando > System.currentTimeMillis()) programar(c, cuando);
        else if (cuando > 0) cancelar(c);
    }
}
