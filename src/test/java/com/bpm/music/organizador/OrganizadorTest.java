package com.bpm.music.organizador;

import com.bpm.music.models.Cancion;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.JwtFilter;
import com.bpm.music.security.JwtUtil;
import com.bpm.music.services.BibliotecaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Organizador y completado de datos: correcciones manuales protegidas, aplicar/revertir, una ejecución a la vez,
 * pausa/cancelación/reanudación tras reinicio, validación de la IA y permisos. Sin llamadas a internet
 * (las canciones de prueba ya tienen letra y portada; YouTube Music y ffmpeg no están instalados en la prueba).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:organizadortest;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=clave-solo-para-pruebas-automaticas-de-spacemusic",
        "music.path=${java.io.tmpdir}/sm-test-music", "music.playlist-images-path=${java.io.tmpdir}/sm-test-uploads",
        "soporte.respaldos.ruta=${java.io.tmpdir}/sm-test-respaldos", "music.scan-interval-minutes=100000",
        "app.require-email-verification=false",
        "ytmusic.python=/no/existe/python", "ytmusic.script=/no/existe/ytm.py", "organizador.ffmpeg=/no/existe/ffmpeg"
})
@AutoConfigureMockMvc
class OrganizadorTest {

    @Autowired CancionRepository canciones;
    @Autowired BibliotecaService biblioteca;
    @Autowired CompletadoService completado;
    @Autowired RegistroMetadatos registro;
    @Autowired CambioMetadatoRepository cambios;
    @Autowired OrganizadorService organizador;
    @Autowired TrabajoOrganizadorRepository trabajos;
    @Autowired RevisionCatalogoService revision;
    @Autowired UsuarioRepository usuarios;
    @Autowired JwtUtil jwt;
    @Autowired MockMvc mvc;

    private Cancion cancion(String nombre, String artista, int dur, boolean conLetra) {
        Cancion c = new Cancion();
        c.setNombre(nombre);
        c.setArtista(artista);
        c.setAlbum("Prueba");
        c.setDuracionSegundos(dur);
        c.setRutaArchivo("/no/existe/" + nombre + ".mp3");
        if (conLetra) c.setLetra("la la la");
        c.setPortadaHash("abc123"); // con portada: no se buscan portadas en internet
        c = canciones.save(c);
        biblioteca.invalidar();
        return c;
    }

    private CambioMetadato pendiente(Long id, String campo, String anterior, String nuevo) {
        CambioMetadato c = new CambioMetadato();
        c.setCancionId(id); c.setCampo(campo); c.setValorAnterior(anterior); c.setValorNuevo(nuevo);
        c.setFuente("LRCLIB"); c.setMetodo("prueba"); c.setEstado(CambioMetadato.Estado.PENDIENTE);
        return cambios.save(c);
    }

    @Test
    void aplicarYRevertirLetra() throws Exception {
        Cancion c = cancion("Sin letra uno", "Prueba", 200, false);
        CambioMetadato cm = completado.aplicar(pendiente(c.getId(), "letra", "", "[00:01.00] hola\n[00:02.00] mundo"), "prueba");
        assertEquals(CambioMetadato.Estado.APLICADO, cm.getEstado());
        assertTrue(biblioteca.porId(c.getId()).orElseThrow().isTieneLetra());
        assertTrue(canciones.findById(c.getId()).orElseThrow().getLetraSincronizada().contains("[00:01.00]"));
        completado.revertir(cm, "prueba");
        assertFalse(biblioteca.porId(c.getId()).orElseThrow().isTieneLetra());
        assertEquals(CambioMetadato.Estado.REVERTIDO, cambios.findById(cm.getId()).orElseThrow().getEstado());
    }

    @Test
    void laCorreccionManualNoSeSobrescribe() throws Exception {
        Cancion c = cancion("Manual dos", "Prueba", 200, false);
        registro.manual(c.getId(), "letra", null, "", "usuario #1");
        assertTrue(completado.candidatas("letra", 100).stream().noneMatch(x -> x.getId().equals(c.getId())), "no se vuelve a buscar");
        CambioMetadato cm = completado.aplicar(pendiente(c.getId(), "letra", "", "otra letra"), "sistema");
        assertEquals(CambioMetadato.Estado.RECHAZADO, cm.getEstado());
        assertFalse(biblioteca.porId(c.getId()).orElseThrow().isTieneLetra());
    }

    @Test
    void soloRellenaLoQueFalta() throws Exception {
        Cancion c = cancion("Con letra tres", "Prueba", 200, true);
        CambioMetadato cm = completado.aplicar(pendiente(c.getId(), "letra", "", "nueva"), "sistema");
        assertEquals(CambioMetadato.Estado.RECHAZADO, cm.getEstado());
        assertEquals("la la la", canciones.findById(c.getId()).orElseThrow().getLetra());
    }

    @Test
    void revisionProponeSinTocarNada() throws Exception {
        Cancion a = cancion("Mi Canción (Official Video)", "Artista Prueba", 180, true);
        Cancion b = cancion("Doble", "Artista Prueba", 200, true);
        Cancion b2 = cancion("Doble", "Artista Prueba", 201, true);
        Cancion vivo = cancion("Doble (En Vivo)", "Artista Prueba", 250, true);
        revision.revisar();
        var lista = cambios.findAll();
        assertTrue(lista.stream().anyMatch(x -> x.getCampo().equals("nombre") && x.getCancionId().equals(a.getId()) && x.getValorNuevo().equals("Mi Canción")));
        assertTrue(lista.stream().anyMatch(x -> x.getCampo().equals("duplicado") && x.getCancionId().equals(b2.getId())));
        assertTrue(lista.stream().noneMatch(x -> x.getCampo().equals("duplicado") && x.getCancionId().equals(vivo.getId())), "la versión en vivo no es duplicado");
        assertEquals("Mi Canción (Official Video)", canciones.findById(a.getId()).orElseThrow().getNombre(), "nada se aplica solo");
        // Aplicar y revertir el título limpio
        CambioMetadato n = lista.stream().filter(x -> x.getCampo().equals("nombre") && x.getCancionId().equals(a.getId())).findFirst().orElseThrow();
        completado.aplicar(n, "prueba");
        assertEquals("Mi Canción", canciones.findById(a.getId()).orElseThrow().getNombre());
        completado.revertir(cambios.findById(n.getId()).orElseThrow(), "prueba");
        assertEquals("Mi Canción (Official Video)", canciones.findById(a.getId()).orElseThrow().getNombre());
        // La segunda revisión no repite propuestas
        long antes = cambios.count();
        revision.revisar();
        assertEquals(antes, cambios.count());
        assertNotNull(b);
    }

    @Test
    void respuestasDeIaSeValidanEnElServidor() {
        assertEquals(Map.of("estilo", "bachata", "contexto", "romantico"), IaLocalService.validar("{\"estilo\":\"bachata\",\"contexto\":\"romantico\"}"));
        assertNull(IaLocalService.validar("{\"estilo\":\"borra todo\",\"contexto\":\"fiesta\"}"));
        assertNull(IaLocalService.validar("no es json"));
        assertNull(IaLocalService.validar("{\"estilo\":\"bachata\"}"));
        assertNull(IaLocalService.validar("[\"bachata\"]"));
    }

    @Test
    void unaEjecucionALaVezPausaReanudaYCancela() throws Exception {
        trabajos.deleteAll();
        cancion("Lista cuatro", "Prueba", 200, true);
        TrabajoOrganizador t = organizador.iniciar("manual", "prueba");
        assertThrows(IllegalStateException.class, () -> organizador.iniciar("manual", "otra"));
        // Termina solo (sin internet, sin ffmpeg ni YouTube Music en la prueba)
        for (int i = 0; i < 100 && trabajos.findById(t.getId()).orElseThrow().getEstado() == TrabajoOrganizador.Estado.EN_CURSO; i++) Thread.sleep(100);
        TrabajoOrganizador fin = trabajos.findById(t.getId()).orElseThrow();
        assertEquals(TrabajoOrganizador.Estado.TERMINADO, fin.getEstado(), fin.getMensaje());
        assertTrue(fin.getAvance().contains("revision"));
        for (int i = 0; i < 100 && organizador.estaCorriendo(); i++) Thread.sleep(100);
        // Un trabajo que quedó EN_CURSO (reinicio del servidor) se retoma desde su etapa
        TrabajoOrganizador colgado = new TrabajoOrganizador();
        colgado.setTipo("diario"); colgado.setEstado(TrabajoOrganizador.Estado.EN_CURSO); colgado.setEtapa(5); colgado.setAvance("{\"vincular\":7}");
        colgado = trabajos.save(colgado);
        organizador.retomar();
        for (int i = 0; i < 100 && trabajos.findById(colgado.getId()).orElseThrow().getEstado() == TrabajoOrganizador.Estado.EN_CURSO; i++) Thread.sleep(100);
        for (int i = 0; i < 100 && organizador.estaCorriendo(); i++) Thread.sleep(100);
        TrabajoOrganizador retomado = trabajos.findById(colgado.getId()).orElseThrow();
        assertEquals(TrabajoOrganizador.Estado.TERMINADO, retomado.getEstado());
        assertTrue(retomado.getAvance().contains("\"vincular\":7"), "conserva el avance anterior");
        // Cancelar sin trabajo: error claro
        assertThrows(IllegalArgumentException.class, organizador::cancelar);
    }

    @Test
    void proximasEjecucionesEnHoraDeElSalvador() {
        Map<String, Object> p = organizador.proximas();
        assertEquals("America/El_Salvador", p.get("zona"));
        OffsetDateTime d = OffsetDateTime.parse((String) p.get("diaria"));
        assertEquals(ZoneOffset.ofHours(-6), d.getOffset());
        assertTrue(Duration.between(OffsetDateTime.now(), d).toHours() <= 24);
        assertEquals(3, d.getHour());
    }

    @Test
    void soloAdministradores() throws Exception {
        Usuario u = usuarios.findByUsernameIgnoreCase("orguser").orElseGet(() -> {
            Usuario n = new Usuario(); n.setUsername("orguser"); n.setEmail("orguser@ejemplo.test"); n.setPassword("x"); n.setRol(Usuario.Rol.USER); n.setActivo(true);
            return usuarios.save(n);
        });
        JwtFilter.olvidar(u.getId());
        String token = "Bearer " + jwt.generateToken(u.getId(), u.getUsername(), "USER");
        for (String ruta : List.of("/api/admin/organizacion", "/api/admin/organizacion/cambios", "/api/admin/descargas/buscar?q=ab"))
            mvc.perform(get(ruta).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/organizacion/iniciar").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/organizacion")).andExpect(status().isUnauthorized());
    }
}
