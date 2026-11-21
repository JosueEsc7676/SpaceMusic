package com.bpm.music.controllers;

import com.bpm.music.config.GlobalExceptionHandler;
import com.bpm.music.services.LetrasService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SincroniaPermisosTest {
    LetrasService letras = mock(LetrasService.class);
    LyricsController protegido() {
        var proxy = new ProxyFactory(new LyricsController(letras));
        proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return (LyricsController) proxy.getProxy();
    }
    void rol(String rol) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "prueba", "", AuthorityUtils.createAuthorityList("ROLE_" + rol)));
    }
    @AfterEach void limpiar() { SecurityContextHolder.clearContext(); }
    @Test void oyenteNoPuedeGuardar() {
        rol("USER");
        assertThrows(AccessDeniedException.class, () -> protegido().sincronia(1L, new LyricsController.AjusteSincronia(0.5, "a".repeat(64))));
        verifyNoInteractions(letras);
    }
    @Test void sinAutenticacionNoPuedeGuardar() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> protegido().sincronia(1L, new LyricsController.AjusteSincronia(0.5, "a".repeat(64))));
        verifyNoInteractions(letras);
    }
    @Test void administradorYColaboradorLleganAlServicio() {
        var c = protegido();
        for (String rol : new String[]{"ADMIN", "COLABORADOR"}) {
            rol(rol); c.sincronia(1L, new LyricsController.AjusteSincronia(0.5, "a".repeat(64)));
        }
        verify(letras, times(2)).guardarSincronia(1L, 0.5, "a".repeat(64));
    }
    @Test void jsonIncorrectoEsErrorDelCliente() throws Exception {
        rol("ADMIN");
        var mvc = MockMvcBuilders.standaloneSetup(protegido()).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/letras/1/sincronia").contentType("application/json").content("{\"segundos\": \"texto\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(letras);
    }
}
