package com.bpm.spacemusic.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Al encender la TV (o al actualizar la app) queda lista para recibir música sin abrirla. */
public class Arranque extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Escucha.iniciar(c);
    }
}
