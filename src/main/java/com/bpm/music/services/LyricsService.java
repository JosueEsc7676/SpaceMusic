// services/LyricsService.java
package com.bpm.music.services;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LyricsService {

    // 🔥 VALIDAR LRC (más estricto)
    public boolean esLrcValido(String content) {
        if (content == null || content.isEmpty()) return false;
        // Busca al menos un timestamp en formato [mm:ss.xx] o [mm:ss.xx]
        Pattern pattern = Pattern.compile("\\[\\d{2}:\\d{2}(?:\\.\\d{2,3})?\\]");
        return pattern.matcher(content).find();
    }

    // 🔥 PARSEAR LRC
    public List<LyricsLine> parseLrc(String lrc) {
        List<LyricsLine> lines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return lines;

        // Patrón para [mm:ss.xx] o [mm:ss.xx]
        Pattern pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{2,3}))?\\](.*)");

        for (String line : lrc.split("\\n")) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                int minutes = Integer.parseInt(m.group(1));
                int seconds = Integer.parseInt(m.group(2));
                String millisStr = m.group(3);
                double millis = 0;
                if (millisStr != null) {
                    millis = Double.parseDouble(millisStr) / (millisStr.length() == 2 ? 100 : 1000);
                }
                double time = minutes * 60 + seconds + millis;
                String text = m.group(4).trim();

                // Limpiar texto de etiquetas
                text = text.replaceAll("\\[.*?\\]", "").trim();

                if (!text.isEmpty()) {
                    lines.add(new LyricsLine(time, text));
                }
            }
        }

        lines.sort(Comparator.comparingDouble(LyricsLine::getTime));
        return lines;
    }

    // Clase interna para líneas de letra
    public static class LyricsLine {
        private double time;
        private String text;

        public LyricsLine(double time, String text) {
            this.time = time;
            this.text = text;
        }

        public double getTime() { return time; }
        public String getText() { return text; }
        public void setTime(double time) { this.time = time; }
        public void setText(String text) { this.text = text; }
    }
}