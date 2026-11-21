package com.bpm.music.services;

import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {"spring.datasource.url=jdbc:h2:mem:sincronia;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SincroniaPersistenciaTest {
    @Autowired CancionRepository repo;
    @Autowired PlatformTransactionManager transacciones;
    private static final String ORIGINAL = "[00:10.00]uno\n[00:15.00]dos";
    Long cancion() {
        var c = new Cancion(); c.setNombre("Prueba de sincronía"); c.setArtista("Prueba");
        c.setRutaArchivo("/prueba.mp3"); c.setLetra("Texto manual"); c.setLetraSincronizada(ORIGINAL);
        return repo.saveAndFlush(c).getId();
    }
    LetrasService servicio() { return new LetrasService(repo, mock(BibliotecaService.class), "/no/existe"); }
    void guardar(LetrasService s, Long id, double segundos, String revision) {
        new TransactionTemplate(transacciones).execute(t -> s.guardarSincronia(id, segundos, revision));
    }
    @Test void persisteParaLecturasNuevasSinCambiarTextoManual() {
        Long id = cancion();
        guardar(servicio(), id, 1.5, SincroniaLetra.revision(ORIGINAL));
        var datos = new LetrasService(repo, mock(BibliotecaService.class), "/no/existe").obtener(id);
        assertEquals(1.5, datos.get("sincroniaGlobalSegundos"));
        assertEquals("Texto manual", datos.get("letra"));
        assertTrue(datos.get("letraSincronizada").toString().contains(ORIGINAL));
        assertThrows(IllegalStateException.class, () -> guardar(servicio(), id, 2, SincroniaLetra.revision(ORIGINAL)));
    }
    @Test void dosEditoresConcurrentesNoSePisan() throws Exception {
        Long id = cancion(); var s = servicio(); var inicio = new CountDownLatch(1);
        var cola = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> editar = () -> { inicio.await(); try {
                guardar(s, id, Thread.currentThread().getId() % 2 == 0 ? 1 : 2, SincroniaLetra.revision(ORIGINAL)); return true;
            } catch (IllegalStateException e) { return false; } };
            var a = cola.submit(editar); var b = cola.submit(editar); inicio.countDown();
            int guardados = (a.get(60, TimeUnit.SECONDS) ? 1 : 0) + (b.get(60, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, guardados);
            assertEquals("Texto manual", repo.findLetrasById(id).get(0)[0]);
        } finally { cola.shutdownNow(); }
    }
}
