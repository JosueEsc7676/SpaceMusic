package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modo fiesta: la TV (o cualquier dispositivo) abre una fiesta y muestra un QR; los invitados, sin cuenta,
 * proponen canciones del servidor y votan. La TV pone a sonar la más votada cuando termina la actual.
 * Vive en memoria: se cierra sola tras 6 horas sin actividad.
 */
@Service
public class FiestaService {

    static final long INACTIVA_MS = 6 * 3600 * 1000L;
    private static final int MAX_PROPUESTAS = 100;
    private static final int MAX_POR_INVITADO = 3;
    private static final String LETRAS = "abcdefghijkmnpqrstuvwxyz23456789";

    private final BibliotecaService biblioteca;
    private final UsuarioRepository usuarios;
    private final SecureRandom azar = new SecureRandom();
    private final Map<String, Fiesta> porToken = new ConcurrentHashMap<>();
    private final Map<Long, Fiesta> porAnfitrion = new ConcurrentHashMap<>();
    /** Límite de acciones por invitado y minuto (contra abusos del enlace público). */
    private final Map<String, int[]> acciones = new ConcurrentHashMap<>();

    static final class Propuesta {
        final long id;
        final long cancionId;
        final String de;
        final String nombre;
        final Set<String> votos = ConcurrentHashMap.newKeySet();
        final long creada = System.currentTimeMillis();
        Propuesta(long id, long cancionId, String de, String nombre) { this.id = id; this.cancionId = cancionId; this.de = de; this.nombre = nombre; }
    }

    static final class Fiesta {
        final String token;
        final long anfitrion;
        final String nombreAnfitrion;
        final List<Propuesta> propuestas = Collections.synchronizedList(new ArrayList<>());
        volatile Long sonando;
        volatile long actividad = System.currentTimeMillis();
        long siguienteId = 1;
        Fiesta(String token, long anfitrion, String nombreAnfitrion) { this.token = token; this.anfitrion = anfitrion; this.nombreAnfitrion = nombreAnfitrion; }
    }

    public FiestaService(BibliotecaService biblioteca, UsuarioRepository usuarios) {
        this.biblioteca = biblioteca;
        this.usuarios = usuarios;
    }

    // ═══════════════ Anfitrión (con sesión) ═══════════════

    public Map<String, Object> abrir(Long uid) {
        Fiesta f = porAnfitrion.get(uid);
        if (f == null) {
            String nombre = usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("Tu anfitrión");
            String token;
            do { token = aleatorio(10); } while (porToken.containsKey(token));
            f = new Fiesta(token, uid, nombre);
            porToken.put(token, f);
            porAnfitrion.put(uid, f);
        }
        f.actividad = System.currentTimeMillis();
        return estado(f, null);
    }

    public Map<String, Object> mia(Long uid) {
        Fiesta f = porAnfitrion.get(uid);
        if (f == null) throw new NoSuchElementException("No tienes una fiesta abierta");
        return estado(f, null);
    }

    public void cerrar(Long uid) {
        Fiesta f = porAnfitrion.remove(uid);
        if (f != null) porToken.remove(f.token);
    }

    /** La TV toma la propuesta (la más votada) para ponerla a continuación: sale de la lista. */
    public Map<String, Object> tomar(Long uid, long propuestaId) {
        Fiesta f = porAnfitrion.get(uid);
        if (f == null) throw new NoSuchElementException("No tienes una fiesta abierta");
        Propuesta p;
        synchronized (f.propuestas) {
            p = f.propuestas.stream().filter(x -> x.id == propuestaId).findFirst().orElseThrow(() -> new NoSuchElementException("Esa canción ya no está en la lista"));
            f.propuestas.remove(p);
        }
        f.actividad = System.currentTimeMillis();
        CancionDTO c = biblioteca.porId(p.cancionId).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        return Map.of("cancion", c, "propuestaPor", p.nombre);
    }

    /** Lo que suena en la TV (los invitados lo ven arriba). */
    public void sonando(Long uid, Long cancionId) {
        Fiesta f = porAnfitrion.get(uid);
        if (f != null) { f.sonando = cancionId; f.actividad = System.currentTimeMillis(); }
    }

    // ═══════════════ Invitados (sin cuenta, con el enlace del QR) ═══════════════

    public Map<String, Object> verPublica(String token, String invitado) {
        return estado(buscar(token), invitado);
    }

    public List<CancionDTO> buscarCanciones(String token, String q, String ip) {
        buscar(token);
        limitar(ip);
        String t = q == null ? "" : q.trim();
        if (t.length() < 2) return List.of();
        return biblioteca.buscarCanciones(t.length() > 80 ? t.substring(0, 80) : t, 15);
    }

    public Map<String, Object> proponer(String token, long cancionId, String invitado, String nombre, String ip) {
        Fiesta f = buscar(token);
        String quien = invitado(invitado);
        limitar(ip + "|" + quien);
        biblioteca.porId(cancionId).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        String n = nombre == null || nombre.isBlank() ? "Invitado" : nombre.trim();
        if (n.length() > 24) n = n.substring(0, 24);
        synchronized (f.propuestas) {
            Optional<Propuesta> ya = f.propuestas.stream().filter(p -> p.cancionId == cancionId).findFirst();
            if (ya.isPresent()) {
                ya.get().votos.add(quien); // ya la propusieron: cuenta como voto
            } else {
                if (f.propuestas.size() >= MAX_PROPUESTAS) throw new IllegalStateException("La lista de la fiesta está llena");
                long mias = f.propuestas.stream().filter(p -> p.de.equals(quien)).count();
                if (mias >= MAX_POR_INVITADO) throw new IllegalStateException("Ya tienes " + MAX_POR_INVITADO + " canciones propuestas: espera a que suene alguna");
                Propuesta p = new Propuesta(f.siguienteId++, cancionId, quien, n);
                p.votos.add(quien);
                f.propuestas.add(p);
            }
        }
        f.actividad = System.currentTimeMillis();
        return estado(f, quien);
    }

    public Map<String, Object> votar(String token, long propuestaId, String invitado, String ip) {
        Fiesta f = buscar(token);
        String quien = invitado(invitado);
        limitar(ip + "|" + quien);
        Propuesta p = f.propuestas.stream().filter(x -> x.id == propuestaId).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Esa canción ya sonó o salió de la lista"));
        if (!p.votos.remove(quien)) p.votos.add(quien);
        f.actividad = System.currentTimeMillis();
        return estado(f, quien);
    }

    // ═══════════════ Comunes ═══════════════

    private Map<String, Object> estado(Fiesta f, String invitado) {
        List<Propuesta> lista;
        synchronized (f.propuestas) { lista = new ArrayList<>(f.propuestas); }
        // Más votos primero; a igualdad, la más antigua
        lista.sort(Comparator.<Propuesta>comparingInt(p -> -p.votos.size()).thenComparingLong(p -> p.creada));
        List<Map<String, Object>> props = new ArrayList<>();
        for (Propuesta p : lista) {
            biblioteca.porId(p.cancionId).ifPresent(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id);
                m.put("cancion", c);
                m.put("votos", p.votos.size());
                m.put("de", p.nombre);
                if (invitado != null) m.put("voto", p.votos.contains(invitado));
                props.add(m);
            });
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("token", f.token);
        r.put("anfitrion", f.nombreAnfitrion);
        r.put("propuestas", props);
        if (f.sonando != null) biblioteca.porId(f.sonando).ifPresent(c -> r.put("sonando", c));
        return r;
    }

    private Fiesta buscar(String token) {
        Fiesta f = token == null ? null : porToken.get(token.trim().toLowerCase(Locale.ROOT));
        if (f == null || System.currentTimeMillis() - f.actividad > INACTIVA_MS) throw new NoSuchElementException("Esta fiesta ya terminó");
        return f;
    }

    private static String invitado(String id) {
        String s = id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", "");
        if (s.length() < 8 || s.length() > 64) throw new IllegalArgumentException("Invitado no válido");
        return s;
    }

    /** Como mucho 40 acciones por minuto por invitado (y por IP). */
    private void limitar(String clave) {
        long minuto = System.currentTimeMillis() / 60000;
        int[] c = acciones.compute(clave, (k, v) -> v == null || v[0] != (int) minuto ? new int[]{(int) minuto, 0} : v);
        if (++c[1] > 40) throw new IllegalStateException("Demasiadas acciones: espera un minuto");
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void limpiar() {
        long ahora = System.currentTimeMillis();
        porToken.values().removeIf(f -> {
            boolean fuera = ahora - f.actividad > INACTIVA_MS;
            if (fuera) porAnfitrion.remove(f.anfitrion, f);
            return fuera;
        });
        long minuto = ahora / 60000;
        acciones.values().removeIf(v -> v[0] < minuto - 1);
    }

    private String aleatorio(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(LETRAS.charAt(azar.nextInt(LETRAS.length())));
        return sb.toString();
    }
}
