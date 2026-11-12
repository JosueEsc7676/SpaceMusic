package com.bpm.music.organizador;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.externo.YouTubeMusicProveedor;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.services.BibliotecaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Recomendaciones con YouTube Music: solo canciones del servidor, sin versiones confundidas ni descargas. */
class SenalesExternasTest {

    BibliotecaService biblioteca = mock(BibliotecaService.class);
    CancionRepository canciones = mock(CancionRepository.class);
    CancionExternaRepository vinculos = mock(CancionExternaRepository.class);
    SimilitudRepository similitudes = mock(SimilitudRepository.class);
    YouTubeMusicProveedor ytm = mock(YouTubeMusicProveedor.class);
    SenalesExternasService senales;
    CancionExterna vA;

    static CancionDTO c(long id, String n, String a, int d) {
        CancionDTO x = new CancionDTO();
        x.setId(id); x.setNombre(n); x.setArtista(a); x.setDuracionSegundos(d);
        return x;
    }

    @BeforeEach
    void preparar() {
        when(biblioteca.todas()).thenReturn(List.of(c(1, "Propuesta Indecente", "Romeo Santos", 236),
                c(2, "Eres Mía", "Romeo Santos", 251), c(3, "Hilito", "Romeo Santos", 235)));
        when(ytm.disponible()).thenReturn(true);
        vA = new CancionExterna();
        vA.setCancionId(1L); vA.setProveedor("ytmusic"); vA.setIdExterno("wJYy7Yuf1Ig"); vA.setEstado(CancionExterna.Estado.VERIFICADA);
        when(vinculos.findAll()).thenReturn(List.of(vA));
        when(vinculos.pendientesDeRelacionadas(eq("ytmusic"), any(LocalDateTime.class), any())).thenReturn(List.of(vA));
        senales = new SenalesExternasService(biblioteca, canciones, vinculos, similitudes, ytm);
    }

    @Test
    @SuppressWarnings("unchecked")
    void soloGuardaCancionesDelServidorYSinVersionesDistintas() throws Exception {
        when(ytm.relacionadas("wJYy7Yuf1Ig", 25)).thenReturn(new ObjectMapper().readTree("""
            {"ok":true,"resultados":[
              {"videoId":"DYuhnVSOzwE","titulo":"Eres Mía","artistas":["Romeo Santos"],"duracion":251},
              {"videoId":"AAAAAAAAAAA","titulo":"Hilito (En Vivo)","artistas":["Romeo Santos"],"duracion":260},
              {"videoId":"BBBBBBBBBBB","titulo":"Canción que no está","artistas":["Otro"],"duracion":200}
            ]}"""));
        assertEquals(1, senales.relacionadasLote(10, () -> true));
        ArgumentCaptor<List<Similitud>> cap = ArgumentCaptor.forClass(List.class);
        verify(similitudes).saveAll(cap.capture());
        List<Similitud> guardadas = cap.getValue();
        assertEquals(1, guardadas.size(), "solo 'Eres Mía' está en el servidor; la versión en vivo no es 'Hilito'");
        assertEquals(2L, guardadas.get(0).getSimilarId());
        // Nunca se pide descargar nada
        verify(ytm, never()).buscar(anyString(), anyString(), anyInt());
    }

    @Test
    void siElProveedorFallaNoRompeNiBorraLoGuardado() {
        when(ytm.relacionadas(anyString(), anyInt())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "caído"));
        assertEquals(0, senales.relacionadasLote(10, () -> true));
        verify(similitudes, never()).borrarDe(anyLong(), anyString());
        // Sin datos, el peso es 0 y la radio sigue con sus señales locales
        when(similitudes.findAll()).thenReturn(List.of());
        assertEquals(0.0, senales.peso(1L, 2L));
    }

    @Test
    void pesoEnAmbosSentidos() {
        when(similitudes.findAll()).thenReturn(List.of(new Similitud(1L, 2L, "ytmusic", 0.9)));
        senales.recargar();
        assertEquals(0.9, senales.peso(1L, 2L), 1e-9);
        assertTrue(senales.peso(2L, 1L) > 0 && senales.peso(2L, 1L) < 0.9);
        assertEquals(List.of(2L), senales.parecidas(1L));
    }

    @Test
    void vinculoAmbiguoNoSeVerifica() throws Exception {
        when(vinculos.cancionesConVinculo("ytmusic")).thenReturn(List.of(1L, 3L));
        when(vinculos.findByProveedorAndCancionId(eq("ytmusic"), anyLong())).thenReturn(Optional.empty());
        when(ytm.buscar(anyString(), eq("songs"), eq(5))).thenReturn(new ObjectMapper().readTree("""
            {"ok":true,"resultados":[{"videoId":"CCCCCCCCCCC","titulo":"Eres Mía","artistas":["Romeo Santos"],"duracion":265}]}"""));
        senales.vincularLote(5, () -> true);
        ArgumentCaptor<CancionExterna> cap = ArgumentCaptor.forClass(CancionExterna.class);
        verify(vinculos).save(cap.capture());
        assertEquals(CancionExterna.Estado.AMBIGUA, cap.getValue().getEstado(), "14 s de diferencia: queda para revisión");
    }
}
