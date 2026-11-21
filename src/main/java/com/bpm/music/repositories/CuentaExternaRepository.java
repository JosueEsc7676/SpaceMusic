package com.bpm.music.repositories;

import com.bpm.music.models.CuentaExterna;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CuentaExternaRepository extends JpaRepository<CuentaExterna, Long> {
    Optional<CuentaExterna> findByProveedorAndSujeto(String proveedor, String sujeto);
}
