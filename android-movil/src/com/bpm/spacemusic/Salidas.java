package com.bpm.spacemusic;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Salidas de audio (teléfono, audífonos, Bluetooth, transmisión) para el botón «Dispositivos».
 * La lista y el cambio los gestiona el sistema: aquí solo se lee la salida actual y se abre
 * su selector oficial, con respaldos para versiones antiguas de Android.
 */
final class Salidas {
    private Salidas() { }

    interface Aviso { void cambio(String json); }

    private static MediaRouter.Callback vigilante;

    /** {"nombre":"…","tipo":"telefono|audifonos|bluetooth|remota","disponibles":[…],"selector":true} */
    static String actual(Context ctx) {
        JSONObject r = new JSONObject();
        try {
            String nombre = null, tipo = "telefono";
            MediaRouter mr = (MediaRouter) ctx.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (mr != null) {
                MediaRouter.RouteInfo ruta = mr.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
                if (ruta != null) {
                    CharSequence n = ruta.getName(ctx);
                    nombre = n == null ? null : n.toString();
                    if (ruta.getPlaybackType() == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) tipo = "remota";
                }
            }
            JSONArray disponibles = new JSONArray();
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= 23) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    String t = tipoDe(d.getType());
                    if (t == null) continue;
                    JSONObject o = new JSONObject();
                    CharSequence pn = d.getProductName();
                    o.put("nombre", t.equals("telefono") ? "Este teléfono" : pn == null ? "" : pn.toString());
                    o.put("tipo", t);
                    disponibles.put(o);
                    // Android manda la música a lo último que se conectó (Bluetooth > audífonos > altavoz)
                    if (!"remota".equals(tipo) && rango(t) > rango(tipo)) tipo = t;
                }
            }
            if (nombre == null || nombre.isEmpty()) nombre = "Este teléfono";
            r.put("nombre", nombre);
            r.put("tipo", tipo);
            r.put("disponibles", disponibles);
            r.put("selector", true);
        } catch (Exception ignored) { }
        return r.toString();
    }

    private static int rango(String t) {
        return "remota".equals(t) ? 4 : "bluetooth".equals(t) ? 3 : "audifonos".equals(t) ? 2 : 1;
    }

    private static String tipoDe(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "telefono";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "audifonos";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bluetooth";
            default:
                return Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER) ? "bluetooth" : null;
        }
    }

    /**
     * Abre el selector de salida de medios. Devuelve lo que se abrió:
     * "sistema" (selector de salida), "panel" (panel de volumen), "bluetooth" (ajustes) o "" si nada.
     */
    static String elegir(Activity a) {
        // Android 14+: selector oficial de salida para apps de música
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                if (android.media.MediaRouter2.getInstance(a).showSystemOutputSwitcher()) return "sistema";
            } catch (Exception ignored) { }
        }
        // Android 12 y 13: el mismo cuadro «Salida de medios» de la interfaz del sistema
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Intent i = new Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                        .setPackage("com.android.systemui").putExtra("package_name", a.getPackageName());
                a.sendBroadcast(i);
                return "sistema";
            } catch (Exception ignored) { }
        }
        // Android 10 y 11: panel de salida de medios de Ajustes
        if (Build.VERSION.SDK_INT >= 29) {
            Intent panel = new Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", a.getPackageName());
            if (abrir(a, panel)) return "sistema";
            if (abrir(a, new Intent(Settings.Panel.ACTION_VOLUME))) return "panel";
        }
        // Respaldo: ajustes de Bluetooth para conectar audífonos o bocinas
        if (abrir(a, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) return "bluetooth";
        return "";
    }

    private static boolean abrir(Activity a, Intent i) {
        try {
            if (i.resolveActivity(a.getPackageManager()) == null) return false;
            a.startActivity(i);
            return true;
        } catch (Exception e) { return false; }
    }

    static void vigilar(Context ctx, Aviso aviso) {
        MediaRouter mr = (MediaRouter) ctx.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        if (mr == null || vigilante != null) return;
        final Context app = ctx.getApplicationContext();
        vigilante = new MediaRouter.SimpleCallback() {
            @Override public void onRouteSelected(MediaRouter r, int type, MediaRouter.RouteInfo info) { aviso.cambio(actual(app)); }
            @Override public void onRouteUnselected(MediaRouter r, int type, MediaRouter.RouteInfo info) { aviso.cambio(actual(app)); }
            @Override public void onRouteChanged(MediaRouter r, MediaRouter.RouteInfo info) { aviso.cambio(actual(app)); }
        };
        mr.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, vigilante);
    }

    static void dejarDeVigilar(Context ctx) {
        MediaRouter mr = (MediaRouter) ctx.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        if (mr != null && vigilante != null) mr.removeCallback(vigilante);
        vigilante = null;
    }
}
