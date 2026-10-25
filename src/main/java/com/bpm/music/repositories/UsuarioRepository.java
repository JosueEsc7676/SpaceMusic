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

    List<Usuario> findBySolicitudColaborador(Usuario.EstadoSolicitud estado);
}
