package com.bpm.music.experiencias;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.models.Seguimiento;
import com.bpm.music.models.Usuario;
import com.bpm.music.organizador.CaracteristicaAudio;
import com.bpm.music.organizador.CaracteristicaAudioRepository;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.UsuarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Experiencias: consentimiento y privacidad, solo catálogo local, rutas graduales y límites de espacio. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:experienciastest;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=clave-solo-para-pruebas-automaticas-de-spacemusic",
        "music.path=${java.io.tmpdir}/sm-test-music", "music.playlist-images-path=${java.io.tmpdir}/sm-test-uploads",
        "soporte.respaldos.ruta=${java.io.tmpdir}/sm-test-respaldos", "music.scan-interval-minutes=100000",
        "app.require-email-verification=false", "ytmusic.python=/no/existe", "ytmusic.script=/no/existe", "organizador.ffmpeg=/no/existe"
})
class ExperienciasTest {

    @Autowired UsuarioRepository usuarios;
    @Autowired SeguimientoRepository seguimientos;
    @Autowired CancionRepository canciones;
    @Autowired BibliotecaService biblioteca;
    @Autowired UsuarioService usuarioService;
    @Autowired CaracteristicaAudioRepository audio;
    @Autowired DedicatoriasService dedicatorias;
    @Autowired CoincidimosService coincidimos;
    @Autowired DescubrirService descubrir;
    @Autowired RutasService rutas;
    @Autowired PrepararOfflineService preparar;

    Usuario ana, beto, carla;
    List<Cancion> lista = new ArrayList<>();

    private Usuario u(String n) {
        return usuarios.findByUsernameIgnoreCase(n).orElseGet(() -> {
            Usuario x = new Usuario(); x.setUsername(n); x.setEmail(n + "@ejemplo.test"); x.setPassword("x"); x.setRol(Usuario.Rol.USER); x.setActivo(true);
            return usuarios.save(x);
        });
    }

    private void sigue(Usuario a, Usuario b) {
        if (seguimientos.siguiendo(a.getId()).contains(b.getId())) return;
        Seguimiento s = new Seguimiento(); s.setSeguidorId(a.getId()); s.setSeguidoId(b.getId()); seguimientos.save(s);
    }

    @BeforeEach
    void preparar() throws Exception {
        ana = u("ana_exp"); beto = u("beto_exp"); carla = u("carla_exp");
        if (canciones.count() < 30) {
            Path dir = Files.createTempDirectory("sm-exp");
            for (int i = 0; i < 30; i++) {
                Path f = dir.resolve("c" + i + ".mp3");
                Files.write(f, new byte[200_000]); // 200 KB cada una
                Cancion c = new Cancion();
                c.setNombre("Canción " + i); c.setArtista("Artista " + (i % 10)); c.setAlbum("Álbum " + (i % 5));
                c.setGenero(i % 2 == 0 ? "Bachata" : "Reggaetón"); c.setDuracionSegundos(180); c.setRutaArchivo(f.toString());
                c = canciones.save(c);
                CaracteristicaAudio a = new CaracteristicaAudio();
                a.setCancionId(c.getId()); a.setEnergia(i / 29.0); a.setHuellaArchivo("x"); audio.save(a);
            }
            biblioteca.invalidar();
        }
        lista = canciones.findAll();
    }

    @Test
    void dedicatoriasSoloAQuienTeSigueYConBloqueo() {
        Long cancion = lista.get(0).getId();
        // Beto no sigue a Ana: no puede recibir sus dedicatorias
        assertThrows(IllegalArgumentException.class, () -> dedicatorias.enviar(ana.getId(), "beto_exp", cancion, 30, "hola"));
        sigue(beto, ana);
        Dedicatoria d = dedicatorias.enviar(ana.getId(), "@beto_exp", cancion, 30, "para ti");
        assertEquals(30, d.getSegundo());
        // Solo los dos la ven
        assertThrows(NoSuchElementException.class, () -> dedicatorias.ver(d.getId(), carla.getId()));
        assertEquals(true, dedicatorias.ver(d.getId(), beto.getId()).get("recibida"));
        // Solo quien la recibe responde o la guarda
        assertThrows(NoSuchElementException.class, () -> dedicatorias.guardar(d.getId(), ana.getId(), true));
        assertEquals(true, dedicatorias.guardar(d.getId(), beto.getId(), true).get("guardada"));
        // Bloqueo: ya no recibe de Ana
        dedicatorias.bloquear(beto.getId(), "ana_exp", true);
        assertThrows(IllegalArgumentException.class, () -> dedicatorias.enviar(ana.getId(), "beto_exp", cancion, 0, null));
        dedicatorias.bloquear(beto.getId(), "ana_exp", false);
        assertThrows(IllegalArgumentException.class, () -> dedicatorias.enviar(ana.getId(), "beto_exp", cancion, 0, "x".repeat(300)));
    }

    @Test
    void coincidimosNecesitaAceptacionYSoloLoVenLosDos() {
        assertThrows(IllegalArgumentException.class, () -> coincidimos.invitar(carla.getId(), "ana_exp"), "sin relación no se puede invitar");
        sigue(ana, carla);
        SesionCoincidencia s = coincidimos.invitar(ana.getId(), "carla_exp");
        // Antes de aceptar no hay aportes
        assertThrows(IllegalArgumentException.class, () -> coincidimos.aportar(s.getId(), ana.getId(), lista.get(0).getId()));
        // Beto no participa: no la ve
        assertThrows(NoSuchElementException.class, () -> coincidimos.ver(s.getId(), beto.getId()));
        // Solo la invitada responde
        assertThrows(IllegalArgumentException.class, () -> coincidimos.responder(s.getId(), ana.getId(), true));
        coincidimos.responder(s.getId(), carla.getId(), true);
        for (int i = 0; i < 5; i++) coincidimos.aportar(s.getId(), ana.getId(), lista.get(i).getId());
        assertThrows(IllegalArgumentException.class, () -> coincidimos.aportar(s.getId(), ana.getId(), lista.get(6).getId()), "máximo 5");
        assertThrows(IllegalArgumentException.class, () -> coincidimos.reaccionar(s.getId(), carla.getId(), lista.get(0).getId(), "ODIO"));
        coincidimos.reaccionar(s.getId(), carla.getId(), lista.get(0).getId(), "SORPRENDIO");
        Map<String, Object> v = coincidimos.ver(s.getId(), carla.getId());
        assertEquals(5, ((List<?>) v.get("suAporte")).size());
        coincidimos.cerrar(s.getId(), carla.getId());
    }

    @Test
    void descubrirSoloLoNoEscuchadoYRespetaDescartes() {
        Long escuchada = lista.get(0).getId();
        usuarioService.registrarReproduccion(beto.getId(), escuchada, "prueba", 120);
        List<Map<String, Object>> r = descubrir.descubrir(beto.getId(), "sorpresa", 60);
        Set<Long> ids = new HashSet<>();
        r.forEach(m -> ids.add(((CancionDTO) m.get("cancion")).getId()));
        assertFalse(ids.contains(escuchada), "nunca recomienda lo ya escuchado");
        assertTrue(r.stream().allMatch(m -> m.get("razon") instanceof String s && !s.isBlank()), "cada una explica por qué");
        Long hoyNo = ids.iterator().next();
        descubrir.descartar(beto.getId(), hoyNo, "hoy");
        assertTrue(descubrir.descubrir(beto.getId(), "sorpresa", 60).stream().noneMatch(m -> ((CancionDTO) m.get("cancion")).getId().equals(hoyNo)));
        CancionDTO bachata = biblioteca.porId(lista.get(2).getId()).orElseThrow();
        descubrir.descartar(beto.getId(), bachata.getId(), "estilo");
        assertTrue(descubrir.descubrir(beto.getId(), "sorpresa", 60).stream().noneMatch(m -> "bachata".equals(m.get("estilo"))), "el estilo descartado no aparece");
        assertThrows(IllegalArgumentException.class, () -> descubrir.descartar(beto.getId(), bachata.getId(), "otro"));
    }

    @Test
    void rutaDeCalmaAEnergiaSubeDeVerdad() {
        Map<String, Object> r = rutas.armar(ana.getId(), new RutasService.Pedido(30, "calma", "energia", null, null, 0), null, null);
        assertEquals(true, r.get("disponible"));
        @SuppressWarnings("unchecked") List<Double> e = (List<Double>) r.get("energias");
        assertTrue(e.size() >= 5);
        double inicio = e.subList(0, 3).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double fin = e.subList(e.size() - 3, e.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        assertTrue(fin > inicio + 0.2, "debe subir la energía: " + e);
        // Más calma desde donde va: lo que queda baja
        @SuppressWarnings("unchecked") List<CancionDTO> cs = (List<CancionDTO>) r.get("canciones");
        Map<String, Object> aj = rutas.armar(ana.getId(), new RutasService.Pedido(30, "calma", "energia", null, null, 0), cs.subList(0, 6).stream().map(CancionDTO::getId).toList(), "calma");
        @SuppressWarnings("unchecked") List<Double> e2 = (List<Double>) aj.get("energias");
        assertEquals(cs.get(5).getId(), ((List<CancionDTO>) aj.get("canciones")).get(5).getId(), "lo ya escuchado se conserva");
        assertTrue(e2.get(e2.size() - 1) < e.get(e.size() - 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prepararRespetaElEspacioConTamanosReales() {
        lista.subList(0, 10).forEach(c -> usuarioService.registrarReproduccion(carla.getId(), c.getId(), "prueba", 120));
        Map<String, Object> r = preparar.proponer(carla.getId(), 600, 1, 50); // 1 MB pedido → mínimo 50 MB permitido
        long bytes = (long) r.get("bytes");
        assertTrue(bytes <= 50L * 1024 * 1024);
        List<Map<String, Object>> l = (List<Map<String, Object>>) r.get("canciones");
        assertTrue(l.stream().allMatch(m -> ((Long) m.get("bytes")) == 200_000L), "tamaño real del archivo");
        Map<String, Object> pocas = preparar.proponer(carla.getId(), 600, 50, 100);
        assertTrue(((List<?>) pocas.get("canciones")).size() <= 260);
    }
}
