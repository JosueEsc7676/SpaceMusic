package com.bpm.music.services;

import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Control remoto: cada cuenta solo ve y ordena a sus propios dispositivos; órdenes y datos validados. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:controltest;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=clave-solo-para-pruebas-automaticas-de-spacemusic",
        "music.path=${java.io.tmpdir}/sm-test-music", "music.playlist-images-path=${java.io.tmpdir}/sm-test-uploads",
        "soporte.respaldos.ruta=${java.io.tmpdir}/sm-test-respaldos", "music.scan-interval-minutes=100000",
        "app.require-email-verification=false", "ytmusic.python=/no/existe", "ytmusic.script=/no/existe", "organizador.ffmpeg=/no/existe"
})
class ControlRemotoTest {

    @Autowired ControlRemotoService control;
    @Autowired CancionRepository canciones;
    @Autowired BibliotecaService biblioteca;

    Long c1, c2;

    @BeforeEach
    void preparar() throws Exception {
        if (canciones.count() < 2) {
            Path dir = Files.createTempDirectory("sm-control");
            for (int i = 0; i < 2; i++) {
                Path f = dir.resolve("r" + i + ".mp3");
                Files.write(f, new byte[1000]);
                Cancion c = new Cancion();
                c.setNombre("Remota " + i); c.setArtista("Artista"); c.setDuracionSegundos(200); c.setRutaArchivo(f.toString());
                canciones.save(c);
            }
            biblioteca.invalidar();
        }
        List<Cancion> l = canciones.findAll();
        c1 = l.get(0).getId(); c2 = l.get(1).getId();
    }

    private Map<String, Object> estado(String id, Long cancion, boolean sonando) {
        Map<String, Object> m = new HashMap<>();
        m.put("dispositivo", id); m.put("nombre", "Teléfono " + id); m.put("plataforma", "android");
        m.put("cancionId", cancion); m.put("segundo", 30); m.put("duracion", 200); m.put("sonando", sonando); m.put("volumen", 0.8);
        return m;
    }

    @Test
    void cadaCuentaSoloVeSusDispositivos() {
        control.presencia(1001L, estado("telefono-ana-1", c1, true));
        control.presencia(1001L, estado("compu-ana-0002", null, false));
        control.presencia(2002L, estado("telefono-beto-1", c2, true));
        var deAna = control.lista(1001L, "compu-ana-0002");
        assertEquals(2, deAna.size());
        assertEquals("telefono-ana-1", deAna.get(0).get("id"), "primero el que está sonando");
        assertEquals(true, deAna.stream().filter(d -> d.get("id").equals("compu-ana-0002")).findFirst().orElseThrow().get("esEste"));
        assertTrue(deAna.stream().noneMatch(d -> d.get("id").equals("telefono-beto-1")));
        // Beto no puede ordenar al teléfono de Ana (para él no existe)
        assertThrows(NoSuchElementException.class, () -> control.orden(2002L, "telefono-ana-1", null, "pause", Map.of()));
        control.orden(1001L, "telefono-ana-1", "compu-ana-0002", "pause", Map.of());
        // Al salir desaparece
        control.salir(1001L, "telefono-ana-1");
        assertEquals(1, control.lista(1001L, null).size());
    }

    @Test
    void ordenesYDatosValidados() {
        control.presencia(3003L, estado("tablet-carla-1", c1, false));
        assertThrows(IllegalArgumentException.class, () -> control.orden(3003L, "tablet-carla-1", null, "borrar-todo", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> control.orden(3003L, "../../x", null, "pause", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> control.presencia(3003L, estado("corto", c1, true)));
        // reproducir sin canciones válidas: rechazado; con canciones: aceptado
        assertThrows(IllegalArgumentException.class, () -> control.orden(3003L, "tablet-carla-1", null, "reproducir", Map.of("cancionIds", List.of(999999))));
        control.orden(3003L, "tablet-carla-1", null, "reproducir", Map.of("cancionIds", List.of(c1, c2), "indice", 1));
        // Una canción que no existe no queda como «sonando»
        control.presencia(3003L, estado("tablet-carla-1", 999999L, true));
        var d = control.lista(3003L, null).get(0);
        assertNull(d.get("cancion"));
        assertEquals(false, d.get("sonando"));
    }
}
