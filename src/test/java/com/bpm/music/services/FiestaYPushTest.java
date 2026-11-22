package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class FiestaYPushTest {

    private FiestaService fiesta() {
        BibliotecaService bib = mock(BibliotecaService.class);
        when(bib.porId(anyLong())).thenAnswer(i -> {
            CancionDTO c = mock(CancionDTO.class);
            when(c.getId()).thenReturn(i.getArgument(0));
            return Optional.of(c);
        });
        UsuarioRepository repo = mock(UsuarioRepository.class);
        Usuario u = mock(Usuario.class);
        when(u.getNombreVisible()).thenReturn("Ana");
        when(repo.findById(1L)).thenReturn(Optional.of(u));
        return new FiestaService(bib, repo);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> propuestas(Map<String, Object> e) { return (List<Map<String, Object>>) e.get("propuestas"); }

    @Test
    void lasMasVotadasVanPrimeroYLaTvLasToma() {
        FiestaService f = fiesta();
        String token = (String) f.abrir(1L).get("token");
        assertEquals("Ana", f.verPublica(token, null).get("anfitrion"));
        f.proponer(token, 10, "invitado-aaaa", "Beto", "1.1.1.1");
        f.proponer(token, 20, "invitado-bbbb", "Caro", "1.1.1.2");
        // Proponer una que ya está cuenta como voto
        Map<String, Object> e = f.proponer(token, 20, "invitado-cccc", "Dani", "1.1.1.3");
        List<Map<String, Object>> l = propuestas(e);
        assertEquals(2, l.size());
        assertEquals(2, l.get(0).get("votos"));
        long primera = ((Number) l.get(0).get("id")).longValue();
        // Votar de nuevo quita el voto
        f.votar(token, primera, "invitado-cccc", "1.1.1.3");
        assertEquals(1, propuestas(f.mia(1L)).get(0).get("votos"));
        f.tomar(1L, primera);
        assertEquals(1, propuestas(f.mia(1L)).size());
        f.cerrar(1L);
        assertThrows(NoSuchElementException.class, () -> f.verPublica(token, null));
    }

    @Test
    void cadaInvitadoProponeComoMaximoTres() {
        FiestaService f = fiesta();
        String token = (String) f.abrir(1L).get("token");
        for (int i = 1; i <= 3; i++) f.proponer(token, i, "invitado-zzzz", null, "2.2.2.2");
        assertThrows(IllegalStateException.class, () -> f.proponer(token, 9, "invitado-zzzz", null, "2.2.2.2"));
        assertThrows(IllegalArgumentException.class, () -> f.proponer(token, 9, "x", null, "2.2.2.2"));
    }

    @Test
    void losAvisosPushSoloVanAServidoresPublicosConHttps() {
        assertThrows(IllegalArgumentException.class, () -> PushService.validar("http://ntfy.sh/abc"));
        assertThrows(IllegalArgumentException.class, () -> PushService.validar("https://127.0.0.1/abc"));
        assertThrows(IllegalArgumentException.class, () -> PushService.validar("https://192.168.10.5/abc"));
        assertThrows(IllegalArgumentException.class, () -> PushService.validar("https://usuario:clave@ntfy.sh/abc"));
        assertEquals("https://8.8.8.8/upABC", PushService.validar("https://8.8.8.8/upABC"));
    }

    @Test
    void laTraduccionQuitaLasComillasDeFrasesCortas() {
        assertEquals("Dije ooh", TraduccionService.limpiar("«Dije ooh»."));
        assertEquals("Me haces sentir", TraduccionService.limpiar("Me haces sentir"));
    }
}
