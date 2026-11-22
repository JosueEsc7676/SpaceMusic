package com.bpm.music.services;

import com.bpm.music.models.*;
import com.bpm.music.repositories.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;

@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:eficiencia;DB_CLOSE_DELAY=-1;MODE=MySQL", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
class EficienciaConsultasTest {
    @Autowired PlaylistRepository playlists;
    @Autowired PlaylistColaboradorRepository colaboradores;
    @Autowired UsuarioGuardadoRepository guardados;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsuarioReproduccionRepository escuchas;
    @Autowired CancionRepository canciones;
    @Test void busquedaPaginadaNoExponePlaylistsPrivadasYGuardadasRespetanVisibilidad() {
        var privada = playlists.save(Playlist.builder().nombre("Mi lista").usuarioId(2L).publica(false).cancionesCount(1).build());
        var publica = playlists.save(Playlist.builder().nombre("Mi lista pública").usuarioId(2L).publica(true).cancionesCount(1).build());
        assertEquals(1, playlists.buscarVisibles(1L,"lista",PageRequest.of(0,20)).size());
        assertEquals(1, playlists.publicasPagina(1L,PageRequest.of(0,1)).size());
        assertTrue(playlists.publicasPagina(1L,PageRequest.of(1,1)).isEmpty());
        guardados.save(UsuarioGuardado.builder().usuarioId(1L).tipo("PLAYLIST").clave(privada.getId().toString()).build());
        guardados.save(UsuarioGuardado.builder().usuarioId(1L).tipo("PLAYLIST").clave(publica.getId().toString()).build());
        assertEquals(publica.getId(), playlists.guardadas(1L).getFirst().getId());
        colaboradores.save(new PlaylistColaborador(privada.getId(), 1L));
        assertEquals(2, playlists.buscarVisibles(1L,"lista",PageRequest.of(0,20)).size());
    }
    @Test void estadisticasAgrupanEventosSinCargarTodasLasFechas() {
        var u = usuarios.save(Usuario.builder().username("prueba").email("prueba@example.test").password("solo-prueba").estadoCuenta(Usuario.EstadoCuenta.APROBADA).build());
        var c = canciones.save(Cancion.builder().nombre("Prueba").artista("Prueba").rutaArchivo("/prueba.mp3").build());
        LocalDateTime fecha = LocalDateTime.of(2026,11,21,12,0);
        for(int i=0;i<100;i++) escuchas.save(UsuarioReproduccion.builder().usuario(u).cancion(c).fecha(fecha).build());
        var horas = escuchas.porHora(u.getId(), fecha.minusDays(1));
        assertEquals(1,horas.size()); assertEquals(100,((Number)horas.getFirst()[1]).intValue());
        assertEquals(1,escuchas.diasActivos(u.getId(),fecha.minusDays(1)).size());
        assertEquals(1,usuarios.buscarVisibles("prue",PageRequest.of(0,8)).size());
    }
}
