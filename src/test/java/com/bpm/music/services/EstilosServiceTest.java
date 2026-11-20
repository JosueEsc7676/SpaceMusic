package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Estilos concretos a partir de géneros (sin servidor). */
class EstilosServiceTest {

    private static String clave(String genero) {
        var e = EstilosService.deGenero(genero);
        return e == null ? null : e.clave();
    }

    @Test
    void generosConcretosTienenSuEstilo() {
        assertEquals("bachata", clave("Bachata"));
        assertEquals("reggaeton", clave("Reggaetón"));
        assertEquals("trap-latino", clave("Trap latino"));
        assertEquals("trap-latino", clave("latin hip hop"));
        assertEquals("trap", clave("Trap"));
        assertEquals("hiphop", clave("Hip hop"));
        assertEquals("rnb", clave("R&B alternativo"));
        assertEquals("rock", clave("Rock alternativo"));
        assertEquals("corridos", clave("Corrido tumbado"));
        assertEquals("regional", clave("Latin/Música Mexicana"));
        assertEquals("regional", clave("Regional mexicano"));
        assertEquals("pop-latino", clave("Pop latino"));
        assertEquals("pop", clave("Pop"));
        assertEquals("pop", clave("Indie pop"));
        assertEquals("infantil", clave("Children's music"));
        assertEquals("cristiana", clave("Cristiana"));
        assertEquals("electronica", clave("Electrónica"));
    }

    @Test
    void generosGeneralesSeResuelvenPorElArtista() {
        // "Latino" no dice qué es: se busca por el artista
        assertNull(EstilosService.deGenero("Latino"));
        assertNull(EstilosService.deGenero("Desconocido"));
        assertNull(EstilosService.deGenero(""));
    }

    private static CancionDTO cancion(long id, String artista, String album) {
        CancionDTO c = new CancionDTO();
        c.setId(id);
        c.setNombre("c" + id);
        c.setArtista(artista);
        c.setAlbum(album);
        return c;
    }

    @Test
    void variadasNoAmontonaUnArtistaNiUnAlbum() {
        List<CancionDTO> l = new ArrayList<>();
        for (int i = 0; i < 20; i++) l.add(cancion(i, "Romeo Santos", "Utopia"));
        for (int i = 20; i < 25; i++) l.add(cancion(i, "Prince Royce", "Single"));
        l.add(cancion(30, "Aventura", "We Broke the Rules"));
        List<CancionDTO> r = EstilosService.variadas(l, 2, 6);
        assertEquals(6, r.size());
        Map<String, Integer> porArtista = new HashMap<>();
        r.forEach(c -> porArtista.merge(c.getArtista(), 1, Integer::sum));
        assertTrue(porArtista.size() >= 3, "deben aparecer varios artistas: " + porArtista);
        assertTrue(porArtista.getOrDefault("Romeo Santos", 0) <= 2, "no deben ir muchas seguidas del mismo álbum");
    }

    @Test
    void losEstilosDeLaIaSeEntiendenParaLosMix() {
        // Los valores que devuelve la IA tienen que caer en un estilo de los mix
        for (String v : com.bpm.music.organizador.IaLocalService.ESTILOS) {
            assertNotNull(EstilosService.estiloDeIa(v), "la IA dijo «" + v + "» y los mix no lo entienden");
        }
        assertNull(EstilosService.estiloDeIa(""));
    }
}
