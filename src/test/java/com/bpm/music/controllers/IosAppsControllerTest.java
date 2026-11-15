package com.bpm.music.controllers;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IosAppsControllerTest {
    @TempDir Path carpeta;

    @Test void sirvePaginaConMimeCorrectoSinCache() throws Exception {
        Files.writeString(carpeta.resolve("ios.html"), "<!doctype html><title>SpaceMusic</title>");
        var mvc = MockMvcBuilders.standaloneSetup(new IosAppsController(carpeta.toString())).build();
        mvc.perform(get("/app/ios.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void permiteReanudarDescargaYNoExponeOtrosArchivos() throws Exception {
        Files.write(carpeta.resolve("SpaceMusic-21.ipa"), new byte[] {80, 75, 3, 4, 7, 8});
        Files.writeString(carpeta.resolve("privado.txt"), "no debe publicarse");
        var mvc = MockMvcBuilders.standaloneSetup(new IosAppsController(carpeta.toString())).build();
        mvc.perform(get("/app/SpaceMusic-21.ipa").header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(new byte[] {80, 75, 3, 4}))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"SpaceMusic.ipa\""));
        mvc.perform(get("/app/privado.txt")).andExpect(status().isNotFound());
        mvc.perform(get("/app/SpaceMusic-22.ipa")).andExpect(status().isNotFound());
    }
}
