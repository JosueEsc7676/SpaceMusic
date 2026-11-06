package com.bpm.music.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Eventos en tiempo real (Server-Sent Events): mensajes nuevos, "visto" y notificaciones
 * llegan al instante a la app abierta, sin recargar. Cada dispositivo abre una conexión.
 */
@Service
public class EventosService {

    private final Map<Long, List<SseEmitter>> conexiones = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public SseEmitter conectar(Long uid) {
        SseEmitter e = new SseEmitter(0L); // sin límite: se mantiene abierta con latidos
        List<SseEmitter> lista = conexiones.computeIfAbsent(uid, k -> new CopyOnWriteArrayList<>());
        lista.add(e);
        Runnable quitar = () -> lista.remove(e);
        e.onCompletion(quitar);
        e.onTimeout(quitar);
        e.onError(x -> quitar.run());
        try { e.send(SseEmitter.event().name("hola").data("{}")); } catch (Exception ex) { quitar.run(); }
        return e;
    }

    /** Envía un evento a todos los dispositivos abiertos de un usuario. */
    public void enviar(Long uid, String nombre, Object datos) {
        List<SseEmitter> lista = conexiones.get(uid);
        if (lista == null || lista.isEmpty()) return;
        String texto;
        try { texto = json.writeValueAsString(datos); } catch (Exception ex) { return; }
        for (SseEmitter e : lista) {
            try { e.send(SseEmitter.event().name(nombre).data(texto)); } catch (Exception ex) { lista.remove(e); }
        }
    }

    /** Latido cada 25 s: mantiene viva la conexión a través de proxys (Tailscale) y detecta las cerradas. */
    @Scheduled(fixedRate = 25_000)
    public void latido() {
        conexiones.forEach((uid, lista) -> {
            for (SseEmitter e : lista) {
                try { e.send(SseEmitter.event().comment("latido")); } catch (Exception ex) { lista.remove(e); }
            }
        });
    }
}
