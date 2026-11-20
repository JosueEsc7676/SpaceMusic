package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Controlar la reproducción entre los dispositivos de una misma cuenta, con el mismo modelo que «Spotify Connect»:
 *
 * <ul>
 *   <li><b>Estado único de la cuenta</b> («cluster»): la lista de dispositivos con su estado (canción, segundo con su
 *       marca de tiempo del servidor, pausa, volumen y lo que sigue en la cola), el <b>dispositivo activo</b> y un
 *       número de versión. Cada cambio sube la versión y se envía a todos los dispositivos.</li>
 *   <li><b>Conexión permanente</b> por dispositivo (WebSocket {@code /api/control/ws}, como el «dealer» de Spotify):
 *       por ahí llegan el cluster y las órdenes, y salen el estado y las confirmaciones.</li>
 *   <li><b>Órdenes con identificador y confirmación</b>: el dispositivo destino ejecuta la orden y responde «ack»;
 *       el que la mandó recibe la confirmación (o el error) y todos ven el cluster nuevo.</li>
 *   <li><b>Un solo dispositivo suena a la vez</b>: si uno empieza a sonar, pasa a ser el activo y el anterior se pausa.</li>
 *   <li><b>Reloj</b>: «ping»/«pong» con la hora del servidor para que cada app calcule la posición exacta.</li>
 * </ul>
 *
 * Compatibilidad: las apps anteriores siguen funcionando por HTTP ({@code POST /presencia}, {@code POST /{id}/orden})
 * y por la conexión en vivo de eventos (SSE: «control-orden», «control-dispositivos»).
 * Solo entre dispositivos de la MISMA cuenta: el usuario sale siempre del token. Todo vive en memoria.
 */
@Service
public class ControlRemotoService {

    static final long VIGENCIA_MS = 75_000;
    /** Tras cerrarse el WebSocket, el dispositivo sigue en la lista unos segundos (recargar la página no lo hace parpadear). */
    static final long GRACIA_CIERRE_MS = 15_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    static final Set<String> ACCIONES = Set.of("play", "pause", "toggle", "next", "prev", "seek", "volumen", "reproducir", "transferir", "cola");
    private static final int MAX_DISPOSITIVOS = 12, MAX_COLA = 10;

    /** Por dónde se le habla a un dispositivo conectado por WebSocket (en las pruebas, una lista en memoria). */
    public interface Canal { boolean enviar(String json); }

    /** Estado de un dispositivo, tal como lo informa la app. */
    static final class Dispositivo {
        final String id;
        volatile String nombre, plataforma;
        volatile Long cancionId;
        volatile double segundo, duracion, volumen = 1;
        volatile boolean sonando;
        /** Hora del servidor en que se fijó «segundo». */
        volatile long marca = System.currentTimeMillis(), visto = System.currentTimeMillis();
        volatile long seq = -1;
        /** Último inicio de reproducción hecho por la persona (lo informa la app); 0 = ninguno todavía. */
        volatile long inicio;
        volatile List<Long> cola = List.of();
        volatile Canal canal;
        Dispositivo(String id) { this.id = id; }
        double segundoAhora() {
            double s = sonando ? segundo + (System.currentTimeMillis() - marca) / 1000.0 : segundo;
            return duracion > 0 ? Math.min(s, duracion) : s;
        }
        boolean vigente() { return visto >= System.currentTimeMillis() - VIGENCIA_MS; }
    }

    /** Estado de la cuenta (el «cluster»). */
    static final class Cuenta {
        final Map<String, Dispositivo> dispositivos = new ConcurrentHashMap<>();
        volatile String activo;
        final AtomicLong version = new AtomicLong();
        /** Órdenes pendientes de confirmar: id → quién la mandó. */
        final Map<String, String[]> pendientes = new ConcurrentHashMap<>();
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ControlRemotoService.class);
    private final Map<Long, Cuenta> cuentas = new ConcurrentHashMap<>();
    private final EventosService eventos;
    private final BibliotecaService biblioteca;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public ControlRemotoService(EventosService eventos, BibliotecaService biblioteca) {
        this.eventos = eventos;
        this.biblioteca = biblioteca;
    }

    // ═══════════════════ Utilidades ═══════════════════

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

    private static Long idCancion(Object c) {
        if (c instanceof Number n) return n.longValue();
        if (c != null && !String.valueOf(c).isBlank()) { try { return Long.valueOf(String.valueOf(c)); } catch (NumberFormatException ignored) { } }
        return null;
    }

    private Cuenta cuenta(long uid) { return cuentas.computeIfAbsent(uid, k -> new Cuenta()); }

    private String aJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    // ═══════════════════ Estado de los dispositivos ═══════════════════

    /** Estado informado por HTTP (apps anteriores o sin WebSocket). */
    public void presencia(long uid, Map<String, Object> b) {
        actualizar(uid, validarId(Objects.toString(b.get("dispositivo"), null)), b);
    }

    private Dispositivo registrar(Cuenta c, String id) {
        Dispositivo d = c.dispositivos.get(id);
        if (d != null) return d;
        if (c.dispositivos.size() >= MAX_DISPOSITIVOS) c.dispositivos.values().removeIf(x -> !x.vigente());
        if (c.dispositivos.size() >= MAX_DISPOSITIVOS) throw new IllegalStateException("Demasiados dispositivos abiertos con esta cuenta");
        return c.dispositivos.computeIfAbsent(id, Dispositivo::new);
    }

    /** Aplica un estado: {nombre, plataforma, cancionId, segundo, duracion, sonando, volumen, cola, seq}. */
    private void actualizar(long uid, String id, Map<String, Object> b) {
        Cuenta c = cuenta(uid);
        String pausarAnterior = null;
        synchronized (c) {
            Dispositivo d = registrar(c, id);
            // Mensajes viejos que llegan tarde (red móvil): se descartan
            if (b.get("seq") instanceof Number n) {
                if (n.longValue() <= d.seq) return;
                d.seq = n.longValue();
            }
            boolean sonabaAntes = d.sonando;
            d.nombre = texto(b.get("nombre"), d.nombre == null ? "Dispositivo" : d.nombre, 60);
            d.plataforma = texto(b.get("plataforma"), d.plataforma == null ? "web" : d.plataforma, 20);
            Long cancion = idCancion(b.get("cancionId"));
            d.cancionId = cancion != null && biblioteca.porId(cancion).isPresent() ? cancion : null;
            d.segundo = Math.max(0, numero(b.get("segundo"), 0));
            d.duracion = Math.max(0, numero(b.get("duracion"), 0));
            d.volumen = Math.max(0, Math.min(1, numero(b.get("volumen"), 1)));
            d.sonando = Boolean.TRUE.equals(b.get("sonando")) && d.cancionId != null;
            List<Long> cola = new ArrayList<>();
            if (b.get("cola") instanceof List<?> l) for (Object x : l) { Long y = idCancion(x); if (y != null) cola.add(y); if (cola.size() >= MAX_COLA) break; }
            d.cola = List.copyOf(cola);
            d.marca = d.visto = System.currentTimeMillis();
            // Un solo dispositivo suena a la vez (como Spotify Connect): el que la PERSONA pone a sonar pasa a ser el activo.
            // La app informa «inicio» (hora de su último reproducir, elegir canción, anterior/siguiente u orden recibida);
            // el paso automático a la siguiente canción, los cortes al cargar y los informes atrasados no lo cambian.
            // Apps anteriores (sin «inicio»): cuenta pasar de pausa a sonando.
            boolean empezo;
            if (b.get("inicio") instanceof Number n) {
                long ini = n.longValue();
                // Se toma cuando ya suena (el primer informe tras tocar reproducir puede llegar mientras aún carga)
                empezo = d.sonando && ini > d.inicio;
                if (d.sonando) d.inicio = Math.max(d.inicio, ini);
            } else {
                empezo = d.sonando && !sonabaAntes;
            }
            if (log.isDebugEnabled()) log.debug("estado {} sonando {}->{} inicio={} empezo={} activo={}", id, sonabaAntes, d.sonando, b.get("inicio"), empezo, c.activo);
            if (empezo && !id.equals(c.activo)) {
                Dispositivo anterior = c.activo == null ? null : c.dispositivos.get(c.activo);
                if (anterior != null && anterior.sonando && anterior.vigente()) pausarAnterior = anterior.id;
                c.activo = id;
            } else if (c.activo == null && d.cancionId != null) {
                c.activo = id;
            }
            c.version.incrementAndGet();
        }
        if (pausarAnterior != null) entregar(uid, c, pausarAnterior, nuevaOrden("pause", "servidor", Map.of()), null);
        difundir(uid);
    }

    /** Se cerró la app o la sesión: deja de aparecer al instante. */
    public void salir(long uid, String dispositivo) {
        Cuenta c = cuentas.get(uid);
        if (c == null) return;
        synchronized (c) {
            if (c.dispositivos.remove(validarId(dispositivo)) == null) return;
            if (dispositivo.equals(c.activo)) c.activo = null;
            c.version.incrementAndGet();
        }
        difundir(uid);
    }

    /** Dispositivos vigentes de la cuenta con su estado. */
    public List<Map<String, Object>> lista(long uid, String yo) {
        Cuenta c = cuentas.get(uid);
        if (c == null) return List.of();
        List<Map<String, Object>> r = new ArrayList<>();
        long ahora = System.currentTimeMillis();
        for (Dispositivo d : c.dispositivos.values()) {
            if (!d.vigente()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id);
            m.put("nombre", d.nombre);
            m.put("plataforma", d.plataforma);
            m.put("esEste", d.id.equals(yo));
            m.put("activo", d.id.equals(c.activo));
            m.put("cancion", d.cancionId == null ? null : biblioteca.porId(d.cancionId).orElse(null));
            // «segundo» vale en el instante «ts» (hora del servidor): la app suma lo transcurrido si está sonando
            m.put("segundo", Math.round(d.segundoAhora() * 1000) / 1000.0);
            m.put("ts", ahora);
            m.put("duracion", d.duracion);
            m.put("sonando", d.sonando);
            m.put("volumen", d.volumen);
            m.put("cola", d.cola.stream().map(id -> biblioteca.porId(id).orElse(null)).filter(Objects::nonNull).toList());
            m.put("enVivo", d.canal != null);
            m.put("visto", d.visto);
            r.add(m);
        }
        // Primero el activo, luego los que suenan y luego el más reciente
        r.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("activo"))
                .thenComparing(m -> !(Boolean) m.get("sonando"))
                .thenComparing(m -> -((Number) m.get("visto")).longValue()));
        return r;
    }

    /** El estado completo de la cuenta («cluster»). */
    public Map<String, Object> cluster(long uid) {
        Cuenta c = cuenta(uid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "cluster");
        m.put("version", c.version.get());
        m.put("servidorMs", System.currentTimeMillis());
        Dispositivo activo = c.activo == null ? null : c.dispositivos.get(c.activo);
        m.put("activo", activo != null && activo.vigente() ? activo.id : null);
        m.put("dispositivos", lista(uid, ""));
        return m;
    }

    /** Envía el cluster a todos: por WebSocket a los conectados y por SSE a las apps anteriores. */
    private void difundir(long uid) {
        Cuenta c = cuentas.get(uid);
        if (c == null) return;
        Map<String, Object> cl = cluster(uid);
        String j = aJson(cl);
        for (Dispositivo d : c.dispositivos.values()) {
            Canal canal = d.canal;
            if (canal != null && !canal.enviar(j)) d.canal = null;
        }
        eventos.enviar(uid, "control-dispositivos", Map.of("dispositivos", cl.get("dispositivos"), "version", cl.get("version")));
    }

    // ═══════════════════ Órdenes ═══════════════════

    private Map<String, Object> nuevaOrden(String accion, String origen, Map<String, Object> datos) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("tipo", "orden");
        o.put("id", UUID.randomUUID().toString());
        o.put("de", origen);
        o.put("accion", accion);
        switch (accion) {
            case "seek" -> o.put("segundo", Math.max(0, numero(datos.get("segundo"), 0)));
            case "volumen" -> o.put("volumen", Math.max(0, Math.min(1, numero(datos.get("volumen"), 1))));
            case "reproducir", "transferir" -> {
                List<CancionDTO> canciones = new ArrayList<>();
                if (datos.get("cancionIds") instanceof List<?> l) {
                    for (Object x : l) {
                        if (canciones.size() >= 200) break;
                        Long id = idCancion(x);
                        if (id != null) biblioteca.porId(id).ifPresent(canciones::add);
                    }
                }
                if (canciones.isEmpty()) throw new IllegalArgumentException("No hay canciones para poner");
                o.put("canciones", canciones);
                o.put("indice", (int) Math.max(0, Math.min(canciones.size() - 1, numero(datos.get("indice"), 0))));
                o.put("segundo", Math.max(0, numero(datos.get("segundo"), 0)));
            }
            // «A continuación» del otro dispositivo (sin tocar la canción que suena); puede quedar vacía
            case "cola" -> {
                List<CancionDTO> canciones = new ArrayList<>();
                if (datos.get("cancionIds") instanceof List<?> l) {
                    for (Object x : l) {
                        if (canciones.size() >= 200) break;
                        Long id = idCancion(x);
                        if (id != null) biblioteca.porId(id).ifPresent(canciones::add);
                    }
                }
                o.put("canciones", canciones);
            }
            default -> { }
        }
        return o;
    }

    /**
     * Manda una orden a otro dispositivo de la cuenta (destino «activo» o vacío = el que está sonando).
     * Devuelve el identificador de la orden: la confirmación llega después («ack»).
     */
    public String orden(long uid, String destino, String origen, String accion, Map<String, Object> datos) {
        if (!ACCIONES.contains(accion)) throw new IllegalArgumentException("Orden no válida");
        Cuenta c = cuentas.get(uid);
        if (destino == null || destino.isBlank() || destino.equals("activo")) destino = c == null ? null : c.activo;
        validarId(destino);
        if (origen != null) validarId(origen);
        String para = destino;
        Dispositivo d = Optional.ofNullable(c).map(x -> x.dispositivos.get(para)).filter(Dispositivo::vigente)
                .orElseThrow(() -> new NoSuchElementException("Ese dispositivo ya no está conectado"));
        Map<String, Object> o = nuevaOrden(accion, origen, datos);
        entregar(uid, c, d.id, o, origen);
        return (String) o.get("id");
    }

    /** Entrega la orden: por WebSocket si el destino está conectado; si no, por SSE (apps anteriores). */
    private void entregar(long uid, Cuenta c, String destino, Map<String, Object> o, String origen) {
        o.put("para", destino);
        Dispositivo d = c.dispositivos.get(destino);
        String id = (String) o.get("id");
        if (d != null && d.canal != null && d.canal.enviar(aJson(o))) {
            if (origen != null) c.pendientes.put(id, new String[]{origen, String.valueOf(System.currentTimeMillis())});
            return;
        }
        Map<String, Object> sse = new LinkedHashMap<>(o);
        sse.remove("tipo");
        eventos.enviar(uid, "control-orden", sse);
        // La app anterior no confirma: se da por entregada para que quien la mandó no espere en vano
        if (origen != null) avisarConfirmacion(uid, c, origen, id, true, null, true);
    }

    /** El dispositivo destino confirma (o rechaza) una orden. */
    public void confirmar(long uid, String id, boolean ok, String error) {
        Cuenta c = cuentas.get(uid);
        if (c == null || id == null) return;
        String[] p = c.pendientes.remove(id);
        if (p != null) avisarConfirmacion(uid, c, p[0], id, ok, error, false);
    }

    private void avisarConfirmacion(long uid, Cuenta c, String origen, String id, boolean ok, String error, boolean sinConfirmar) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("tipo", "ack");
        a.put("id", id);
        a.put("ok", ok);
        if (error != null) a.put("error", texto(error, "", 120));
        if (sinConfirmar) a.put("legado", true);
        Dispositivo o = c.dispositivos.get(origen);
        if (o != null && o.canal != null && o.canal.enviar(aJson(a))) return;
        eventos.enviar(uid, "control-ack", a);
    }

    // ═══════════════════ WebSocket ═══════════════════

    /** Un dispositivo abre su conexión permanente y se presenta. */
    public void conectar(long uid, String dispositivo, Map<String, Object> hola, Canal canal) {
        validarId(dispositivo);
        Cuenta c = cuenta(uid);
        synchronized (c) { registrar(c, dispositivo).canal = canal; }
        canal.enviar(aJson(Map.of("tipo", "hola", "servidorMs", System.currentTimeMillis(), "dispositivo", dispositivo)));
        actualizar(uid, dispositivo, hola);
    }

    /** Se cerró el WebSocket: si no vuelve a conectarse en unos segundos, deja de aparecer. */
    public void desconectar(long uid, String dispositivo, Canal canal) {
        Cuenta c = cuentas.get(uid);
        Dispositivo d = c == null || dispositivo == null ? null : c.dispositivos.get(dispositivo);
        if (d == null || d.canal != canal) return;
        d.canal = null;
        d.visto = Math.min(d.visto, System.currentTimeMillis() - (VIGENCIA_MS - GRACIA_CIERRE_MS));
        // Se cortó: deja de contar como «sonando» al instante (si solo recargó, lo vuelve a informar al reconectarse)
        if (d.sonando) {
            synchronized (c) { d.segundo = d.segundoAhora(); d.sonando = false; d.marca = System.currentTimeMillis(); c.version.incrementAndGet(); }
            difundir(uid);
        }
    }

    /** Mensajes del dispositivo: estado, orden, ack y ping. */
    public void mensaje(long uid, String dispositivo, Map<String, Object> m, Canal canal) {
        switch (Objects.toString(m.get("tipo"), "")) {
            case "estado" -> actualizar(uid, dispositivo, m);
            case "orden" -> {
                try {
                    String id = orden(uid, Objects.toString(m.get("para"), null), dispositivo, Objects.toString(m.get("accion"), ""), m);
                    canal.enviar(aJson(Map.of("tipo", "orden-enviada", "id", id, "ref", Objects.toString(m.get("ref"), ""))));
                } catch (RuntimeException e) {
                    canal.enviar(aJson(Map.of("tipo", "ack", "ref", Objects.toString(m.get("ref"), ""), "ok", false,
                            "error", e.getMessage() == null ? "No se pudo" : e.getMessage())));
                }
            }
            case "ack" -> confirmar(uid, Objects.toString(m.get("id"), null), !Boolean.FALSE.equals(m.get("ok")), m.get("error") == null ? null : String.valueOf(m.get("error")));
            case "ping" -> canal.enviar(aJson(Map.of("tipo", "pong", "t", m.getOrDefault("t", 0), "servidorMs", System.currentTimeMillis())));
            case "cluster" -> canal.enviar(aJson(cluster(uid)));
            default -> { }
        }
    }

    // ═══════════════════ Limpieza ═══════════════════

    /** Cada 15 s: quita los dispositivos que dejaron de informar y las órdenes sin confirmar de hace más de 30 s. */
    @Scheduled(fixedDelay = 15_000)
    public void limpiarTodos() {
        long limite = System.currentTimeMillis() - 30_000;
        cuentas.forEach((uid, c) -> {
            boolean cambio;
            synchronized (c) {
                cambio = c.dispositivos.values().removeIf(d -> !d.vigente());
                if (c.activo != null && !c.dispositivos.containsKey(c.activo)) { c.activo = null; cambio = true; }
                if (cambio) c.version.incrementAndGet();
            }
            c.pendientes.values().removeIf(p -> Long.parseLong(p[1]) < limite);
            if (cambio) difundir(uid);
        });
        cuentas.values().removeIf(c -> c.dispositivos.isEmpty());
    }
}
