package com.bpm.music.services;

import com.bpm.music.models.Cancion;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Audio por rangos: respuestas parciales rápidas, validador para reutilizar trozos y caché según la calidad. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audiotest;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=clave-solo-para-pruebas-automaticas-de-spacemusic",
        "music.path=${java.io.tmpdir}/sm-test-music", "music.playlist-images-path=${java.io.tmpdir}/sm-test-uploads",
        "music.light-path=${java.io.tmpdir}/sm-test-ligero",
        "soporte.respaldos.ruta=${java.io.tmpdir}/sm-test-respaldos", "music.scan-interval-minutes=100000",
        "app.require-email-verification=false", "app.allow-downloads=true", "ytmusic.python=/no/existe", "ytmusic.script=/no/existe", "organizador.ffmpeg=/no/existe"
})
@AutoConfigureMockMvc
class AudioStreamingTest {

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired CancionRepository canciones;
    @Autowired BibliotecaService biblioteca;
    @Autowired JwtUtil jwt;

    static final int TAMANO = 300_000;
    Long id;
    String token;

    @BeforeEach
    void preparar() throws Exception {
        Usuario u = usuarios.findByUsernameIgnoreCase("oyente_audio").orElseGet(() -> {
            Usuario n = new Usuario(); n.setUsername("oyente_audio"); n.setEmail("oyente_audio@ejemplo.test");
            n.setPassword("x"); n.setRol(Usuario.Rol.USER); n.setActivo(true);
            return usuarios.save(n);
        });
        com.bpm.music.security.JwtFilter.olvidar(u.getId());
        token = "Bearer " + jwt.generateToken(u.getId(), u.getUsername(), u.getRol().name());
        if (id == null) {
            Path f = Files.createTempDirectory("sm-audio").resolve("larga.mp3");
            byte[] datos = new byte[TAMANO];
            new Random(7).nextBytes(datos);
            Files.write(f, datos);
            Cancion c = new Cancion();
            // 300 KB en 10 s = 240 kbps: necesitaría versión ligera (no hay ffmpeg en las pruebas)
            c.setNombre("Larga"); c.setArtista("Prueba"); c.setAlbum("Single"); c.setDuracionSegundos(10); c.setRutaArchivo(f.toString());
            id = canciones.save(c).getId();
            biblioteca.invalidar();
        }
    }

    @Test
    void elPrimerTrozoLlegaComoRespuestaParcialConValidador() throws Exception {
        MvcResult r = mvc.perform(get("/api/canciones/" + id + "/audio").header("Authorization", token).header("Range", "bytes=0-65535"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-65535/" + TAMANO))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-SM-Calidad", "original"))
                .andReturn();
        assertEquals(65536, r.getResponse().getContentAsByteArray().length);
        String etag = r.getResponse().getHeader("ETag");
        assertNotNull(etag, "sin ETag el navegador no puede combinar trozos guardados");
        assertTrue(etag.startsWith("\"") && !etag.startsWith("W/"), "validador fuerte: " + etag);
        String cache = r.getResponse().getHeader("Cache-Control");
        assertTrue(cache.contains("no-cache") && cache.contains("private"), cache);
        // El resto de la canción, desde donde se quedó
        mvc.perform(get("/api/canciones/" + id + "/audio").header("Authorization", token).header("Range", "bytes=65536-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 65536-" + (TAMANO - 1) + "/" + TAMANO))
                .andExpect(header().string("ETag", etag));
        // Si no cambió, no se vuelve a enviar
        mvc.perform(get("/api/canciones/" + id + "/audio").header("Authorization", token).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        // Fuera del archivo
        mvc.perform(get("/api/canciones/" + id + "/audio").header("Authorization", token).header("Range", "bytes=" + (TAMANO + 10) + "-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    void ligeraTodaviaNoListaSeEntregaElOriginalConCachéCorta() throws Exception {
        MvcResult r = mvc.perform(get("/api/canciones/" + id + "/audio").param("calidad", "ligera").header("Authorization", token).header("Range", "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("X-SM-Calidad", "original"))
                .andReturn();
        String cache = r.getResponse().getHeader("Cache-Control");
        assertTrue(cache.contains("no-cache"), "la ligera llegará pronto: el original no se guarda 7 días: " + cache);
    }

    @Test
    void sinSesionNoSeEntregaAudio() throws Exception {
        int estado = mvc.perform(get("/api/canciones/" + id + "/audio").header("Range", "bytes=0-1023")).andReturn().getResponse().getStatus();
        assertTrue(estado == 401 || estado == 403, "estado " + estado);
    }

    @Test
    void copiaRapidaMantieneCalidadRangosYOriginalIntacto() throws Exception {
        Path original = Path.of(canciones.findRutaById(id).orElseThrow());
        byte[] antes = Files.readAllBytes(original);
        Path preparada = Path.of(System.getProperty("java.io.tmpdir"), "sm-test-ligero", "inicio",
                id + "_" + Files.size(original) + "_" + original.toFile().lastModified() + ".mp3");
        Files.createDirectories(preparada.getParent());
        Files.write(preparada, new byte[]{10, 11, 12, 13, 14});
        try {
            var r = mvc.perform(get("/api/canciones/" + id + "/audio").header("Authorization", token).header("Range", "bytes=0-1"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string("Content-Range", "bytes 0-1/5"))
                    .andExpect(header().string("X-SM-Calidad", "original"))
                    .andReturn();
            assertArrayEquals(new byte[]{10, 11}, r.getResponse().getContentAsByteArray());
            assertArrayEquals(antes, Files.readAllBytes(original));
            var continuacion = mvc.perform(get("/api/canciones/" + id + "/audio").header("Authorization", token)
                            .header("Range", "bytes=2-3")
                            .header("If-Range", com.bpm.music.controllers.CancionController.etagAudio(original.toFile(), false)))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string("Content-Range", "bytes 2-3/" + TAMANO)).andReturn();
            assertArrayEquals(new byte[]{antes[2], antes[3]}, continuacion.getResponse().getContentAsByteArray());
            // La descarga sigue entregando el archivo de biblioteca con sus etiquetas originales.
            var descarga = mvc.perform(get("/api/canciones/" + id + "/descargar").header("Authorization", token).header("Range", "bytes=0-1"))
                    .andExpect(status().isPartialContent()).andReturn();
            assertArrayEquals(new byte[]{antes[0], antes[1]}, descarga.getResponse().getContentAsByteArray());
        } finally {
            Files.deleteIfExists(preparada);
        }
    }

    @Test
    void cacheYValidadorDistinguenLaVersionLigera() throws Exception {
        Path f = Files.createTempFile("sm", ".m4a");
        assertNotEquals(com.bpm.music.controllers.CancionController.etagAudio(f.toFile(), true),
                com.bpm.music.controllers.CancionController.etagAudio(f.toFile(), false));
        assertTrue(com.bpm.music.controllers.CancionController.cacheAudio(false, false).getHeaderValue().contains("max-age=604800"));
        assertTrue(com.bpm.music.controllers.CancionController.cacheAudio(true, true).getHeaderValue().contains("max-age=604800"));
        assertTrue(com.bpm.music.controllers.CancionController.cacheAudio(true, false).getHeaderValue().contains("max-age=600"));
        Files.deleteIfExists(f);
    }
}
