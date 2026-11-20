package com.bpm.music.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Búsqueda de letras: variantes del título y del artista, y limpieza del LRC. */
class LetrasFuentesTest {

    @Test
    void artistaPrincipalYTituloLimpio() {
        assertEquals("Bad Bunny", LetrasService.principal("Bad Bunny feat. Jhay Cortez"));
        assertEquals("Peso Pluma", LetrasService.principal("Peso Pluma, Natanael Cano"));
        assertEquals("Touch Your Body", LetrasService.limpiarTitulo("Touch Your Body | Sauce Boyz 2"));
        assertEquals("Hilito", LetrasService.limpiarTitulo("Hilito (feat. Alguien)"));
        assertEquals("Cuarentena", LetrasService.limpiarTitulo("Cuarentena (Official Video)"));
    }

    @Test
    void lrcSinCreditosNiCabeceras() {
        String lrc = "[ar:Drake]\n[ti:Hotline Bling]\n[00:01.00]作词 : Aubrey Graham\n[00:02.00]Lyrics by: Alguien\n"
                + "[00:22.44]You used to call me on my\n\n\n[00:24.49]You used to, you used to\n";
        String limpio = LetrasService.limpiarLrc(lrc);
        assertFalse(limpio.contains("[ar:"));
        assertFalse(limpio.contains("作词"));
        assertFalse(limpio.toLowerCase().contains("lyrics by"));
        assertTrue(limpio.startsWith("[00:22.44]You used to call me on my"));
        assertEquals(2, limpio.split("\n").length);
    }
}
