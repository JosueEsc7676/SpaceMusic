package com.bpm.music.services;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Vigila la carpeta de música: cuando se copia o descarga una canción
 * (por ejemplo desde la unidad de red de tu PC), lanza un escaneo unos
 * segundos después de que el archivo deja de cambiar. Así aparece en la
 * app casi al instante, sin esperar al escaneo periódico.
 */
@Component
public class VigilanteCarpeta {

    private static final Logger log = LoggerFactory.getLogger(VigilanteCarpeta.class);
    private static final long ESPERA_SEGUNDOS = 25;

    private final EscanerService escaner;
    private final ScheduledExecutorService programador = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vigilante-escaneo");
        t.setDaemon(true);
        return t;
    });
    private WatchService watcher;
    private Thread hilo;
    private ScheduledFuture<?> pendiente;

    public VigilanteCarpeta(EscanerService escaner) {
        this.escaner = escaner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void iniciar() {
        Path raiz = escaner.raiz().toPath();
        if (!Files.isDirectory(raiz)) return;
        try {
            watcher = FileSystems.getDefault().newWatchService();
            registrarTodo(raiz);
            hilo = new Thread(this::bucle, "vigilante-musica");
            hilo.setDaemon(true);
            hilo.start();
            log.info("Vigilando cambios en {}", raiz);
        } catch (IOException e) {
            log.warn("No se pudo vigilar la carpeta de música: {}", e.getMessage());
        }
    }

    private void registrarTodo(Path inicio) throws IOException {
        Files.walkFileTree(inicio, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName() != null && dir.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void bucle() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            Path dir = (Path) key.watchable();
            boolean relevante = false;
            for (WatchEvent<?> ev : key.pollEvents()) {
                if (ev.kind() == OVERFLOW) { relevante = true; continue; }
                Path p = dir.resolve((Path) ev.context());
                if (ev.kind() == ENTRY_CREATE && Files.isDirectory(p)) {
                    try { registrarTodo(p); } catch (IOException ignored) { }
                    relevante = true;
                } else if (EscanerService.EXTENSIONES.contains(MetadataService.extension(p.getFileName().toString()))) {
                    relevante = true;
                }
            }
            key.reset();
            if (relevante) programarEscaneo();
        }
    }

    /** Espera a que los archivos dejen de cambiar antes de escanear. */
    private synchronized void programarEscaneo() {
        if (pendiente != null) pendiente.cancel(false);
        pendiente = programador.schedule(() -> {
            if (!escaner.escanearEnSegundoPlano()) programarEscaneo(); // había otro escaneo en curso
        }, ESPERA_SEGUNDOS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void detener() {
        try { if (watcher != null) watcher.close(); } catch (IOException ignored) { }
        if (hilo != null) hilo.interrupt();
        programador.shutdownNow();
    }
}
