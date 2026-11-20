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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
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
    static final String ACCION_LETRA = "com.bpm.spacemusic.LETRA";
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

    // Letra en la pantalla de bloqueo: sobre la portada desenfocada (no desaparece), en el centro, que siempre se ve
    // aunque el sistema recorte la imagen; entre versos, la línea anterior sube y se desvanece y la nueva entra.
    private static final int LADO = 720;
    private String linea = "", siguiente = "", lineaMostrada = "", siguienteMostrada = "";
    private Bitmap fondoBorroso, arte;
    private int animacion;

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
        else if (ACCION_LETRA.equals(accion)) {
            linea = texto(i, "linea");
            siguiente = texto(i, "siguiente");
            animarLetra();
            return START_NOT_STICKY;
        }
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
                fondoBorroso = null;
                arte = null;
                lineaMostrada = "";
                siguienteMostrada = "";
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
                        if (url.equals(portadaUrl)) {
                            portada = b;
                            fondoBorroso = b == null ? null : desenfocar(b);
                            if (!linea.isEmpty()) { lineaMostrada = ""; animarLetra(); }
                            actualizar();
                        }
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
        if (arte != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, arte);
        else if (portada != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, portada);
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

    // ═══════════════════ Letra sobre la portada ═══════════════════

    /** Desenfoque barato: la portada a 20 px y de nuevo a 120 px con filtro (suave, sin bordes). */
    private static Bitmap desenfocar(Bitmap b) {
        int w = Math.max(1, b.getWidth()), h = Math.max(1, b.getHeight());
        Bitmap chica = Bitmap.createScaledBitmap(b, 20, Math.max(1, 20 * h / w), true);
        return Bitmap.createScaledBitmap(chica, 120, Math.max(1, 120 * h / w), true);
    }

    /** Dibuja `img` cubriendo el cuadro (como «center crop»). */
    private static void cubrir(Canvas c, Bitmap img, Paint p) {
        float esc = Math.max((float) LADO / img.getWidth(), (float) LADO / img.getHeight());
        float w = img.getWidth() * esc, h = img.getHeight() * esc;
        c.drawBitmap(img, null, new android.graphics.RectF((LADO - w) / 2, (LADO - h) / 2, (LADO + w) / 2, (LADO + h) / 2), p);
    }

    private static StaticLayout capa(String t, TextPaint p, int ancho, int maxLineas) {
        StaticLayout.Builder b = StaticLayout.Builder.obtain(t, 0, t.length(), p, ancho)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(4, 1f).setIncludePad(false)
                .setMaxLines(maxLineas).setEllipsize(TextUtils.TruncateAt.END);
        return b.build();
    }

    /** Dibuja una línea (y la siguiente atenuada) centradas en la franja del medio. */
    private static void dibujarLetra(Canvas c, String actual, String sig, float alfa, float dy) {
        if (actual.isEmpty() || alfa <= 0.01f) return;
        int ancho = (int) (LADO * 0.84f);
        TextPaint grande = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        grande.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        grande.setColor(Color.argb((int) (255 * alfa), 255, 255, 255));
        grande.setShadowLayer(12, 0, 2, Color.argb((int) (120 * alfa), 0, 0, 0));
        float tam = 62;
        StaticLayout l;
        do {
            grande.setTextSize(tam);
            l = capa(actual, grande, ancho, 4);
            tam -= 4;
        } while (l.getHeight() > LADO * 0.36f && tam > 28);
        StaticLayout s = null;
        if (!sig.isEmpty()) {
            TextPaint chica = new TextPaint(grande);
            chica.setTextSize(Math.max(24, tam * 0.55f));
            chica.setColor(Color.argb((int) (130 * alfa), 255, 255, 255));
            s = capa(sig, chica, ancho, 2);
        }
        float alto = l.getHeight() + (s == null ? 0 : 22 + s.getHeight());
        float y = (LADO - alto) / 2 + dy;
        c.save();
        c.translate((LADO - ancho) / 2f, y);
        l.draw(c);
        if (s != null) { c.translate(0, l.getHeight() + 22); s.draw(c); }
        c.restore();
    }

    /** Un cuadro de la transición (p de 0 a 1). Al llegar la letra la portada se desenfoca; al irse vuelve nítida. */
    private Bitmap cuadro(String deL, String deS, String aL, String aS, float p) {
        float e = p * p * (3 - 2 * p);
        Bitmap b = Bitmap.createBitmap(LADO, LADO, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint pintura = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawColor(Color.BLACK);
        if (fondoBorroso != null) cubrir(c, fondoBorroso, pintura);
        // Velo oscuro, más fuerte en el centro donde va la letra
        Paint velo = new Paint();
        velo.setShader(new LinearGradient(0, 0, 0, LADO, new int[]{0x55000000, 0x99000000, 0x66000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(new Rect(0, 0, LADO, LADO), velo);
        float nitidez = deL.isEmpty() && aL.isEmpty() ? 1 : deL.isEmpty() ? 1 - e : aL.isEmpty() ? e : 0;
        if (nitidez > 0.01f && portada != null) {
            Paint n = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            n.setAlpha((int) (255 * nitidez));
            cubrir(c, portada, n);
        }
        float salto = LADO * 0.05f;
        dibujarLetra(c, deL, deS, 1 - e, -salto * e);
        dibujarLetra(c, aL, aS, e, salto * (1 - e));
        return b;
    }

    /** Cambia la imagen de la pantalla de bloqueo a la línea nueva en 5 cuadros (unos 0,35 s). */
    private void animarLetra() {
        final String deL = lineaMostrada, deS = siguienteMostrada, aL = linea, aS = siguiente;
        if (deL.equals(aL) && deS.equals(aS)) return;
        lineaMostrada = aL;
        siguienteMostrada = aS;
        final int mia = ++animacion;
        if (portada == null && fondoBorroso == null) return;
        final float[] pasos = {0.2f, 0.4f, 0.6f, 0.8f, 1f};
        for (int k = 0; k < pasos.length; k++) {
            final float p = pasos[k];
            ui.postDelayed(() -> {
                if (mia != animacion) return;
                arte = aL.isEmpty() && p >= 1f ? null : cuadro(deL, deS, aL, aS, p);
                MediaMetadata.Builder m = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artista)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, duracionMs);
                Bitmap img = arte != null ? arte : portada;
                if (img != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, img);
                sesion.setMetadata(m.build());
            }, k * 70L);
        }
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
