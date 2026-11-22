package com.bpm.music.services;

import com.bpm.music.repositories.CancionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EficienciaTest {
    @TempDir Path carpeta;
    @Test void rangosReutilizanRutaEInvalidacionNoResucitaArchivoBorrado() throws Exception {
        var repo = mock(CancionRepository.class);
        var biblioteca = mock(BibliotecaService.class);
        var escaner = mock(EscanerService.class);
        Path pista = Files.write(carpeta.resolve("pista.mp3"), new byte[]{1,2,3});
        when(repo.findRutaById(1L)).thenReturn(Optional.of(pista.toString()));
        when(escaner.resolverArchivo(pista.toString())).thenReturn(pista.toFile());
        var s = new CancionService(repo, biblioteca, escaner, mock(ImagenService.class));
        for (int i = 0; i < 100; i++) assertEquals(pista.toFile(), s.archivo(1L));
        verify(repo, times(1)).findRutaById(1L);
        when(biblioteca.revisionArchivos()).thenReturn(1L);
        when(repo.findRutaById(1L)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> s.archivo(1L));
        verify(repo, times(2)).findRutaById(1L);
    }
    @Test void veinteSolicitudesConcurrentesCalculanUnaVez() throws Exception {
        var cache = new CacheTemporal<String, Integer>(60000, 100);
        var contador = new AtomicInteger();
        var puerta = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pendientes = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 20; i++) pendientes.add(pool.submit(() -> { puerta.await(); return cache.obtener("misma", () -> { contador.incrementAndGet(); return 7; }); }));
            puerta.countDown();
            for (var f : pendientes) assertEquals(7, f.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, contador.get());
    }
    @Test void veintePlaylistsUsanCuatroConsultasEnLugarDeUnaPorCadaDato() {
        var repo = mock(com.bpm.music.repositories.PlaylistRepository.class);
        var usuarios = mock(com.bpm.music.repositories.UsuarioRepository.class);
        var cols = mock(com.bpm.music.repositories.PlaylistColaboradorRepository.class);
        var biblioteca = mock(BibliotecaService.class);
        var lista = new ArrayList<com.bpm.music.models.Playlist>();
        var filas = new ArrayList<Object[]>();
        for (long i=1; i<=20; i++) {
            lista.add(com.bpm.music.models.Playlist.builder().id(i).usuarioId(2L).nombre("Lista " + i).publica(true).build());
            filas.add(new Object[]{i,1L});
        }
        var cancion = new com.bpm.music.dtos.CancionDTO(); cancion.setId(1L); cancion.setDuracionSegundos(120);
        when(biblioteca.porId(1L)).thenReturn(Optional.of(cancion));
        when(repo.findPublicasDeOtros(1L)).thenReturn(lista);
        when(repo.cancionesPorPlaylists(anyCollection())).thenReturn(filas);
        var servicio = new PlaylistService(repo,usuarios,biblioteca,mock(ImagenService.class),mock(NotificacionesService.class));
        servicio.setColaboracion(cols,mock(com.bpm.music.repositories.SeguimientoRepository.class));
        var resultado = servicio.publicas(1L);
        assertEquals(20,resultado.size()); assertTrue(resultado.stream().allMatch(p -> p.getCancionesCount()==1 && !p.isEditable()));
        verify(repo).findPublicasDeOtros(1L); verify(repo).cancionesPorPlaylists(anyCollection());
        verify(usuarios).findAllById(anyIterable()); verify(cols).findByPlaylistIdIn(anyCollection());
        verifyNoMoreInteractions(repo,usuarios,cols);
    }
    @Test void invalidarDuranteCalculoNoGuardaSuResultadoViejo() throws Exception {
        var cache = new CacheTemporal<String, String>(60000, 1);
        var dentro = new CountDownLatch(1); var continuar = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var f = pool.submit(() -> cache.obtener("a", () -> { dentro.countDown(); try { continuar.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } return "viejo"; }));
            assertTrue(dentro.await(5, TimeUnit.SECONDS)); cache.borrar("a"); continuar.countDown(); f.get(5, TimeUnit.SECONDS);
            assertNull(cache.leer("a"));
        }
        cache.poner("a", "uno"); cache.poner("a", "dos"); assertEquals("dos", cache.leer("a"));
    }
    @Test void refrescoDeBibliotecaMantieneLecturasDisponibles() throws Exception {
        var repo = mock(CancionRepository.class);
        var c = new com.bpm.music.dtos.CancionDTO();
        c.setId(1L); c.setNombre("Prueba"); c.setArtista("Prueba"); c.setAlbum("Álbum"); c.setGenero("Rock");
        when(repo.findAllLigero()).thenReturn(List.of(c));
        var b = new BibliotecaService(repo); var anterior = b.todas();
        var dentro = new CountDownLatch(1); var continuar = new CountDownLatch(1); var terminado = new CountDownLatch(1);
        when(repo.findAllLigero()).thenAnswer(inv -> { dentro.countDown(); continuar.await(); terminado.countDown(); return List.of(); });
        b.refrescarEnSegundoPlano(); assertTrue(dentro.await(5, TimeUnit.SECONDS));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            assertSame(anterior, pool.submit(b::todas).get(1, TimeUnit.SECONDS));
        } finally { continuar.countDown(); }
        assertTrue(terminado.await(5, TimeUnit.SECONDS));
        for(int i=0; i<100 && !b.todas().isEmpty(); i++) Thread.sleep(10);
        assertTrue(b.todas().isEmpty());
    }
}
