package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Portadas en línea: limpieza del título, coincidencia y solo servidores de portadas permitidos. */
class PortadaEnLineaTest {

    @Test
    void limpiaTitulosDeYouTube() {
        assertEquals("Touch Your Body", PortadaEnLineaService.tituloLimpio("Touch Your Body | Sauce Boyz 2"));
        assertEquals("Cuarentena", PortadaEnLineaService.tituloLimpio("Cuarentena (Official Video)"));
        assertEquals("Hilito", PortadaEnLineaService.tituloLimpio("Hilito [Letra]"));
    }

    @Test
    void puntuaMejorElMismoTituloYArtista() {
        CancionDTO c = new CancionDTO();
        c.setNombre("Touch Your Body | Sauce Boyz 2");
        c.setArtista("Eladio Carrión");
        assertEquals(3, PortadaEnLineaService.puntaje("Touch Your Body", "Eladio Carrión", c));
        assertTrue(PortadaEnLineaService.puntaje("Otra Canción", "Otro Artista", c) < 2);
    }

    @Test
    void soloDescargaDeITunesODeezer() {
        assertTrue(PortadaEnLineaService.permitida("https://is1-ssl.mzstatic.com/image/thumb/Music/x/1400x1400bb.jpg"));
        assertTrue(PortadaEnLineaService.permitida("https://e-cdns-images.dzcdn.net/images/cover/abc/1000x1000-000000-80-0-0.jpg"));
        assertFalse(PortadaEnLineaService.permitida("http://is1-ssl.mzstatic.com/a.jpg"));
        assertFalse(PortadaEnLineaService.permitida("https://mzstatic.com.evil.test/a.jpg"));
        assertFalse(PortadaEnLineaService.permitida("https://127.0.0.1/a.jpg"));
        assertFalse(PortadaEnLineaService.permitida("https://user@is1-ssl.mzstatic.com/a.jpg"));
        assertFalse(PortadaEnLineaService.permitida("file:///etc/passwd"));
    }
}
