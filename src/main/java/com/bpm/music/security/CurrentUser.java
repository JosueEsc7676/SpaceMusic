package com.bpm.music.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Acceso al usuario autenticado de la petición actual. */
public final class CurrentUser {

    private CurrentUser() {}

    /** ID del usuario logueado; lanza 401 si no hay sesión. */
    public static Long id() {
        Long id = idOrNull();
        if (id == null) throw new ResponseStatusException(UNAUTHORIZED, "Debes iniciar sesión");
        return id;
    }

    public static Long idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long id) return id;
        return null;
    }

    public static boolean tieneRol(String rol) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + rol));
    }

    public static boolean esAdmin() {
        return tieneRol("ADMIN");
    }

    public static boolean puedeEditar() {
        return tieneRol("ADMIN") || tieneRol("COLABORADOR");
    }
}
