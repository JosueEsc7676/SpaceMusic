package com.bpm.music.soporte;

import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Auditoría de acciones administrativas. Solo inserta: no hay forma de editar ni borrar registros desde la app. */
@Service
public class AuditoriaService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

    private final AuditoriaRepository repo;
    private final UsuarioRepository usuarios;
    private final Map<Long, String> nombres = new ConcurrentHashMap<>();

    public AuditoriaService(AuditoriaRepository repo, UsuarioRepository usuarios) {
        this.repo = repo;
        this.usuarios = usuarios;
    }

    public void registrar(String accion, String detalle, String resultado) {
        try {
            AuditoriaAdmin a = new AuditoriaAdmin();
            Long uid = null;
            try { uid = CurrentUser.id(); } catch (Exception ignored) { }
            a.setUsuarioId(uid);
            a.setUsuario(uid == null ? "sistema" : nombres.computeIfAbsent(uid,
                    id -> usuarios.findById(id).map(u -> u.getUsername()).orElse("#" + id)));
            a.setAccion(Sanitizador.limpiar(accion, 80));
            a.setDetalle(Sanitizador.limpiar(detalle, 600));
            a.setResultado(resultado);
            HttpServletRequest req = peticion();
            if (req != null) a.setIp(Sanitizador.ipParcial(req.getRemoteAddr()));
            repo.save(a);
        } catch (Exception e) {
            // La auditoría nunca debe romper la acción que se auditó
            log.debug("No se pudo guardar la auditoría: {}", e.getMessage());
        }
    }

    public Page<AuditoriaAdmin> buscar(String q, String resultado, LocalDateTime desde, int pagina, int tam) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return repo.buscar(like, resultado == null || resultado.isBlank() ? null : resultado, desde,
                PageRequest.of(Math.max(0, pagina), Math.min(Math.max(tam, 1), 100), Sort.by(Sort.Direction.DESC, "fecha")));
    }

    private static HttpServletRequest peticion() {
        var a = RequestContextHolder.getRequestAttributes();
        return a instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }
}
