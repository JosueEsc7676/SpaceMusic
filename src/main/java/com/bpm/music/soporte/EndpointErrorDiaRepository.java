package com.bpm.music.soporte;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EndpointErrorDiaRepository extends JpaRepository<EndpointErrorDia, Long> {

    Optional<EndpointErrorDia> findByDiaAndEndpointAndEstadoHttp(LocalDate dia, String endpoint, int estadoHttp);

    /** [endpoint, estado, cuenta] de un período, de más a menos errores. */
    @Query("SELECT e.endpoint, e.estadoHttp, SUM(e.cuenta) FROM EndpointErrorDia e WHERE e.dia >= :desde AND e.dia <= :hasta "
            + "GROUP BY e.endpoint, e.estadoHttp ORDER BY SUM(e.cuenta) DESC")
    List<Object[]> masErrores(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta);

    long deleteByDiaBefore(LocalDate dia);
}
