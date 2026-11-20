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
    @Autowired JuntosService juntos;
    @Autowired DedicatoriaRepository dedicatoriasRepo;

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

    @Test
    void dedicatoriaConLetraElegidaYCancionCompleta() {
        Cancion c = lista.get(3);
        c.setLetraSincronizada("[00:10.00] primera linea\n[00:14.00] segunda linea\n\n[00:20.00] tercera linea");
        c.setLetra("primera linea\nsegunda linea\n\ntercera linea");
        canciones.save(c);
        biblioteca.invalidar();
        sigue(carla, beto);
        // Fragmento real (líneas seguidas): se guarda tal cual
        Dedicatoria d = dedicatorias.enviar(beto.getId(), "carla_exp", c.getId(), 10, "mira", false, List.of("primera linea", "segunda linea"), 20);
        assertEquals("primera linea\nsegunda linea", d.getLetra());
        assertEquals(20, d.getSegundoFin());
        // Texto inventado o líneas no seguidas: no
        assertThrows(IllegalArgumentException.class, () -> dedicatorias.enviar(beto.getId(), "carla_exp", c.getId(), 0, null, false, List.of("te amo mucho"), null));
        assertThrows(IllegalArgumentException.class, () -> dedicatorias.enviar(beto.getId(), "carla_exp", c.getId(), 0, null, false, List.of("primera linea", "tercera linea"), null));
        // Canción completa: sin fragmento y desde el inicio
        Dedicatoria completa = dedicatorias.enviar(beto.getId(), "carla_exp", c.getId(), 50, null, true, List.of("primera linea"), null);
        assertTrue(completa.getCompleta());
        assertNull(completa.getLetra());
        assertEquals(0, completa.getSegundo());
        assertEquals(true, dedicatorias.ver(completa.getId(), carla.getId()).get("completa"));
    }

    @Test
    void personasParaDedicarSonLasQueTeSiguen() {
        sigue(carla, ana);   // Carla sigue a Ana: Ana puede dedicarle
        sigue(ana, carla);   // y se siguen las dos
        var l = dedicatorias.personas(ana.getId(), null);
        assertTrue(l.stream().anyMatch(p -> p.get("username").equals("carla_exp") && Boolean.TRUE.equals(p.get("mutuo"))));
        assertTrue(l.stream().noneMatch(p -> p.get("username").equals("ana_exp")));
        assertTrue(dedicatorias.personas(ana.getId(), "zzz").isEmpty());
        assertEquals(1, dedicatorias.personas(ana.getId(), "@carla").stream().filter(p -> p.get("username").equals("carla_exp")).count());
    }

    @Test
    void dedicatoriaProgramadaNoSeVeHastaSuHora() {
        Usuario emi = u("emi_exp"), rafa = u("rafa_exp");
        sigue(rafa, emi);
        Long cancion = lista.get(1).getId();
        var cuando = java.time.LocalDateTime.now().plusHours(2);
        Dedicatoria d = dedicatorias.enviar(emi.getId(), "rafa_exp", cancion, 0, "feliz cumpleaños", true, List.of(), null, cuando);
        assertFalse(d.esEntregada());
        // Beto todavía no la ve ni en su lista ni por enlace; Ana sí, con la fecha de entrega
        assertThrows(NoSuchElementException.class, () -> dedicatorias.ver(d.getId(), rafa.getId()));
        assertTrue(((List<?>) dedicatorias.listas(rafa.getId()).get("recibidas")).stream().noneMatch(x -> ((Map<?, ?>) x).get("id").equals(d.getId())));
        assertNotNull(dedicatorias.ver(d.getId(), emi.getId()).get("programadaPara"));
        // Llega la hora: se entrega
        d.setProgramadaPara(java.time.LocalDateTime.now().minusMinutes(1));
        dedicatoriasRepo.save(d);
        dedicatorias.entregarProgramadas();
        assertEquals(true, dedicatorias.ver(d.getId(), rafa.getId()).get("recibida"));
        // Más de un año: no se permite; en el pasado: se envía al momento
        assertThrows(IllegalArgumentException.class, () -> dedicatorias.enviar(emi.getId(), "rafa_exp", cancion, 0, null, true, List.of(), null, java.time.LocalDateTime.now().plusYears(2)));
        assertTrue(dedicatorias.enviar(emi.getId(), "rafa_exp", cancion, 0, null, true, List.of(), null, java.time.LocalDateTime.now().minusDays(1)).esEntregada());
    }

    @Test
    void borrarDedicatoriasRecibidasYEnviadas() {
        Usuario gina = u("gina_exp"), hugo = u("hugo_exp");
        sigue(hugo, gina);
        Dedicatoria a = dedicatorias.enviar(gina.getId(), "hugo_exp", lista.get(5).getId(), 0, "uno");
        Dedicatoria b = dedicatorias.enviar(gina.getId(), "hugo_exp", lista.get(6).getId(), 0, "dos");
        Dedicatoria c = dedicatorias.enviar(gina.getId(), "hugo_exp", lista.get(7).getId(), 0, "tres");
        java.util.function.Function<Object, List<?>> l = x -> (List<?>) x;
        // Nadie más puede borrarla
        assertThrows(NoSuchElementException.class, () -> dedicatorias.ocultar(a.getId(), carla.getId()));
        // Quien la envió la quita de sus enviadas; quien la recibió la sigue viendo
        dedicatorias.ocultar(a.getId(), gina.getId());
        assertTrue(l.apply(dedicatorias.listas(gina.getId()).get("enviadas")).stream().noneMatch(x -> ((Map<?, ?>) x).get("id").equals(a.getId())));
        assertTrue(l.apply(dedicatorias.listas(hugo.getId()).get("recibidas")).stream().anyMatch(x -> ((Map<?, ?>) x).get("id").equals(a.getId())));
        // Borrar todas las recibidas respeta las guardadas
        dedicatorias.guardar(b.getId(), hugo.getId(), true);
        assertEquals(2, dedicatorias.vaciar(hugo.getId(), true));
        List<?> recibidas = l.apply(dedicatorias.listas(hugo.getId()).get("recibidas"));
        assertEquals(1, recibidas.size());
        assertEquals(b.getId(), ((Map<?, ?>) recibidas.get(0)).get("id"));
        // Borrar todas las enviadas
        assertEquals(2, dedicatorias.vaciar(gina.getId(), false));
        assertTrue(l.apply(dedicatorias.listas(gina.getId()).get("enviadas")).isEmpty());
        assertNotNull(dedicatoriasRepo.findById(c.getId()).orElse(null)); // solo se oculta: sigue para quien la recibió
    }

    @Test
    void cancelarProgramadaSoloQuienLaEnvio() {
        Usuario emi = u("emi_exp"), rafa = u("rafa_exp");
        sigue(rafa, emi);
        Dedicatoria d = dedicatorias.enviar(emi.getId(), "rafa_exp", lista.get(2).getId(), 0, null, true, List.of(), null, java.time.LocalDateTime.now().plusDays(1));
        assertThrows(NoSuchElementException.class, () -> dedicatorias.cancelarProgramada(d.getId(), rafa.getId()));
        dedicatorias.cancelarProgramada(d.getId(), emi.getId());
        assertTrue(dedicatoriasRepo.findById(d.getId()).isEmpty());
    }

    @Test
    void syncPlayConCodigoComoJam() {
        Usuario ivo = u("ivo_exp"), juli = u("juli_exp"), kari = u("kari_exp");
        Map<String, Object> sala = juntos.abrir(ivo.getId());
        String codigo = (String) sala.get("codigo");
        assertNotNull(codigo);
        assertTrue(codigo.matches("[A-Z0-9]{6}"));
        // Sin seguir a nadie ni invitación: con el código se entra (minúsculas y espacios no importan)
        Map<String, Object> dentro = juntos.unirseConCodigo(juli.getId(), " " + codigo.substring(0, 3).toLowerCase() + " " + codigo.substring(3) + " ");
        assertEquals(true, dentro.get("dentro"));
        assertEquals(sala.get("id"), dentro.get("id"));
        // Quien no está dentro no ve el código
        assertThrows(NoSuchElementException.class, () -> juntos.ver(((Number) sala.get("id")).longValue(), kari.getId()));
        // Código equivocado y límite de intentos
        assertThrows(IllegalArgumentException.class, () -> juntos.unirseConCodigo(kari.getId(), "ZZZZZZ"));
        for (int i = 0; i < JuntosService.MAX_INTENTOS; i++) {
            try { juntos.unirseConCodigo(kari.getId(), "ZZZZZ" + i); } catch (IllegalArgumentException ignored) { }
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> juntos.unirseConCodigo(kari.getId(), codigo));
        assertTrue(e.getMessage().contains("Demasiados intentos"));
        juntos.salir(((Number) sala.get("id")).longValue(), ivo.getId());
    }

    @Test
    void escucharJuntosSalaConInvitacionControlYCola() {
        Long c1 = lista.get(3).getId(), c2 = lista.get(4).getId();
        // Solo a quien te sigue
        assertThrows(IllegalArgumentException.class, () -> juntos.invitar(carla.getId(), "beto_exp"));
        sigue(beto, carla);
        long sala = ((Number) juntos.invitar(carla.getId(), "beto_exp").get("id")).longValue();
        // Ana no está invitada: no ve la sala
        assertThrows(NoSuchElementException.class, () -> juntos.ver(sala, ana.getId()));
        // Beto invitado, todavía fuera: no puede sumar a la cola
        assertEquals(false, juntos.ver(sala, beto.getId()).get("dentro"));
        assertThrows(IllegalArgumentException.class, () -> juntos.sumarACola(sala, beto.getId(), c2));
        juntos.unirse(sala, beto.getId());
        // Control compartido: el invitado también pone la música (y alguien de fuera no)
        assertThrows(NoSuchElementException.class, () -> juntos.control(sala, ana.getId(), "pause", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> juntos.control(sala, beto.getId(), "borrar", Map.of()));
        juntos.control(sala, beto.getId(), "poner", Map.of("cancionId", c1, "segundo", 42));
        var v = juntos.ver(sala, beto.getId());
        assertEquals(c1, ((CancionDTO) v.get("cancion")).getId());
        assertTrue(((Number) v.get("segundo")).doubleValue() >= 42);
        // Cola: cualquiera de la sala suma; al empezar a sonar sale de la cola
        juntos.sumarACola(sala, beto.getId(), c2);
        assertEquals(1, ((List<?>) juntos.ver(sala, carla.getId()).get("cola")).size());
        juntos.estado(sala, carla.getId(), c2, 0, true);
        assertEquals(0, ((List<?>) juntos.ver(sala, carla.getId()).get("cola")).size());
        // Pausa y posición desde cualquier participante; la versión sube con cada cambio
        long v0 = ((Number) juntos.ver(sala, beto.getId()).get("version")).longValue();
        juntos.control(sala, beto.getId(), "pause", Map.of());
        assertEquals(false, juntos.ver(sala, carla.getId()).get("sonando"));
        juntos.control(sala, carla.getId(), "seek", Map.of("segundo", 30));
        assertEquals(30.0, ((Number) juntos.ver(sala, beto.getId()).get("segundo")).doubleValue(), 0.001);
        assertTrue(((Number) juntos.ver(sala, beto.getId()).get("version")).longValue() > v0);
        // «Siguiente» desde la canción que sonaba: si dos lo tocan a la vez, se avanza una sola vez
        juntos.sumarACola(sala, beto.getId(), c1);
        juntos.control(sala, beto.getId(), "next", Map.of("desde", c2));
        juntos.control(sala, carla.getId(), "next", Map.of("desde", c2));
        assertEquals(c1, ((CancionDTO) juntos.ver(sala, beto.getId()).get("cancion")).getId());
        // «Anterior» vuelve a la canción de antes
        juntos.control(sala, carla.getId(), "prev", Map.of());
        assertEquals(c2, ((CancionDTO) juntos.ver(sala, beto.getId()).get("cancion")).getId());
        assertThrows(IllegalArgumentException.class, () -> juntos.reaccionar(sala, beto.getId(), "no-es-emoji"));
        juntos.reaccionar(sala, beto.getId(), "🔥");
        // El anfitrión cierra: la sala deja de existir
        juntos.salir(sala, carla.getId());
        assertThrows(NoSuchElementException.class, () -> juntos.ver(sala, beto.getId()));
    }

    @Test
    void audioLigeroSoloParaArchivosPesados() {
        // 3 min a 320 kbps (~7 MB): necesita versión ligera; a 128 kbps (~2,9 MB): no
        assertTrue(com.bpm.music.services.AudioLigeroService.necesita(7_200_000, 180));
        assertFalse(com.bpm.music.services.AudioLigeroService.necesita(2_900_000, 180));
    }
}
