package com.bpm.music.repositories;

import com.bpm.music.models.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);
    Optional<Usuario> findByUsername(String username);
    Optional<Usuario> findByResetToken(String token);
    Optional<Usuario> findByVerificationToken(String token);

    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

    // Para el panel admin: solicitudes pendientes
    List<Usuario> findBySolicitudColaborador(Usuario.EstadoSolicitud estado);
}
