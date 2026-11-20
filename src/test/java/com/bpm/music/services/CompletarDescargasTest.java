package com.bpm.music.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Solo se completan las bajadas de YouTube (no las de Spotify, que ya traen todo). */
class CompletarDescargasTest {
    @Test
    void reconoceLasBajadasDeYoutube() {
        assertTrue(CompletarDescargasService.esDeYoutube("/home/x/spacemusic/musica/Descargas/Eladio Carrión/Cuarentena.m4a"));
        assertFalse(CompletarDescargasService.esDeYoutube("/home/x/spacemusic/musica/Descargas/Drake/Views/07 - Hotline Bling.m4a"));
        assertFalse(CompletarDescargasService.esDeYoutube("/home/x/spacemusic/musica/Descargas/Directas/tema.mp3"));
        assertFalse(CompletarDescargasService.esDeYoutube("/home/x/spacemusic/musica/Subidas/tema.mp3"));
    }
}
