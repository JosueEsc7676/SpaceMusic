package com.bpm.music.services;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SincroniaLetraTest {
    private static final String LRC = "[ar:Artista]\n[00:10.00]<00:10.00>uno <00:11.00>dos\n[00:15.00]tres";
    @Test void conservaTextoYTiemposYUsaLaConvencionLrc() {
        var nueva = SincroniaLetra.ajustar(LRC, 1.5);
        assertTrue(nueva.startsWith("[offset:-1500]\n"));
        assertTrue(nueva.endsWith(LRC));
        assertEquals(1.5, SincroniaLetra.segundos(nueva));
    }
    @Test void reemplazaEtiquetasSinAcumularAjustes() {
        var a = SincroniaLetra.ajustar("[OFFSET:500]\n[offset:200]\n" + LRC, -0.5);
        assertEquals(-0.5, SincroniaLetra.segundos(a));
        assertEquals(1, a.split("offset:", -1).length - 1);
        assertEquals(a, SincroniaLetra.ajustar(a, -0.5));
        assertEquals(0, SincroniaLetra.segundos(SincroniaLetra.ajustar(a, 0.0)));
    }
    @Test void rechazaDatosNoValidosYLetraSinTiempos() {
        for (Double n : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, 60.1, -60.1})
            assertThrows(IllegalArgumentException.class, () -> SincroniaLetra.ajustar(LRC, n));
        assertThrows(IllegalArgumentException.class, () -> SincroniaLetra.ajustar("sin tiempos", 1.0));
        assertThrows(IllegalArgumentException.class, () -> SincroniaLetra.ajustar(null, 1.0));
    }
    @Test void revisionDetectaCambiosInclusoDeMayusculasOTiempos() {
        assertEquals(SincroniaLetra.revision(LRC), SincroniaLetra.revision(LRC));
        assertNotEquals(SincroniaLetra.revision(LRC), SincroniaLetra.revision(LRC.toUpperCase()));
        assertNotEquals(SincroniaLetra.revision(LRC), SincroniaLetra.revision(SincroniaLetra.ajustar(LRC, 1.0)));
        assertEquals(64, SincroniaLetra.revision(LRC).length());
    }
}
