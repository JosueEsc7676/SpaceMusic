package com.bpm.music.repositories;

import com.bpm.music.models.PushDestino;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PushDestinoRepository extends JpaRepository<PushDestino, Long> {
    List<PushDestino> findByUsuarioId(Long usuarioId);

    Optional<PushDestino> findByEndpoint(String endpoint);

    @Transactional
    void deleteByEndpoint(String endpoint);
}
