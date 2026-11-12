package com.bpm.music.organizador;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Anota las correcciones manuales (editar canción, letra o portada). Así el completado automático
 * sabe que ese campo lo decidió una persona y no lo vuelve a tocar.
 */
@Component
public class RegistroMetadatos {

    private final CambioMetadatoRepository repo;

    public RegistroMetadatos(CambioMetadatoRepository repo) {
        this.repo = repo;
    }

    public void manual(Long cancionId, String campo, String anterior, String nuevo, String usuario) {
        CambioMetadato c = new CambioMetadato();
        c.setCancionId(cancionId);
        c.setCampo(campo);
        c.setValorAnterior(recortar(anterior));
        c.setValorNuevo(recortar(nuevo));
        c.setFuente("manual");
        c.setMetodo("Corrección manual");
        c.setEstado(CambioMetadato.Estado.APLICADO);
        c.setResueltoEn(LocalDateTime.now());
        c.setResueltoPor(usuario);
        repo.save(c);
    }

    /** Un dato completado automáticamente fuera del organizador (p. ej. la letra al abrirla): queda revisable y reversible. */
    public void automatico(Long cancionId, String campo, String nuevo, String fuente, String metodo) {
        CambioMetadato c = new CambioMetadato();
        c.setCancionId(cancionId);
        c.setCampo(campo);
        c.setValorAnterior("");
        c.setValorNuevo(recortar(nuevo));
        c.setFuente(fuente);
        c.setMetodo(metodo);
        c.setEstado(CambioMetadato.Estado.APLICADO);
        c.setResueltoEn(LocalDateTime.now());
        c.setResueltoPor("sistema");
        repo.save(c);
    }

    public boolean esManual(Long cancionId, String campo) {
        return repo.existsByCancionIdAndCampoAndFuente(cancionId, campo, "manual");
    }

    private static String recortar(String s) {
        return s == null ? null : s.length() > 200_000 ? s.substring(0, 200_000) : s;
    }
}
