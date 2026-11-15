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

    /** Canal de prueba: guarda lo que el servidor le manda a ese dispositivo. */
    static final class CanalPrueba implements ControlRemotoService.Canal {
        final List<String> recibido = Collections.synchronizedList(new ArrayList<>());
        public boolean enviar(String json) { recibido.add(json); return true; }
        boolean tiene(String fragmento) { synchronized (recibido) { return recibido.stream().anyMatch(x -> x.contains(fragmento)); } }
    }

    @Test
    void comoSpotifyConnectUnSoloDispositivoActivoYOrdenesConfirmadas() {
        long uid = 4004L;
        CanalPrueba telefono = new CanalPrueba(), compu = new CanalPrueba();
        control.conectar(uid, "telefono-dani-1", new HashMap<>(Map.of("nombre", "Teléfono", "plataforma", "android")), telefono);
        control.conectar(uid, "compu-dani-0001", new HashMap<>(Map.of("nombre", "Compu", "plataforma", "web")), compu);
        assertTrue(telefono.tiene("\"tipo\":\"hola\""), "saludo con la hora del servidor");
        assertTrue(compu.tiene("\"tipo\":\"cluster\""), "cada cambio llega como cluster");

        // Suena en el teléfono: pasa a ser el activo
        Map<String, Object> e = new HashMap<>(estado("telefono-dani-1", c1, true)); e.put("tipo", "estado"); e.put("seq", 1);
        control.mensaje(uid, "telefono-dani-1", e, telefono);
        assertEquals("telefono-dani-1", control.cluster(uid).get("activo"));

        // Un estado más viejo que llega tarde (seq menor) no pisa el actual
        Map<String, Object> viejo = new HashMap<>(estado("telefono-dani-1", c2, false)); viejo.put("tipo", "estado"); viejo.put("seq", 0);
        control.mensaje(uid, "telefono-dani-1", viejo, telefono);
        assertEquals(true, control.lista(uid, null).get(0).get("sonando"));

        // Empieza a sonar en la compu: la compu pasa a ser la activa y el teléfono recibe la orden de pausar
        Map<String, Object> e2 = new HashMap<>(estado("compu-dani-0001", c2, true)); e2.put("tipo", "estado"); e2.put("seq", 1);
        control.mensaje(uid, "compu-dani-0001", e2, compu);
        assertEquals("compu-dani-0001", control.cluster(uid).get("activo"));
        assertTrue(telefono.tiene("\"accion\":\"pause\""), "el anterior se pausa: un solo dispositivo suena a la vez");

        // Orden con confirmación: la compu manda «next» al teléfono, el teléfono confirma y la compu recibe el ack
        String id = control.orden(uid, "telefono-dani-1", "compu-dani-0001", "next", Map.of());
        assertTrue(telefono.tiene(id), "la orden llega por el WebSocket del destino");
        control.mensaje(uid, "telefono-dani-1", new HashMap<>(Map.of("tipo", "ack", "id", id, "ok", true)), telefono);
        assertTrue(compu.tiene("\"tipo\":\"ack\"") && compu.tiene(id), "quien mandó la orden recibe la confirmación");

        // «activo» como destino: va al dispositivo que está sonando
        String id2 = control.orden(uid, "activo", "telefono-dani-1", "pause", Map.of());
        assertTrue(compu.tiene(id2));

        // Reloj: ping → pong con la hora del servidor
        control.mensaje(uid, "compu-dani-0001", new HashMap<>(Map.of("tipo", "ping", "t", 123)), compu);
        assertTrue(compu.tiene("\"tipo\":\"pong\"") && compu.tiene("servidorMs"));

        // Al cerrarse el WebSocket sigue en la lista unos segundos (recargar no lo hace desaparecer)
        control.desconectar(uid, "telefono-dani-1", telefono);
        assertTrue(control.lista(uid, null).stream().anyMatch(d -> d.get("id").equals("telefono-dani-1") && d.get("enVivo").equals(false)));
    }
}
