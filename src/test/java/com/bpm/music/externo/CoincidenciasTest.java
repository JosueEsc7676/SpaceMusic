package com.bpm.music.externo;

import com.bpm.music.dtos.CancionDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.bpm.music.externo.Coincidencias.Resultado.*;
import static org.junit.jupiter.api.Assertions.*;

/** Coincidencias de catálogo: misma canción, versiones distintas y casos ambiguos. */
class CoincidenciasTest {

    private static CancionDTO local(long id, String nombre, String artista, int dur) {
        CancionDTO c = new CancionDTO();
        c.setId(id);
        c.setNombre(nombre);
        c.setArtista(artista);
        c.setDuracionSegundos(dur);
        return c;
    }

    @Test
    void mismaCancionConDetallesDistintos() {
        CancionDTO l = local(1, "Propuesta Indecente", "Romeo Santos", 236);
        assertEquals(MISMA, Coincidencias.comparar("Propuesta Indecente", List.of("Romeo Santos"), 238, l));
        assertEquals(MISMA, Coincidencias.comparar("Propuesta Indecente [Remastered]", List.of("Romeo Santos"), 235, l));
        assertEquals(MISMA, Coincidencias.comparar("PROPUESTA INDECENTE (feat. Nadie)", List.of("Romeo Santos", "Nadie"), 236, l));
    }

    @Test
    void versionesNoSonLaOriginal() {
        CancionDTO original = local(1, "Hilito", "Romeo Santos", 235);
        assertEquals(DISTINTA, Coincidencias.comparar("Hilito (En Vivo)", List.of("Romeo Santos"), 236, original));
        assertEquals(DISTINTA, Coincidencias.comparar("Hilito - Remix", List.of("Romeo Santos"), 235, original));
        assertEquals(DISTINTA, Coincidencias.comparar("Hilito (Acoustic)", List.of("Romeo Santos"), 235, original));
        assertEquals(DISTINTA, Coincidencias.comparar("Hilito (Sped Up)", List.of("Romeo Santos"), 200, original));
        CancionDTO enVivo = local(2, "Hilito (En Vivo)", "Romeo Santos", 260);
        assertEquals(DISTINTA, Coincidencias.comparar("Hilito", List.of("Romeo Santos"), 235, enVivo));
        assertEquals(MISMA, Coincidencias.comparar("Hilito (Live)", List.of("Romeo Santos"), 262, local(3, "Hilito (Live)", "Romeo Santos", 260)));
    }

    @Test
    void casosAmbiguos() {
        CancionDTO l = local(1, "Utopia", "Romeo Santos", 240);
        // Otro artista con una canción del mismo nombre
        assertEquals(AMBIGUA, Coincidencias.comparar("Utopia", List.of("Travis Scott"), 240, l));
        // Sin duración: no se confirma
        assertEquals(AMBIGUA, Coincidencias.comparar("Utopia", List.of("Romeo Santos"), null, l));
        // Duración algo distinta
        assertEquals(AMBIGUA, Coincidencias.comparar("Utopia", List.of("Romeo Santos"), 250, l));
        // Muy distinta: otra grabación
        assertEquals(DISTINTA, Coincidencias.comparar("Utopia", List.of("Romeo Santos"), 300, l));
        // Dos copias locales iguales: no se elige una sola
        var r = Coincidencias.buscar("Utopia", List.of("Romeo Santos"), 240, List.of(l, local(2, "Utopia", "Romeo Santos", 241)));
        assertEquals(AMBIGUA, r.getValue());
    }

    @Test
    void tituloBase() {
        assertEquals("propuesta indecente", Coincidencias.base("Propuesta Indecente (feat. X) [Remastered 2014]"));
        assertEquals("yesterday", Coincidencias.base("Yesterday - Remastered 2009"));
    }
}
