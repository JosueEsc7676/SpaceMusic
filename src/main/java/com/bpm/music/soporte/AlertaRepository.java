package com.bpm.music.soporte;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlertaRepository extends JpaRepository<Alerta, Long> {

    Optional<Alerta> findFirstByClaveAndEstadoIn(String clave, Collection<Alerta.Estado> estados);

    List<Alerta> findByEstadoIn(Collection<Alerta.Estado> estados);

    Page<Alerta> findByEstadoInOrderByActualizadaDesc(Collection<Alerta.Estado> estados, Pageable pageable);

    long countByEstado(Alerta.Estado estado);

    long countByCreadaAfter(LocalDateTime fecha);

    long deleteByEstadoAndResueltaBefore(Alerta.Estado estado, LocalDateTime antes);
}
