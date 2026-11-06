package com.bpm.music.services;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Guarda en memoria las últimas advertencias y errores del servidor para verlos desde Administración → Soporte. */
@Component
public class RegistroErrores {

    private static final int MAX = 200;
    private final Deque<Map<String, Object>> eventos = new ArrayDeque<>();

    @PostConstruct
    void registrar() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        AppenderBase<ILoggingEvent> a = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent e) {
                if (!e.getLevel().isGreaterOrEqual(Level.WARN)) return;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fecha", LocalDateTime.ofInstant(Instant.ofEpochMilli(e.getTimeStamp()), ZoneId.systemDefault()));
                m.put("nivel", e.getLevel().toString());
                String origen = e.getLoggerName();
                m.put("origen", origen.substring(origen.lastIndexOf('.') + 1));
                m.put("mensaje", recortar(e.getFormattedMessage(), 600));
                IThrowableProxy t = e.getThrowableProxy();
                if (t != null) m.put("detalle", recortar(t.getClassName() + ": " + t.getMessage(), 400));
                synchronized (eventos) {
                    eventos.addFirst(m);
                    while (eventos.size() > MAX) eventos.removeLast();
                }
            }
        };
        a.setContext(ctx);
        a.setName("registro-soporte");
        a.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(a);
    }

    public List<Map<String, Object>> ultimos() {
        synchronized (eventos) { return new ArrayList<>(eventos); }
    }

    public void vaciar() {
        synchronized (eventos) { eventos.clear(); }
    }

    private static String recortar(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
