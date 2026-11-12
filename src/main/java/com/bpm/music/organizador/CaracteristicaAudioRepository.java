package com.bpm.music.organizador;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CaracteristicaAudioRepository extends JpaRepository<CaracteristicaAudio, Long> {
    long countByEnergiaIsNotNull();
}
