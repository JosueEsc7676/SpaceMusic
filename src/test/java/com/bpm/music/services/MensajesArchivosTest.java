package com.bpm.music.services;

import com.bpm.music.models.Seguimiento;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.MensajeRepository;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/** Mensajes con fotos y audios cifrados, eliminar mensajes y bloquear. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mensajesarchivos;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=clave-solo-para-pruebas-automaticas-de-spacemusic",
        "music.path=${java.io.tmpdir}/sm-test-music", "music.playlist-images-path=${java.io.tmpdir}/sm-test-uploads",
        "soporte.respaldos.ruta=${java.io.tmpdir}/sm-test-respaldos", "music.scan-interval-minutes=100000",
        "app.require-email-verification=false", "ytmusic.python=/no/existe", "ytmusic.script=/no/existe", "organizador.ffmpeg=/no/existe",
        "mensajes.clave-archivo=${java.io.tmpdir}/sm-test-clave-mensajes.key"
})
class MensajesArchivosTest {

    @Autowired MensajesService mensajes;
    @Autowired UsuarioRepository usuarios;
    @Autowired SeguimientoRepository seguimientos;
    @Autowired MensajeRepository repo;
    @Autowired ImagenService imagenes;

    Usuario ana, beto, caro;

    private Usuario u(String n) {
        return usuarios.findByUsernameIgnoreCase(n).orElseGet(() -> {
            Usuario x = new Usuario(); x.setUsername(n); x.setEmail(n + "@ejemplo.test"); x.setPassword("x");
            x.setRol(Usuario.Rol.USER); x.setActivo(true);
            return usuarios.save(x);
        });
    }

    private void sigue(Usuario a, Usuario b) {
        if (seguimientos.siguiendo(a.getId()).contains(b.getId())) return;
        Seguimiento s = new Seguimiento(); s.setSeguidorId(a.getId()); s.setSeguidoId(b.getId()); seguimientos.save(s);
    }

    @BeforeEach
    void preparar() {
        ana = u("ana_msg"); beto = u("beto_msg"); caro = u("caro_msg");
        sigue(ana, beto); sigue(beto, ana);
        mensajes.bloquear(ana.getId(), "beto_msg", false);
        mensajes.bloquear(beto.getId(), "ana_msg", false);
    }

    private static byte[] png() {
        byte[] d = new byte[64];
        byte[] cab = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(cab, 0, d, 0, cab.length);
        return d;
    }

    @Test
    void fotoCifradaSoloParaLosDos() throws Exception {
        Map<String, Object> m = mensajes.enviarArchivo(ana.getId(), "beto_msg", png(), null, null);
        Long id = ((Number) m.get("id")).longValue();
        assertEquals("IMAGEN", m.get("tipo"));
        assertEquals("/api/mensajes/adjunto/" + id, m.get("adjuntoUrl"));
        // En disco está cifrado (no empieza con la firma PNG)
        String archivo = repo.findById(id).orElseThrow().getAdjunto();
        byte[] guardado = Files.readAllBytes(imagenes.carpeta("adjuntos").resolve(archivo));
        assertFalse(new String(guardado, 0, Math.min(8, guardado.length)).contains("PNG"));
        // Los dos lo abren descifrado; otra persona no
        assertArrayEquals(png(), mensajes.archivo(beto.getId(), id).getValue());
        assertEquals("image/png", mensajes.archivo(ana.getId(), id).getKey());
        assertThrows(NoSuchElementException.class, () -> mensajes.archivo(caro.getId(), id));
    }

    @Test
    void soloFotosYAudiosDeVerdad() {
        byte[] texto = "esto no es una foto, es texto plano......".getBytes();
        assertThrows(IllegalArgumentException.class, () -> mensajes.enviarArchivo(ana.getId(), "beto_msg", texto, null, null));
        byte[] m4a = new byte[64];
        System.arraycopy("\0\0\0 ftypM4A ".getBytes(), 0, m4a, 0, 12);
        assertEquals("AUDIO", mensajes.enviarArchivo(ana.getId(), "beto_msg", m4a, 7, null).get("tipo"));
    }

    @Test
    void eliminarMioParaLosDosYRecibidoSoloParaMi() {
        Long mio = ((Number) mensajes.enviar(ana.getId(), "beto_msg", "hola", null).get("id")).longValue();
        Long otro = ((Number) mensajes.enviar(beto.getId(), "ana_msg", "qué tal", null).get("id")).longValue();
        assertThrows(NoSuchElementException.class, () -> mensajes.borrarMensaje(caro.getId(), mio));
        mensajes.borrarMensaje(ana.getId(), mio);
        List<?> deBeto = (List<?>) mensajes.conversacion(beto.getId(), "ana_msg", null).get("mensajes");
        Map<?, ?> visto = deBeto.stream().map(x -> (Map<?, ?>) x).filter(x -> x.get("id").equals(mio)).findFirst().orElseThrow();
        assertEquals(true, visto.get("borrado"));
        assertEquals("Mensaje eliminado", visto.get("texto"));
        // Ana quita de su conversación el que le mandó Beto; Beto lo sigue viendo
        mensajes.borrarMensaje(ana.getId(), otro);
        assertTrue(((List<?>) mensajes.conversacion(ana.getId(), "beto_msg", null).get("mensajes")).stream()
                .noneMatch(x -> ((Map<?, ?>) x).get("id").equals(otro)));
        assertTrue(((List<?>) mensajes.conversacion(beto.getId(), "ana_msg", null).get("mensajes")).stream()
                .anyMatch(x -> ((Map<?, ?>) x).get("id").equals(otro)));
    }

    @Test
    void bloquearImpideEscribirEnLosDosSentidos() {
        mensajes.bloquear(ana.getId(), "beto_msg", true);
        assertThrows(IllegalStateException.class, () -> mensajes.enviar(ana.getId(), "beto_msg", "hola", null));
        assertThrows(IllegalStateException.class, () -> mensajes.enviar(beto.getId(), "ana_msg", "hola", null));
        assertThrows(IllegalStateException.class, () -> mensajes.enviarArchivo(beto.getId(), "ana_msg", png(), null, null));
        assertEquals(false, mensajes.conversacion(beto.getId(), "ana_msg", null).get("puedeEscribir"));
        assertEquals(true, mensajes.conversacion(ana.getId(), "beto_msg", null).get("bloqueado"));
        assertEquals(1, mensajes.bloqueados(ana.getId()).size());
        mensajes.bloquear(ana.getId(), "beto_msg", false);
        assertNotNull(mensajes.enviar(beto.getId(), "ana_msg", "ya", null).get("id"));
    }
}
