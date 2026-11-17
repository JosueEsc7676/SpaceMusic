package com.bpm.music.security;

import com.bpm.music.models.Seguimiento;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.services.MensajesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Los mensajes se guardan cifrados: la base de datos no muestra el texto y la app lo lee igual que antes. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cifradotest;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=clave-solo-para-pruebas-automaticas-de-spacemusic",
        "mensajes.clave-archivo=${java.io.tmpdir}/sm-test-clave-mensajes.key",
        "music.path=${java.io.tmpdir}/sm-test-music", "music.playlist-images-path=${java.io.tmpdir}/sm-test-uploads",
        "soporte.respaldos.ruta=${java.io.tmpdir}/sm-test-respaldos", "music.scan-interval-minutes=100000",
        "app.require-email-verification=false", "ytmusic.python=/no/existe", "ytmusic.script=/no/existe", "organizador.ffmpeg=/no/existe"
})
class CifradoMensajesTest {

    @Autowired CifradoMensajes cifrado;
    @Autowired MensajesService mensajes;
    @Autowired UsuarioRepository usuarios;
    @Autowired SeguimientoRepository seguimientos;
    @Autowired JdbcTemplate jdbc;

    private Usuario u(String n) {
        return usuarios.findByUsernameIgnoreCase(n).orElseGet(() -> {
            Usuario x = new Usuario(); x.setUsername(n); x.setEmail(n + "@ejemplo.test"); x.setPassword("x"); x.setRol(Usuario.Rol.USER); x.setActivo(true);
            return usuarios.save(x);
        });
    }

    private void sigue(Usuario a, Usuario b) {
        Seguimiento s = new Seguimiento(); s.setSeguidorId(a.getId()); s.setSeguidoId(b.getId()); seguimientos.save(s);
    }

    @Test
    void cifraConNonceDistintoDetectaAlteracionesYLeeLosAntiguos() {
        String texto = "Hola, ¿escuchamos «Verano eterno» a las 8? 🎶";
        String a = cifrado.cifrar(texto), b = cifrado.cifrar(texto);
        assertTrue(a.startsWith("enc:v1:"));
        assertNotEquals(a, b, "cada mensaje usa un nonce distinto");
        assertFalse(a.contains("Hola"));
        assertEquals(texto, cifrado.descifrar(a));
        assertEquals(a, cifrado.cifrar(a), "lo ya cifrado no se vuelve a cifrar");
        // Alterar un carácter del dato guardado se detecta (no devuelve un texto falso)
        char[] x = a.toCharArray();
        x[x.length - 5] = x[x.length - 5] == 'A' ? 'B' : 'A';
        assertEquals("[Mensaje no disponible]", cifrado.descifrar(new String(x)));
        // Los mensajes anteriores al cifrado (sin prefijo) se leen tal cual
        assertEquals("mensaje viejo", cifrado.descifrar("mensaje viejo"));
    }

    @Test
    void enLaBaseDeDatosNoSeLeeElTextoPeroLaAppSi() {
        Usuario ana = u("ana_cifra"), beto = u("beto_cifra");
        sigue(ana, beto); sigue(beto, ana);
        mensajes.enviar(ana.getId(), "beto_cifra", "Mi secreto: la playlist sorpresa", null);
        String guardado = jdbc.queryForObject("SELECT texto FROM mensaje WHERE de_id = ? ORDER BY id DESC LIMIT 1", String.class, ana.getId());
        assertTrue(guardado.startsWith("enc:v1:"), "en la base de datos queda cifrado");
        assertFalse(guardado.contains("secreto"));
        @SuppressWarnings("unchecked")
        var lista = (java.util.List<Map<String, Object>>) mensajes.conversacion(beto.getId(), "ana_cifra", null).get("mensajes");
        assertTrue(lista.stream().anyMatch(m -> "Mi secreto: la playlist sorpresa".equals(m.get("texto"))), "la app lo recibe descifrado");
    }

    @Test
    void laClaveSeCreaUnaVezYSeReutiliza(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("clave.key");
        CifradoMensajes uno = new CifradoMensajes("", archivo.toString());
        String guardado = uno.cifrar("persiste");
        assertTrue(Files.isRegularFile(archivo));
        CifradoMensajes otro = new CifradoMensajes("", archivo.toString());
        assertEquals("persiste", otro.descifrar(guardado), "tras reiniciar se usa la misma clave");
        new CifradoMensajes("", System.getProperty("java.io.tmpdir") + "/sm-test-clave-mensajes.key"); // deja la instancia de las demás pruebas
    }
}
