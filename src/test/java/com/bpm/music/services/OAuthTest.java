package com.bpm.music.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Entrar con Google, Facebook o Apple: el «state» firmado y los botones según la configuración. */
class OAuthTest {

    private OAuthService sinClaves() {
        return new OAuthService(null, null, null, null, null, "https://music.example/", "", "", "", "", "", "", "", "");
    }

    @Test
    void stateFirmadoDelMismoProveedorYSinTocar() {
        OAuthService o = sinClaves();
        String s = o.crearState("google", "ios");
        assertEquals("ios", o.leerState("google", s));
        assertNull(o.leerState("facebook", s), "un state de Google no vale para Facebook");
        String alterado = s.substring(0, s.indexOf('.')) + ".x" + s.substring(s.indexOf('.') + 2);
        assertNull(o.leerState("google", alterado));
        assertNull(o.leerState("google", "basura"));
        assertNull(o.leerState("google", null));
        // Otro servidor (otra clave) no puede usarlo
        assertNull(sinClaves().leerState("google", s));
    }

    @Test
    void sinClavesNoHayBotonesNiInicio() {
        OAuthService o = sinClaves();
        assertTrue(o.proveedores().values().stream().noneMatch(Boolean::booleanValue));
        assertThrows(IllegalArgumentException.class, () -> o.urlInicio("google", "web"));
        assertThrows(IllegalArgumentException.class, () -> o.urlInicio("otro", "web"));
    }

    @Test
    void conClavesArmaLaDireccionDeGoogle() {
        OAuthService o = new OAuthService(null, null, null, null, null, "https://music.example", "id-google", "secreto", "", "", "", "", "", "");
        assertTrue(o.proveedores().get("google"));
        String url = o.urlInicio("google", "web");
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fmusic.example%2Fapi%2Fauth%2Foauth%2Fgoogle%2Fvuelta"));
        assertTrue(url.contains("client_id=id-google"));
        // Vuelta con un state que no es de este servidor: no entra
        assertTrue(o.completar("google", "codigo", "malo", null, null).contains("error="));
    }
}
