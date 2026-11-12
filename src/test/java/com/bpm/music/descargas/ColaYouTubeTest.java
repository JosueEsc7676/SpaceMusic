package com.bpm.music.descargas;

import com.bpm.music.dtos.CancionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Agregar canciones de YouTube Music a la cola: validación, duplicados y peticiones simultáneas. */
class ColaYouTubeTest {

    @TempDir Path dir;
    Path datos;
    AtomicInteger escrituras = new AtomicInteger();
    DescargasService service;

    @BeforeEach
    void preparar() throws Exception {
        datos = dir.resolve("datos");
        Files.createDirectories(datos.resolve("cola"));
        Files.createDirectories(datos.resolve("cola-hechas"));
        Path script = dir.resolve("descargar");
        Files.writeString(script, "#!/bin/sh\n");
        script.toFile().setExecutable(true);
        // En vez de ejecutar el programa real, la prueba escribe la lista en la cola como lo haría él
        service = new DescargasService(script.toString(), datos.toString()) {
            @Override
            public synchronized Map<String, Object> encolar(String texto) {
                try {
                    Thread.sleep(30); // como el programa real, tarda un poco
                    Files.writeString(datos.resolve("cola").resolve("20261112-1200" + String.format("%02d", escrituras.get()) + "-" + escrituras.incrementAndGet() + ".txt"), texto + "\n");
                } catch (Exception e) { throw new RuntimeException(e); }
                return Map.of("agregadas", texto.split("\n").length, "mensaje", "ok");
            }
        };
    }

    @Test
    void rechazaIdentificadoresInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> service.encolarCanciones(List.of("--output /etc")));
        assertThrows(IllegalArgumentException.class, () -> service.encolarCanciones(List.of("abc")));
        assertThrows(IllegalArgumentException.class, () -> service.encolarCanciones(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.encolarAlbum("PL123; rm -rf /"));
    }

    @Test
    void noRepiteLoQueYaEstaEnLaCola() {
        var r1 = service.encolarCanciones(List.of("wJYy7Yuf1Ig", "eGqU6SDE9KM"));
        assertEquals(2, r1.get("agregadas"));
        var r2 = service.encolarCanciones(List.of("wJYy7Yuf1Ig", "eGqU6SDE9KM", "r8goFcqS18k"));
        assertEquals(1, r2.get("agregadas"));
        assertEquals(Set.of("wJYy7Yuf1Ig", "eGqU6SDE9KM"), ((Map<?, ?>) r2.get("omitidas")).keySet());
    }

    @Test
    void peticionesSimultaneasNoDuplican() throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(6);
        List<Future<Map<String, Object>>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) fs.add(ex.submit(() -> service.encolarCanciones(List.of("wJYy7Yuf1Ig"))));
        int total = 0;
        for (var f : fs) total += (int) f.get().get("agregadas");
        ex.shutdown();
        assertEquals(1, total, "solo una de las seis peticiones debe agregarla");
        assertEquals(1, escrituras.get());
    }

    @Test
    void estadoDeUnResultado() throws Exception {
        Files.writeString(datos.resolve("fallidas.txt"), "https://music.youtube.com/watch?v=FALLIDA0001\n");
        Files.writeString(datos.resolve("cola-hechas").resolve("20261101-000000-1.txt"), "https://music.youtube.com/watch?v=HECHA000001\n");
        var s = service.situacion();
        CancionDTO local = new CancionDTO();
        local.setId(1L); local.setNombre("Propuesta Indecente"); local.setArtista("Romeo Santos"); local.setDuracionSegundos(236);
        assertEquals("biblioteca", service.estadoDe("wJYy7Yuf1Ig", "Propuesta Indecente", List.of("Romeo Santos"), 237, s, List.of(local)));
        assertEquals("disponible", service.estadoDe("wJYy7Yuf1Ig", "Propuesta Indecente (En Vivo)", List.of("Romeo Santos"), 250, s, List.of(local)));
        assertEquals("fallido", service.estadoDe("FALLIDA0001", "x", List.of(), null, s, null));
        assertEquals("completado", service.estadoDe("HECHA000001", "x", List.of(), null, s, null));
    }
}
