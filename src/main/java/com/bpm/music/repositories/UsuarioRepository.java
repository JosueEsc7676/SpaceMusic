package com.bpm.music.repositories;

import com.bpm.music.models.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmailIgnoreCase(String email);
    Optional<Usuario> findByUsernameIgnoreCase(String username);
    Optional<Usuario> findByResetToken(String token);
    Optional<Usuario> findByVerificationToken(String token);

    boolean existsByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByRol(Usuario.Rol rol);

    long countByRol(Usuario.Rol rol);
    @org.springframework.data.jpa.repository.Query("SELECT u FROM Usuario u WHERE u.activo = true AND (u.estadoCuenta IS NULL OR u.estadoCuenta = :estado) AND (LOCATE(LOWER(:q), LOWER(u.username)) > 0 OR LOCATE(LOWER(:q), LOWER(u.nombreDisplay)) > 0) ORDER BY u.username")
    List<Usuario> buscarConEstado(@org.springframework.data.repository.query.Param("q") String q, @org.springframework.data.repository.query.Param("estado") Usuario.EstadoCuenta estado, org.springframework.data.domain.Pageable pagina);
    default List<Usuario> buscarVisibles(String q, org.springframework.data.domain.Pageable pagina) { return buscarConEstado(q, Usuario.EstadoCuenta.APROBADA, pagina); }

    List<Usuario> findBySolicitudColaborador(Usuario.EstadoSolicitud estado);
    List<Usuario> findByEstadoCuentaOrderByFechaRegistroDesc(Usuario.EstadoCuenta estado);
    List<Usuario> findByRolAndActivoTrue(Usuario.Rol rol);
    Optional<Usuario> findByAprobacionToken(String token);
}
