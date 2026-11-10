package com.bpm.music.soporte;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DiagnosticoRepository extends JpaRepository<DiagnosticoEjecucion, Long> {

    Page<DiagnosticoEjecucion> findByTipoInOrderByInicioDesc(java.util.Collection<String> tipos, Pageable pageable);

    Optional<DiagnosticoEjecucion> findFirstByTipoOrderByInicioDesc(String tipo);

    long deleteByInicioBefore(LocalDateTime antes);
}
