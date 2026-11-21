package com.bpm.music.services;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Iniciar sesión en la TV sin escribir la contraseña con el control:
 * <ol>
 *   <li>La TV pide un vínculo: recibe un código corto (para escribirlo o leerlo en el QR) y un secreto (solo ella lo sabe).</li>
 *   <li>Desde un teléfono o computadora con la sesión abierta se aprueba: escaneando el QR, escribiendo el código
 *       o tocando la TV que aparece «en tu misma red wifi» (misma dirección pública que la TV).</li>
 *   <li>La TV pregunta con su secreto hasta que la aprueban y recibe su propia sesión (una sola vez).</li>
 * </ol>
 * Los vínculos viven 10 minutos en memoria; nunca se guarda la contraseña ni se reutiliza la sesión del teléfono.
 */
@Service
public class TvVinculoService {

    static final long VIDA_MS = 10 * 60 * 1000L;
    private static final String LETRAS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sin 0/O ni 1/I/L
    private static final int MAX_POR_IP = 12;

    private final AuthService auth;
    private final UsuarioRepository usuarios;
    private final SecureRandom azar = new SecureRandom();
    private final Map<String, Vinculo> porCodigo = new ConcurrentHashMap<>();
    private final Map<String, Vinculo> porSecreto = new ConcurrentHashMap<>();

    static final class Vinculo {
        final String codigo, secreto, nombre, ip;
        final long creado;
        volatile Map<String, Object> sesion;
        volatile String aprobadoPor;
        volatile boolean entregado;

        Vinculo(String codigo, String secreto, String nombre, String ip, long creado) {
            this.codigo = codigo; this.secreto = secreto; this.nombre = nombre; this.ip = ip; this.creado = creado;
        }

        boolean vencido(long ahora) { return ahora - creado > VIDA_MS; }
    }

    public TvVinculoService(AuthService auth, UsuarioRepository usuarios) {
        this.auth = auth;
        this.usuarios = usuarios;
    }

    /** La TV pide un vínculo nuevo. */
    public Map<String, Object> crear(String nombre, String ip) {
        limpiar();
        long deEstaIp = porCodigo.values().stream().filter(v -> Objects.equals(v.ip, ip)).count();
        if (deEstaIp >= MAX_POR_IP) throw new IllegalStateException("Demasiados intentos. Espera unos minutos.");
        String codigo;
        do { codigo = aleatorio(6); } while (porCodigo.containsKey(codigo));
        String secreto = aleatorio(40);
        String n = nombre == null || nombre.isBlank() ? "TV" : nombre.trim();
        if (n.length() > 40) n = n.substring(0, 40);
        Vinculo v = new Vinculo(codigo, secreto, n, ip, System.currentTimeMillis());
        porCodigo.put(codigo, v);
        porSecreto.put(secreto, v);
        return Map.of("codigo", codigo, "secreto", secreto, "expira", v.creado + VIDA_MS, "nombre", n);
    }

    /** La TV pregunta si ya la aprobaron. La sesión se entrega una sola vez. */
    public Map<String, Object> estado(String secreto) {
        Vinculo v = secreto == null ? null : porSecreto.get(secreto);
        if (v == null || v.vencido(System.currentTimeMillis())) {
            if (v != null) quitar(v);
            return Map.of("estado", "vencido");
        }
        if (v.sesion == null) return Map.of("estado", "esperando");
        if (v.entregado) return Map.of("estado", "vencido");
        v.entregado = true;
        quitar(v);
        Map<String, Object> r = new LinkedHashMap<>(v.sesion);
        r.put("estado", "aprobado");
        r.put("aprobadoPor", v.aprobadoPor);
        return r;
    }

    /** Datos de una TV por su código (para la pantalla de confirmar en el teléfono). */
    public Map<String, Object> ver(String codigo) {
        Vinculo v = buscar(codigo);
        return Map.of("codigo", v.codigo, "nombre", v.nombre);
    }

    /** Una persona con la sesión abierta aprueba la TV: la TV entra con la cuenta de esa persona. */
    public Map<String, Object> aprobar(Long usuarioId, String codigo) {
        Vinculo v = buscar(codigo);
        if (v.sesion != null) throw new IllegalStateException("Esta TV ya inició sesión");
        Usuario u = usuarios.findById(usuarioId).orElseThrow(() -> new NoSuchElementException("Usuario no encontrado"));
        Map<String, Object> s = auth.sesionPara(u);
        v.aprobadoPor = u.getNombreVisible();
        v.sesion = Map.of("token", s.get("token"), "usuario", s.get("usuario"));
        return Map.of("ok", true, "nombre", v.nombre);
    }

    /** TVs esperando en la misma red (misma dirección vista por el servidor) que quien pregunta. */
    public List<Map<String, Object>> cercanas(String ip) {
        limpiar();
        if (ip == null) return List.of();
        List<Map<String, Object>> r = new ArrayList<>();
        for (Vinculo v : porCodigo.values()) {
            if (v.sesion == null && mismaRed(ip, v.ip)) r.add(Map.of("codigo", v.codigo, "nombre", v.nombre));
        }
        return r;
    }

    /** Misma dirección, o la misma red local (/24) si las dos son privadas. */
    static boolean mismaRed(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (local(a) && local(b)) return true;
        if (privada(a) && privada(b) && a.contains(".") && b.contains(".")) {
            return a.substring(0, a.lastIndexOf('.')).equals(b.substring(0, b.lastIndexOf('.')));
        }
        return false;
    }

    /** El mismo equipo (127.0.0.1 o ::1). */
    private static boolean local(String ip) {
        return ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean privada(String ip) {
        return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    private Vinculo buscar(String codigo) {
        String c = codigo == null ? "" : codigo.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        Vinculo v = porCodigo.get(c);
        if (v == null || v.vencido(System.currentTimeMillis())) {
            if (v != null) quitar(v);
            throw new NoSuchElementException("Ese código no existe o ya venció. Mira el código nuevo en la TV.");
        }
        return v;
    }

    private void quitar(Vinculo v) {
        porCodigo.remove(v.codigo);
        porSecreto.remove(v.secreto);
    }

    private void limpiar() {
        long ahora = System.currentTimeMillis();
        porCodigo.values().removeIf(v -> {
            boolean fuera = v.vencido(ahora);
            if (fuera) porSecreto.remove(v.secreto);
            return fuera;
        });
    }

    private String aleatorio(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(LETRAS.charAt(azar.nextInt(LETRAS.length())));
        return sb.toString();
    }
}
