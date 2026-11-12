package com.bpm.music.organizador;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrabajoOrganizadorRepository extends JpaRepository<TrabajoOrganizador, Long> {

    Optional<TrabajoOrganizador> findFirstByEstadoInOrderByIdDesc(Collection<TrabajoOrganizador.Estado> estados);

    List<TrabajoOrganizador> findTop20ByOrderByIdDesc();

    Optional<TrabajoOrganizador> findFirstByEstadoOrderByIdDesc(TrabajoOrganizador.Estado estado);
}
