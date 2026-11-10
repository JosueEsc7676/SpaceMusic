package com.bpm.music.soporte;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** El centro de soporte solo responde a administradores y valida sus parámetros. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:soportetest;DB_CLOSE_DELAY=-1",
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
class SoporteSeguridadTest {

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired JwtUtil jwt;

    String admin, normal;

    @BeforeEach
    void cuentas() {
        admin = token("pruebaadmin", Usuario.Rol.ADMIN);
        normal = token("pruebauser", Usuario.Rol.USER);
    }

    private String token(String nombre, Usuario.Rol rol) {
        Usuario u = usuarios.findByUsernameIgnoreCase(nombre).orElseGet(() -> {
            Usuario n = new Usuario();
            n.setUsername(nombre);
            n.setEmail(nombre + "@ejemplo.test");
            n.setPassword("x");
            n.setRol(rol);
            n.setActivo(true);
            return usuarios.save(n);
        });
        com.bpm.music.security.JwtFilter.olvidar(u.getId()); // la caché de cuentas es compartida entre contextos de prueba
        return "Bearer " + jwt.generateToken(u.getId(), u.getUsername(), rol.name());
    }

    @Test
    void sinSesionNoEntra() throws Exception {
        mvc.perform(get("/api/admin/soporte/resumen")).andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioNormalNoEntra() throws Exception {
        for (String ruta : new String[]{"/api/admin/soporte/resumen", "/api/admin/soporte/incidencias",
                "/api/admin/soporte/auditoria", "/api/admin/soporte/exportar", "/api/admin/soporte/config"})
            mvc.perform(get(ruta).header("Authorization", normal)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/soporte/respaldos").header("Authorization", normal)).andExpect(status().isForbidden());
    }

    @Test
    void administradorVeElResumen() throws Exception {
        mvc.perform(get("/api/admin/soporte/resumen").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void incidenciasPaginadas() throws Exception {
        mvc.perform(get("/api/admin/soporte/incidencias?pagina=0&tam=5").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido").isArray())
                .andExpect(jsonPath("$.tam").value(5));
    }

    @Test
    void parametrosInvalidosSeRechazan() throws Exception {
        mvc.perform(get("/api/admin/soporte/incidencias?estado=INVENTADO").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/soporte/informes/csv?tipo=otro&dias=7").header("Authorization", admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cambiosQuedanEnLaAuditoria() throws Exception {
        mvc.perform(post("/api/admin/soporte/mantenimiento/cache?cual=generos").header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/soporte/auditoria").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cache")));
    }

    @Autowired RespaldoService respaldos;

    @Test
    void copiaDeSeguridadSeCrea() throws Exception {
        mvc.perform(post("/api/admin/soporte/respaldos").header("Authorization", admin)).andExpect(status().isOk());
        for (int i = 0; i < 50 && (respaldos.estado().get("ultimo") == null || Boolean.TRUE.equals(respaldos.estado().get("enCurso"))); i++) Thread.sleep(200);
        @SuppressWarnings("unchecked")
        var ultimo = (java.util.Map<String, Object>) respaldos.estado().get("ultimo");
        org.junit.jupiter.api.Assertions.assertEquals("OK", ultimo.get("resultado"), String.valueOf(ultimo.get("error")));
    }
}
