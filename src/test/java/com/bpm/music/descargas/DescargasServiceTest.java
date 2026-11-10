package com.bpm.music.descargas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Panel "Descargar música": lectura del estado (cola, registro, fallidas) y validación de lo que se pega. */
class DescargasServiceTest {

    @TempDir Path dir;
    DescargasService service;

    @BeforeEach
    void preparar() throws Exception {
        Path datos = dir.resolve("datos");
        Files.createDirectories(datos.resolve("cola"));
        Files.createDirectories(datos.resolve("cola-hechas"));
        Files.writeString(datos.resolve("cola").resolve("20261110-080000-1.txt"), "https://open.spotify.com/album/abc\nBad Bunny - Monaco\n");
        Files.writeString(datos.resolve("cola-hechas").resolve("20261109-080000-1.txt"), "https://youtu.be/x\n");
        Files.writeString(datos.resolve("descargas.log"),
                "\u001B[1;32m════ 10/11 08:00 · Lista 20261110-080000-1 · 2 entrada(s) ════\u001B[0m\n"
                        + "\u001B[1;32m▶ [2/2] Bad Bunny - Monaco\u001B[0m\n"
                        + "Descargando 40%\rDescargando 100%\n");
        Files.writeString(datos.resolve("fallidas.txt"), "https://open.spotify.com/track/1\nhttps://open.spotify.com/track/1\n");
        Files.writeString(datos.resolve("spotify.env"), "CLIENT_ID=x\nCLIENT_SECRET=y\n");
        Path script = dir.resolve("descargar");
        Files.writeString(script, "#!/bin/sh\n");
        script.toFile().setExecutable(true);
        service = new DescargasService(script.toString(), datos.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void leeElEstadoSinColoresNiSecretos() {
        Map<String, Object> r = service.estado(50);
        assertEquals(true, r.get("instalado"));
        List<Map<String, Object>> cola = (List<Map<String, Object>>) r.get("cola");
        assertEquals(1, cola.size());
        assertEquals(2L, cola.get(0).get("entradas"));
        Map<String, Object> p = (Map<String, Object>) r.get("progreso");
        assertEquals(2, p.get("actual"));
        assertEquals("Bad Bunny - Monaco", p.get("entrada"));
        List<String> log = (List<String>) r.get("registro");
        assertTrue(log.stream().noneMatch(l -> l.contains("\u001B")), "el registro no debe traer colores de terminal");
        assertTrue(log.contains("Descargando 100%"), "de una barra de progreso queda solo lo último");
        assertEquals(List.of("https://open.spotify.com/track/1"), r.get("fallidas"));
        assertEquals(true, r.get("spotifyConfigurado"));
        assertFalse(r.toString().contains("CLIENT_SECRET"), "las claves de Spotify nunca se devuelven");
    }

    @Test
    void rechazaLoQuePodriaSerUnaOpcionDelPrograma() {
        assertThrows(IllegalArgumentException.class, () -> service.encolar("--output /etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> service.encolar("ftp://algo.com/x"));
        assertThrows(IllegalArgumentException.class, () -> service.encolar("   \n  "));
        assertThrows(IllegalArgumentException.class, () -> service.encolar("x".repeat(600)));
    }

    @Test
    void separaVariosEnlacesEnUnaLinea() {
        assertEquals(List.of("https://a.com/1", "https://b.com/2"), DescargasService.separar("https://a.com/1 https://b.com/2"));
        assertEquals(List.of("Romeo Santos - Propuesta Indecente"), DescargasService.separar("Romeo Santos - Propuesta Indecente"));
    }

    @Test
    void soloQuitaListasValidasDeLaCola() {
        assertThrows(IllegalArgumentException.class, () -> service.quitarDeCola("../../etc/passwd"));
        assertThrows(java.util.NoSuchElementException.class, () -> service.quitarDeCola("20200101-000000-9.txt"));
        service.quitarDeCola("20261110-080000-1.txt"); // no hay descarga en curso: se puede quitar
        assertTrue(((List<?>) service.estado(10).get("cola")).isEmpty());
    }

    @Test
    void sinLaHerramientaLoDiceEnVezDeFallar() {
        DescargasService sin = new DescargasService(dir.resolve("no-existe").toString(), dir.toString());
        assertEquals(false, sin.estado(10).get("instalado"));
    }
}
