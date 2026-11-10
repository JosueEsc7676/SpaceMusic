package com.bpm.music.comunidad;

import com.bpm.music.models.Seguimiento;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Playlists colaborativas y reportes: permisos y validaciones. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:comunidadtest;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=clave-solo-para-pruebas-automaticas-de-spacemusic",
        "music.path=${java.io.tmpdir}/sm-test-music",
        "music.playlist-images-path=${java.io.tmpdir}/sm-test-uploads",
        "soporte.respaldos.ruta=${java.io.tmpdir}/sm-test-respaldos",
        "music.scan-interval-minutes=100000",
        "app.require-email-verification=false"
})
@AutoConfigureMockMvc
class ComunidadTest {

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired SeguimientoRepository seguimientos;
    @Autowired JwtUtil jwt;
    final ObjectMapper json = new ObjectMapper();

    private Usuario cuenta(String nombre, Usuario.Rol rol) {
        return usuarios.findByUsernameIgnoreCase(nombre).orElseGet(() -> {
            Usuario n = new Usuario();
            n.setUsername(nombre);
            n.setEmail(nombre + "@ejemplo.test");
            n.setPassword("x");
            n.setRol(rol);
            n.setActivo(true);
            return usuarios.save(n);
        });
    }

    private String token(Usuario u) {
        com.bpm.music.security.JwtFilter.olvidar(u.getId()); // la caché de cuentas es compartida entre contextos de prueba
        return "Bearer " + jwt.generateToken(u.getId(), u.getUsername(), u.getRol().name());
    }

    @Test
    void playlistColaborativa() throws Exception {
        Usuario dueno = cuenta("duenopl", Usuario.Rol.USER);
        Usuario amigo = cuenta("amigopl", Usuario.Rol.USER);
        Usuario extrano = cuenta("extranopl", Usuario.Rol.USER);
        Seguimiento s = new Seguimiento();
        s.setSeguidorId(dueno.getId());
        s.setSeguidoId(amigo.getId());
        seguimientos.save(s);

        String r = mvc.perform(post("/api/playlists").header("Authorization", token(dueno))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"Juntos\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(r).get("id").asLong();

        // Un desconocido no la ve (es privada) ni puede invitar
        mvc.perform(get("/api/playlists/" + id).header("Authorization", token(extrano))).andExpect(status().isNotFound());
        // Solo se invita a amigos
        mvc.perform(post("/api/playlists/" + id + "/colaboradores").header("Authorization", token(dueno))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"extranopl\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/playlists/" + id + "/colaboradores").header("Authorization", token(dueno))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"@amigopl\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.colaboradores[0].username").value("amigopl"));
        // El invitado la ve, puede editar canciones, no cambiarle el nombre
        mvc.perform(get("/api/playlists/" + id).header("Authorization", token(amigo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.editable").value(true)).andExpect(jsonPath("$.propia").value(false));
        mvc.perform(put("/api/playlists/" + id).header("Authorization", token(amigo))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"Mía\"}"))
                .andExpect(status().isNotFound());
        JsonNode lista = json.readTree(mvc.perform(get("/api/playlists").header("Authorization", token(amigo)))
                .andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(lista.findValuesAsText("nombre").contains("Juntos"));
        // Un tercero no puede sacar al invitado; el invitado sí puede salir
        mvc.perform(delete("/api/playlists/" + id + "/colaboradores/" + amigo.getId()).header("Authorization", token(extrano)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/playlists/" + id + "/colaboradores/" + amigo.getId()).header("Authorization", token(amigo)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/playlists/" + id).header("Authorization", token(amigo))).andExpect(status().isNotFound());
    }

    @Test
    void reportesValidanYSonDeAdmin() throws Exception {
        Usuario u = cuenta("reportauser", Usuario.Rol.USER);
        mvc.perform(post("/api/reportes").header("Authorization", token(u))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cancionId\":999999,\"motivo\":\"audio\"}"))
                .andExpect(status().isNotFound());
        // Pedir canciones se quitó
        mvc.perform(post("/api/solicitudes").header("Authorization", token(u))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titulo\":\"Algo\"}"))
                .andExpect(status().is4xxClientError());
        // Un usuario normal no ve el panel de reportes ni el de descargas
        mvc.perform(get("/api/admin/comunidad/reportes").header("Authorization", token(u))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/descargas").header("Authorization", token(u))).andExpect(status().isForbidden());
        Usuario admin = cuenta("admincom", Usuario.Rol.ADMIN);
        mvc.perform(get("/api/admin/comunidad/reportes?estado=INVENTADO").header("Authorization", token(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resumenSinDatosNoInventaNada() throws Exception {
        Usuario u = cuenta("resumenuser", Usuario.Rol.USER);
        mvc.perform(get("/api/resumen?anio=" + java.time.LocalDate.now().getYear()).header("Authorization", token(u)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suficiente").value(false)).andExpect(jsonPath("$.reproducciones").value(0));
        mvc.perform(get("/api/resumen?anio=1990").header("Authorization", token(u))).andExpect(status().isBadRequest());
    }
}
