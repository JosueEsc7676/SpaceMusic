package com.bpm.music.organizador;

import com.bpm.music.externo.Proveedores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** IA en línea: solo se aceptan respuestas válidas y no se gasta cuota sin clave. Sin conexión a internet. */
class IaEnLineaTest {

    @Test
    void soloAceptaRespuestasValidasYEnSuLugar() {
        String r = "{\"r\":[{\"i\":0,\"estilo\":\"bachata\",\"contexto\":\"romantico\"},"
                + "{\"i\":1,\"estilo\":\"inventado\",\"contexto\":\"fiesta\"},"
                + "{\"i\":2,\"estilo\":\"salsa\",\"contexto\":\"fiesta\"},"
                + "{\"i\":2,\"estilo\":\"rock\",\"contexto\":\"triste\"},"
                + "{\"i\":9,\"estilo\":\"pop\",\"contexto\":\"fiesta\"}]}";
        List<Map<String, String>> x = IaEnLineaService.interpretar(r, 3);
        assertEquals(Map.of("estilo", "bachata", "contexto", "romantico"), x.get(0));
        assertNull(x.get(1), "estilo fuera de la lista: se descarta");
        assertEquals("salsa", x.get(2).get("estilo"), "un índice repetido no pisa al primero");
        assertEquals(3, x.size(), "un índice fuera de rango se ignora");
        assertTrue(IaEnLineaService.interpretar("esto no es JSON", 2).stream().allMatch(java.util.Objects::isNull));
    }

    @Test
    void sinClaveNoPideNadaYLaClaveSeGuardaFueraDelRepositorio(@TempDir Path dir) {
        Path archivo = dir.resolve("ia-clave.key");
        IaEnLineaService s = new IaEnLineaService("https://api.groq.com/openai/v1", "openai/gpt-oss-20b", "Groq", "", archivo.toString(), new Proveedores());
        assertFalse(s.configurada());
        assertFalse(s.puedePedir(300), "sin clave no se hace ninguna petición");
        assertThrows(IllegalStateException.class, () -> s.clasificarLote(List.<String[]>of(new String[]{"Romeo Santos", "Propuesta Indecente"})));
        assertThrows(IllegalArgumentException.class, () -> s.guardarClave("corta"));
        s.guardarClave("gsk_" + "a".repeat(40));
        assertTrue(s.configurada());
        assertTrue(s.puedePedir(300));
        assertFalse(s.puedePedir(0), "el presupuesto diario propio también limita");
        assertEquals("groq:openai/gpt-oss-20b", s.nombreModelo());
        assertFalse(s.estado(300).toString().contains("gsk_"), "la clave nunca se devuelve");
        s.borrarClave();
        assertFalse(s.configurada());
    }
}
