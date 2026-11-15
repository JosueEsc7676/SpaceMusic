package com.bpm.music.controllers;

import com.bpm.music.services.ControlRemotoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletContext;
import jakarta.websocket.*;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Conexión permanente de cada dispositivo para el control remoto ({@code /api/control/ws?t=TOKEN}), como el «dealer»
 * de Spotify Connect. El token se valida en {@code JwtFilter} antes del saludo (la ruta exige sesión); aquí solo se
 * toma el usuario de esa autenticación. El primer mensaje debe ser {"tipo":"hola","dispositivo":…}.
 *
 * Se registra con la API estándar de WebSocket de Tomcat (incluida en el servidor web), sin dependencias nuevas.
 */
@Component
public class ControlRemotoWs implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ControlRemotoWs.class);
    static final String RUTA = "/api/control/ws";

    private final ControlRemotoService control;
    private final ServletContext servletContext;
    private final ObjectMapper json = new ObjectMapper();

    public ControlRemotoWs(ControlRemotoService control, ServletContext servletContext) {
        this.control = control;
        this.servletContext = servletContext;
    }

    /** Registra el punto de conexión (en las pruebas sin servidor web real no hay contenedor y se omite). */
    @Override
    public void afterSingletonsInstantiated() {
        Object sc = servletContext.getAttribute("jakarta.websocket.server.ServerContainer");
        if (!(sc instanceof ServerContainer contenedor)) return;
        try {
            contenedor.addEndpoint(ServerEndpointConfig.Builder.create(Conexion.class, RUTA)
                    .configurator(new Configurador()).build());
        } catch (DeploymentException e) {
            log.warn("No se pudo registrar el WebSocket de control remoto: {}", e.getMessage());
        }
    }

    /** Toma el usuario autenticado del saludo HTTP y crea cada conexión con acceso al servicio. */
    private final class Configurador extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            if (request.getUserPrincipal() instanceof Authentication a && a.getPrincipal() instanceof Long uid) {
                sec.getUserProperties().put("uid", uid);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getEndpointInstance(Class<T> clase) {
            return (T) new Conexion(control, json);
        }
    }

    /** Una conexión (un dispositivo). */
    public static final class Conexion extends Endpoint {
        private static final int MAX_MENSAJES_POR_SEGUNDO = 40;
        private final ControlRemotoService control;
        private final ObjectMapper json;
        private Long uid;
        private volatile String dispositivo;
        private ControlRemotoService.Canal canal;
        private long ventana, enVentana;

        Conexion(ControlRemotoService control, ObjectMapper json) {
            this.control = control;
            this.json = json;
        }

        @Override
        public void onOpen(Session sesion, EndpointConfig config) {
            uid = (Long) config.getUserProperties().get("uid");
            if (uid == null) { cerrar(sesion, CloseReason.CloseCodes.VIOLATED_POLICY, "Debes iniciar sesión"); return; }
            sesion.setMaxIdleTimeout(120_000);          // la app manda «ping» cada 25 s
            sesion.setMaxTextMessageBufferSize(64 * 1024);
            canal = texto -> {
                synchronized (sesion) {
                    if (!sesion.isOpen()) return false;
                    try { sesion.getBasicRemote().sendText(texto); return true; } catch (IOException | IllegalStateException e) { return false; }
                }
            };
            sesion.addMessageHandler(String.class, texto -> recibir(sesion, texto));
        }

        private void recibir(Session sesion, String texto) {
            long ahora = System.currentTimeMillis() / 1000;
            if (ahora != ventana) { ventana = ahora; enVentana = 0; }
            if (++enVentana > MAX_MENSAJES_POR_SEGUNDO) return; // demasiados mensajes: se ignoran
            try {
                Map<String, Object> m = json.readValue(texto, new TypeReference<>() { });
                if ("hola".equals(m.get("tipo"))) {
                    String id = Objects.toString(m.get("dispositivo"), null);
                    if (dispositivo != null && !dispositivo.equals(id)) control.desconectar(uid, dispositivo, canal);
                    dispositivo = id;
                    control.conectar(uid, id, m, canal);
                } else if (dispositivo != null) {
                    control.mensaje(uid, dispositivo, m, canal);
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                canal.enviar("{\"tipo\":\"error\",\"error\":" + comillas(e.getMessage()) + "}");
            } catch (Exception e) {
                log.debug("Mensaje de control remoto no válido: {}", e.getMessage());
            }
        }

        private static String comillas(String s) {
            return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
        }

        @Override
        public void onClose(Session sesion, CloseReason motivo) {
            if (uid != null && dispositivo != null) control.desconectar(uid, dispositivo, canal);
        }

        @Override
        public void onError(Session sesion, Throwable e) {
            if (uid != null && dispositivo != null) control.desconectar(uid, dispositivo, canal);
        }

        private static void cerrar(Session s, CloseReason.CloseCodes codigo, String motivo) {
            try { s.close(new CloseReason(codigo, motivo)); } catch (IOException ignored) { }
        }
    }
}
