package com.bpm.music.services;

import com.bpm.music.soporte.MonitorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioPreparacionTest {
 @TempDir Path dir;
 @Test void noDescartaLaSolicitudMientrasSeTransmiteAudio() throws Exception {
  var canciones = mock(CancionService.class);
  var biblioteca = mock(BibliotecaService.class);
  var monitor = mock(MonitorService.class);
  var original = Files.write(dir.resolve("cancion.m4a"), new byte[2048]).toFile();
  when(canciones.archivo(1L)).thenReturn(original);
  when(monitor.audiosActivos()).thenReturn(1);
  var servicio = new AudioLigeroService(dir.resolve("copias").toString(), "/no/existe/ffmpeg", true, canciones, biblioteca);
  servicio.setMonitor(monitor);
  assertEquals(original, servicio.archivoRapido(1L));
  Thread.sleep(2300);
  assertEquals(1, servicio.estado().get("pendientes"), "la reproducción debe aplazar, no descartar, la preparación");
  when(monitor.audiosActivos()).thenReturn(0);
  for (int n=0; n<60 && (int)servicio.estado().get("pendientes")!=0; n++) Thread.sleep(100);
  assertEquals(0, servicio.estado().get("pendientes"));
  verify(canciones, atLeast(2)).archivo(1L);
 }
 @Test void soloLaRepresentacionDefinitivaPuedeQuedarEnCacheLarga() throws Exception {
  var servicio = new AudioLigeroService(dir.resolve("copias").toString(), "/no/existe/ffmpeg", true, mock(CancionService.class), mock(BibliotecaService.class));
  assertTrue(servicio.inicioPendiente(dir.resolve("original.m4a").toFile()));
  assertFalse(servicio.inicioPendiente(dir.resolve("copias/inicio/1.m4a").toFile()));
  assertFalse(servicio.inicioPendiente(dir.resolve("copias/1.m4a").toFile()));
  assertFalse(servicio.inicioPendiente(dir.resolve("original.flac").toFile()));
 }
}
