package com.bpm.music.soporte;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas sin servidor: limpieza de datos sensibles y agrupación de errores. */
class SoporteUnitarioTest {

    @Test
    void ocultaTokensContrasenasYCorreos() {
        String s = Sanitizador.limpiar("GET /api/x?t=abc123&y=1 Authorization: Bearer xyz.abc password=secreta123 de juan.perez@correo.com", 500);
        assertFalse(s.contains("abc123"));
        assertFalse(s.contains("xyz.abc"));
        assertFalse(s.contains("secreta123"));
        assertFalse(s.contains("juan.perez@"));
        assertTrue(s.contains("j***@correo.com"));
    }

    @Test
    void ocultaJwtSueltos() {
        String jwt = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIxMjM0NTY3OCJ9.abcdefghijklmnop";
        assertFalse(Sanitizador.limpiar("token " + jwt, 500).contains(jwt));
    }

    @Test
    void recortaTextoLargo() {
        assertEquals(11, Sanitizador.limpiar("a".repeat(50), 10).length());
        assertEquals("", Sanitizador.limpiar(null, 10));
    }

    @Test
    void rutasSinIdentificadores() {
        assertEquals("/api/canciones/{id}/audio", Sanitizador.patronRuta("/api/canciones/123/audio?t=zz"));
        assertEquals("/api/artistas/{x}", Sanitizador.patronRuta("/api/artistas/Romeo Santos"));
    }

    @Test
    void ipRecortada() {
        assertEquals("192.168.10.x", Sanitizador.ipParcial("192.168.10.18"));
        assertEquals("", Sanitizador.ipParcial(null));
    }

    @Test
    void mismoErrorConDistintosNumerosTieneLaMismaHuella() {
        String a = IncidenciasService.huella("ERROR", "com.bpm.X", "IOException", "No se pudo leer la canción 15 'a.mp3'");
        String b = IncidenciasService.huella("ERROR", "com.bpm.X", "IOException", "No se pudo leer la canción 982 'b.mp3'");
        String c = IncidenciasService.huella("ERROR", "com.bpm.X", "IOException", "Otro problema distinto");
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void categorias() {
        assertEquals("base de datos", IncidenciasService.categoria("org.hibernate.SQL", null));
        assertEquals("seguridad", IncidenciasService.categoria("com.bpm.music.security.JwtFilter", null));
        assertEquals("sistema", IncidenciasService.categoria("org.apache.catalina", null));
    }
}
