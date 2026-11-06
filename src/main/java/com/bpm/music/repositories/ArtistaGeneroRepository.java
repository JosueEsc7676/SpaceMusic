package com.bpm.music.repositories;

import com.bpm.music.models.ArtistaGenero;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArtistaGeneroRepository extends JpaRepository<ArtistaGenero, Long> {
    Optional<ArtistaGenero> findByClave(String clave);
}
