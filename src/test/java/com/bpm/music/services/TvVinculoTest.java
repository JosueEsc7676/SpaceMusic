package com.bpm.music.services;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TvVinculoTest {

    private TvVinculoService servicio() {
        AuthService auth = mock(AuthService.class);
        UsuarioRepository repo = mock(UsuarioRepository.class);
        Usuario u = mock(Usuario.class);
        when(u.getNombreVisible()).thenReturn("Ana");
        when(repo.findById(7L)).thenReturn(Optional.of(u));
        when(auth.sesionPara(u)).thenReturn(Map.of("ok", true, "token", "tok-ana", "usuario", Map.of("username", "ana")));
        return new TvVinculoService(auth, repo);
    }

    @Test
    void laTvEntraCuandoElTelefonoApruebaYSoloUnaVez() {
        TvVinculoService s = servicio();
        Map<String, Object> v = s.crear("TV · Sala", "203.0.113.5");
        String codigo = (String) v.get("codigo"), secreto = (String) v.get("secreto");
        assertEquals(6, codigo.length());
        assertEquals("esperando", s.estado(secreto).get("estado"));
        assertEquals("TV · Sala", s.ver(codigo.toLowerCase()).get("nombre"));
        s.aprobar(7L, codigo);
        Map<String, Object> r = s.estado(secreto);
        assertEquals("aprobado", r.get("estado"));
        assertEquals("tok-ana", r.get("token"));
        assertEquals("Ana", r.get("aprobadoPor"));
        // La sesión se entrega una sola vez y el código deja de servir
        assertEquals("vencido", s.estado(secreto).get("estado"));
        assertThrows(NoSuchElementException.class, () -> s.aprobar(7L, codigo));
    }

    @Test
    void soloSeVenLasTvsDeLaMismaRed() {
        TvVinculoService s = servicio();
        s.crear("TV casa", "203.0.113.5");
        s.crear("TV local", "192.168.10.120");
        assertEquals(1, s.cercanas("203.0.113.5").size());
        assertEquals("TV local", s.cercanas("192.168.10.44").get(0).get("nombre"));
        assertTrue(s.cercanas("198.51.100.9").isEmpty());
        assertThrows(NoSuchElementException.class, () -> s.ver("ZZZZZZ"));
    }

    @Test
    void secretoDesconocidoNoEntregaNada() {
        assertEquals("vencido", servicio().estado("no-existe").get("estado"));
    }
}
