package com.bpm.spacemusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mantiene la música sonando en segundo plano y muestra los controles del sistema:
 * notificación, pantalla de bloqueo, auriculares y Bluetooth.
 * El audio lo reproduce la web; este servicio solo refleja su estado y le envía las órdenes.
 */
public class ReproductorService extends Service {

    static final String ACCION_ESTADO = "com.bpm.spacemusic.ESTADO";
    private static final String ACCION_ANTERIOR = "com.bpm.spacemusic.ANTERIOR";
    private static final String ACCION_ALTERNAR = "com.bpm.spacemusic.ALTERNAR";
    private static final String ACCION_SIGUIENTE = "com.bpm.spacemusic.SIGUIENTE";
    private static final String CANAL = "reproduccion";
    private static final int ID = 1;

    private MediaSession sesion;
    private PowerManager.WakeLock wake;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean sonando;
    private String titulo = "", artista = "", album = "", portadaUrl = "";
    private long duracionMs, posicionMs;
    private Bitmap portada;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CANAL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Controles de la música que está sonando");
            c.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
        sesion = new MediaSession(this, "SpaceMusic");
        sesion.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        sesion.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { orden("play", 0); }
            @Override public void onPause() { orden("pause", 0); }
            @Override public void onSkipToNext() { orden("next", 0); }
            @Override public void onSkipToPrevious() { orden("prev", 0); }
            @Override public void onStop() { orden("pause", 0); }
            @Override public void onSeekTo(long ms) { orden("seek", ms / 1000); }
        });
        sesion.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpaceMusic:reproduccion");
        wake.setReferenceCounted(false);
    }

    private void orden(String accion, long valor) {
        MainActivity a = MainActivity.actual.get();
        if (a != null) a.orden(accion, valor);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String accion = i != null ? i.getAction() : null;
        if (ACCION_ANTERIOR.equals(accion)) orden("prev", 0);
        else if (ACCION_SIGUIENTE.equals(accion)) orden("next", 0);
        else if (ACCION_ALTERNAR.equals(accion)) orden(sonando ? "pause" : "play", 0);
        else if (ACCION_ESTADO.equals(accion)) {
            sonando = i.getBooleanExtra("sonando", false);
            titulo = texto(i, "titulo");
            artista = texto(i, "artista");
            album = texto(i, "album");
            duracionMs = i.getIntExtra("duracion", 0) * 1000L;
            posicionMs = i.getIntExtra("posicion", 0) * 1000L;
            String url = texto(i, "portada");
            if (!url.equals(portadaUrl)) {
                portadaUrl = url;
                portada = null;
                cargarPortada(url);
            }
            if (sonando) wake.acquire(4 * 60 * 60 * 1000L); else if (wake.isHeld()) wake.release();
        }
        actualizar();
        return START_NOT_STICKY;
    }

    private static String texto(Intent i, String k) {
        String v = i.getStringExtra(k);
        return v != null ? v : "";
    }

    private void cargarPortada(final String url) {
        if (url.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    final Bitmap b = BitmapFactory.decodeStream(in);
                    ui.post(() -> {
                        if (url.equals(portadaUrl)) { portada = b; actualizar(); }
                    });
                }
            } catch (Exception ignored) { /* sin portada */ }
        }).start();
    }

    /** Refleja el estado en la sesión multimedia y en la notificación. */
    private void actualizar() {
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artista)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duracionMs);
        if (portada != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, portada);
        sesion.setMetadata(m.build());
        sesion.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(sonando ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        posicionMs, sonando ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());

        Notification n = notificacion();
        // Se mantiene en primer plano también en pausa, para retomar al instante desde la notificación
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(ID, n);
    }

    @SuppressWarnings("deprecation")
    private Notification notificacion() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        Intent abrir = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_nota)
                .setContentTitle(titulo.isEmpty() ? "SpaceMusic" : titulo)
                .setContentText(album.isEmpty() ? artista : artista + " · " + album)
                .setLargeIcon(portada)
                .setContentIntent(PendingIntent.getActivity(this, 0, abrir, banderas()))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(sonando)
                .setColor(0xFF000000)
                .addAction(new Notification.Action(android.R.drawable.ic_media_previous, "Anterior", pendiente(ACCION_ANTERIOR)))
                .addAction(new Notification.Action(sonando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        sonando ? "Pausar" : "Reproducir", pendiente(ACCION_ALTERNAR)))
                .addAction(new Notification.Action(android.R.drawable.ic_media_next, "Siguiente", pendiente(ACCION_SIGUIENTE)))
                .setStyle(new Notification.MediaStyle().setMediaSession(sesion.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent pendiente(String accion) {
        Intent i = new Intent(this, ReproductorService.class).setAction(accion);
        return PendingIntent.getService(this, accion.hashCode(), i, banderas());
    }

    private static int banderas() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Si se cierra la app desde "recientes" y no está sonando, se quita la notificación
        if (!sonando) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (sesion != null) { sesion.setActive(false); sesion.release(); }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
