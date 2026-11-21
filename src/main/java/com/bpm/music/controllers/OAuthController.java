package com.bpm.music.controllers;

import com.bpm.music.services.OAuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/** Entrar con Google, Facebook o Apple (ver {@link OAuthService}). */
@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthController {

    private final OAuthService oauth;

    public OAuthController(OAuthService oauth) { this.oauth = oauth; }

    /** Qué botones mostrar (solo los configurados en este servidor). */
    @GetMapping("/proveedores")
    public Map<String, Boolean> proveedores() { return oauth.proveedores(); }

    @GetMapping("/{p}/inicio")
    public ResponseEntity<Void> inicio(@PathVariable String p, @RequestParam(defaultValue = "web") String app) {
        return ir(oauth.urlInicio(p, app));
    }

    /** Vuelta de Google y Facebook. */
    @GetMapping("/{p}/vuelta")
    public ResponseEntity<Void> vuelta(@PathVariable String p, @RequestParam(required = false) String code,
                                       @RequestParam(required = false) String state, @RequestParam(required = false) String error) {
        return ir(oauth.completar(p, code, state, null, error));
    }

    /** Vuelta de Apple (llega como formulario). */
    @PostMapping("/{p}/vuelta")
    public ResponseEntity<Void> vueltaFormulario(@PathVariable String p, @RequestParam(required = false) String code,
                                                 @RequestParam(required = false) String state, @RequestParam(required = false) String user,
                                                 @RequestParam(required = false) String error) {
        return ir(oauth.completar(p, code, state, user, error));
    }

    private static ResponseEntity<Void> ir(String url) {
        HttpHeaders h = new HttpHeaders();
        h.setLocation(URI.create(url));
        h.setCacheControl("no-store");
        return new ResponseEntity<>(h, HttpStatus.SEE_OTHER);
    }
}
