package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Controlar otro dispositivo de la misma cuenta (como «Spotify Connect»): cada app abierta (web, Android, iPhone)
 * informa qué suena y desde aquí se le mandan órdenes (reproducir, pausar, siguiente, adelantar, volumen,
 * poner canciones o pasar la música a ese dispositivo).
 *
 * Solo entre dispositivos de la MISMA cuenta: el usuario sale del token, nunca del cuerpo de la petición.
 * Las órdenes viajan por la conexión en vivo (evento «control-orden») y cada dispositivo ejecuta solo las suyas.
 * Todo vive en memoria: un dispositivo que deja de informar durante 75 s desaparece de la lista.
 */
@Service
public class ControlRemotoService {

    static final long VIGENCIA_MS = 75_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    static final Set<String> ACCIONES = Set.of("play", "pause", "toggle", "next", "prev", "seek", "volumen", "reproducir", "transferir");

    /** Estado de un dispositivo, tal como lo informa la app. */
    static final class Dispositivo {
        final String id;
        volatile String nombre, plataforma;
        volatile Long cancionId;
        volatile double segundo, duracion, volumen = 1;
        volatile boolean sonando;
        volatile long visto = System.currentTimeMillis(), marca = System.currentTimeMillis();
        Dispositivo(String id) { this.id = id; }
        double segundoAhora() {
            double s = sonando ? segundo + (System.currentTimeMillis() - marca) / 1000.0 : segundo;
            return duracion > 0 ? Math.min(s, duracion) : s;
        }
    }

    private final Map<Long, Map<String, Dispositivo>> cuentas = new ConcurrentHashMap<>();
    private final EventosService eventos;
    private final BibliotecaService biblioteca;

    public ControlRemotoService(EventosService eventos, BibliotecaService biblioteca) {
        this.eventos = eventos;
        this.biblioteca = biblioteca;
    }

    static String validarId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Dispositivo no válido");
        return id;
    }

    private static String texto(Object o, String def, int max) {
        String s = o == null ? "" : String.valueOf(o).replaceAll("\\p{Cntrl}", " ").trim();
        if (s.isEmpty()) return def;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static double numero(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? def : Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return def; }
    }

    /** La app informa su estado (al abrir, al cambiar algo y cada ~30 s). */
    public void presencia(long uid, Map<String, Object> b) {
        String id = validarId(Objects.toString(b.get("dispositivo"), null));
        Map<String, Dispositivo> mios = cuentas.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
        if (!mios.containsKey(id) && mios.size() >= 12) limpiar(uid, mios, true);
        if (!mios.containsKey(id) && mios.size() >= 12) throw new IllegalStateException("Demasiados dispositivos abiertos con esta cuenta");
        Dispositivo d = mios.computeIfAbsent(id, Dispositivo::new);
        Long antesCancion = d.cancionId;
        boolean antesSonando = d.sonando;
        String antesNombre = d.nombre;
        double antesVolumen = d.volumen;
        boolean saltoTiempo = Math.abs(d.segundoAhora() - numero(b.get("segundo"), 0)) > 3;

        d.nombre = texto(b.get("nombre"), "Dispositivo", 60);
        d.plataforma = texto(b.get("plataforma"), "web", 20);
        Object c = b.get("cancionId");
        Long cancion = null;
        if (c instanceof Number n) cancion = n.longValue();
        else if (c != null && !String.valueOf(c).isBlank()) { try { cancion = Long.valueOf(String.valueOf(c)); } catch (NumberFormatException ignored) { } }
        d.cancionId = cancion != null && biblioteca.porId(cancion).isPresent() ? cancion : null;
        d.segundo = Math.max(0, numero(b.get("segundo"), 0));
        d.duracion = Math.max(0, numero(b.get("duracion"), 0));
        d.volumen = Math.max(0, Math.min(1, numero(b.get("volumen"), 1)));
        d.sonando = Boolean.TRUE.equals(b.get("sonando")) && d.cancionId != null;
        d.marca = d.visto = System.currentTimeMillis();

        boolean cambio = antesNombre == null || !Objects.equals(antesCancion, d.cancionId) || antesSonando != d.sonando
                || Math.abs(antesVolumen - d.volumen) > 0.02 || saltoTiempo;
        if (cambio) avisar(uid);
    }

    /** Se cerró la app o la sesión: deja de aparecer al instante. */
    public void salir(long uid, String dispositivo) {
        Map<String, Dispositivo> mios = cuentas.get(uid);
        if (mios != null && mios.remove(validarId(dispositivo)) != null) avisar(uid);
    }

    /** Dispositivos de la cuenta con su estado. */
    public List<Map<String, Object>> lista(long uid, String yo) {
        Map<String, Dispositivo> mios = cuentas.getOrDefault(uid, Map.of());
        long limite = System.currentTimeMillis() - VIGENCIA_MS;
        List<Map<String, Object>> r = new ArrayList<>();
        for (Dispositivo d : mios.values()) {
            if (d.visto < limite) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id);
            m.put("nombre", d.nombre);
            m.put("plataforma", d.plataforma);
            m.put("esEste", d.id.equals(yo));
            CancionDTO cancion = d.cancionId == null ? null : biblioteca.porId(d.cancionId).orElse(null);
            m.put("cancion", cancion);
            m.put("segundo", Math.round(d.segundoAhora() * 10) / 10.0);
            m.put("duracion", d.duracion);
            m.put("sonando", d.sonando);
            m.put("volumen", d.volumen);
            m.put("visto", d.visto);
            r.add(m);
        }
        // Primero el que está sonando, luego el más reciente
        r.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("sonando"))
                .thenComparing(m -> -((Number) m.get("visto")).longValue()));
        return r;
    }

    /**
     * Manda una orden a otro dispositivo de la misma cuenta.
     * reproducir: valor = {cancionIds:[...], indice, segundo}; transferir: el destino sigue la canción de origen.
     */
    public void orden(long uid, String destino, String origen, String accion, Map<String, Object> datos) {
        validarId(destino);
        if (!ACCIONES.contains(accion)) throw new IllegalArgumentException("Orden no válida");
        Dispositivo d = Optional.ofNullable(cuentas.get(uid)).map(m -> m.get(destino))
                .filter(x -> x.visto >= System.currentTimeMillis() - VIGENCIA_MS)
                .orElseThrow(() -> new NoSuchElementException("Ese dispositivo ya no está conectado"));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("para", d.id);
        o.put("de", origen == null ? null : validarId(origen));
        o.put("accion", accion);
        switch (accion) {
            case "seek" -> o.put("segundo", Math.max(0, numero(datos.get("segundo"), 0)));
            case "volumen" -> o.put("volumen", Math.max(0, Math.min(1, numero(datos.get("volumen"), 1))));
            case "reproducir", "transferir" -> {
                List<Long> ids = new ArrayList<>();
                if (datos.get("cancionIds") instanceof List<?> l) {
                    for (Object x : l) {
                        if (ids.size() >= 200) break;
                        try { long id = Long.parseLong(String.valueOf(x)); if (biblioteca.porId(id).isPresent()) ids.add(id); } catch (NumberFormatException ignored) { }
                    }
                }
                if (ids.isEmpty()) throw new IllegalArgumentException("No hay canciones para poner");
                int indice = (int) Math.max(0, Math.min(ids.size() - 1, numero(datos.get("indice"), 0)));
                o.put("canciones", ids.stream().map(id -> biblioteca.porId(id).orElse(null)).filter(Objects::nonNull).toList());
                o.put("indice", indice);
                o.put("segundo", Math.max(0, numero(datos.get("segundo"), 0)));
            }
            default -> { }
        }
        eventos.enviar(uid, "control-orden", o);
    }

    private void avisar(long uid) {
        eventos.enviar(uid, "control-dispositivos", Map.of("dispositivos", lista(uid, "")));
    }

    private void limpiar(long uid, Map<String, Dispositivo> mios, boolean avisarCambio) {
        long limite = System.currentTimeMillis() - VIGENCIA_MS;
        if (mios.values().removeIf(d -> d.visto < limite) && avisarCambio) avisar(uid);
    }

    /** Cada 30 s: quita los dispositivos que dejaron de informar y avisa a los demás. */
    @Scheduled(fixedDelay = 30_000)
    public void limpiarTodos() {
        cuentas.forEach((uid, mios) -> limpiar(uid, mios, true));
        cuentas.values().removeIf(Map::isEmpty);
    }
}
