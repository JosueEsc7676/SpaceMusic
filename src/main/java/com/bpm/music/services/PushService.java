package com.bpm.music.services;

import com.bpm.music.models.PushDestino;
import com.bpm.music.repositories.PushDestinoRepository;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Avisos al instante en Android sin servicios de Google (UnifiedPush: la app ntfy u otra «distribuidora»).
 * El teléfono registra su dirección; cuando llega un mensaje o una notificación se le manda un aviso vacío
 * («nuevo») y la app pide el resumen a este servidor. El contenido nunca pasa por el servicio de push.
 */
@Service
public class PushService {

    private static final int MAX_POR_USUARIO = 5;

    private final PushDestinoRepository repo;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "push");
        t.setDaemon(true);
        return t;
    });

    public PushService(PushDestinoRepository repo) { this.repo = repo; }

    /** Registra (o mueve a esta cuenta) la dirección de un teléfono. */
    public Map<String, Object> registrar(Long uid, String endpoint, String dispositivo) {
        String e = validar(endpoint);
        PushDestino d = repo.findByEndpoint(e).orElseGet(() -> PushDestino.builder().endpoint(e).build());
        d.setUsuarioId(uid);
        d.setDispositivo(dispositivo == null ? null : dispositivo.substring(0, Math.min(80, dispositivo.length())));
        repo.save(d);
        // Como mucho 5 teléfonos por cuenta: se quitan los más viejos
        List<PushDestino> todos = repo.findByUsuarioId(uid);
        if (todos.size() > MAX_POR_USUARIO) {
            todos.stream().sorted((a, b) -> a.getCreado().compareTo(b.getCreado()))
                    .limit(todos.size() - MAX_POR_USUARIO).forEach(repo::delete);
        }
        return Map.of("ok", true);
    }

    public void quitar(Long uid, String endpoint) {
        repo.findByEndpoint(endpoint == null ? "" : endpoint.trim())
                .filter(d -> d.getUsuarioId().equals(uid)).ifPresent(repo::delete);
    }

    /** Despierta los teléfonos de la persona (en segundo plano; si falla no afecta a nada). */
    public void avisar(Long uid) {
        if (uid == null) return;
        List<PushDestino> destinos;
        try { destinos = repo.findByUsuarioId(uid); } catch (RuntimeException e) { return; }
        if (destinos.isEmpty()) return;
        for (PushDestino d : destinos) {
            hilo.execute(() -> {
                try {
                    validar(d.getEndpoint()); // la dirección pudo cambiar de destino desde que se guardó
                    HttpRequest r = HttpRequest.newBuilder(URI.create(d.getEndpoint())).timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("nuevo")).build();
                    int estado = http.send(r, HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (estado == 404 || estado == 410) repo.deleteByEndpoint(d.getEndpoint());
                } catch (Exception ignored) { /* sin red o servicio caído: el teléfono igual revisa cada 15 min */ }
            });
        }
    }

    /** Solo https a un servidor público (nunca a la red local ni a este equipo). */
    static String validar(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim();
        if (e.length() > 600) throw new IllegalArgumentException("Dirección demasiado larga");
        URI u;
        try { u = URI.create(e); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Dirección no válida"); }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null)
            throw new IllegalArgumentException("La dirección debe ser https");
        try {
            for (InetAddress a : InetAddress.getAllByName(u.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || a.getHostAddress().startsWith("100.") || a.getHostAddress().startsWith("fc")
                        || a.getHostAddress().startsWith("fd"))
                    throw new IllegalArgumentException("La dirección debe ser pública");
            }
        } catch (java.net.UnknownHostException ex) {
            throw new IllegalArgumentException("No se encontró el servidor de avisos");
        }
        return e;
    }
}
