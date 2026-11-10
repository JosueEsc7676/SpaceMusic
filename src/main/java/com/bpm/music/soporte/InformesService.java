package com.bpm.music.soporte;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Informes del soporte a partir de datos guardados (muestras por minuto, conteos diarios de incidencias,
 * errores por ruta). Si no hay datos suficientes se indica en vez de inventarlos.
 */
@Service
public class InformesService {

    private final MetricaRepository metricas;
    private final IncidenciaDiaRepository incDias;
    private final IncidenciaRepository incidencias;
    private final EndpointErrorDiaRepository endpoints;

    public InformesService(MetricaRepository metricas, IncidenciaDiaRepository incDias, IncidenciaRepository incidencias,
                           EndpointErrorDiaRepository endpoints) {
        this.metricas = metricas;
        this.incDias = incDias;
        this.incidencias = incidencias;
        this.endpoints = endpoints;
    }

    public Map<String, Object> informe(int dias) {
        dias = Math.min(Math.max(dias, 1), 365);
        LocalDate hasta = LocalDate.now();
        LocalDate desde = hasta.minusDays(dias - 1L);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("desde", desde);
        r.put("hasta", hasta);
        MetricaMuestra primera = metricas.findFirstByOrderByFechaAsc();
        r.put("datosDesde", primera == null ? null : primera.getFecha());

        // Incidencias: por día, semana y mes
        List<Object[]> filas = incDias.porDia(desde);
        Map<LocalDate, long[]> porDia = new TreeMap<>();
        for (Object[] f : filas) {
            long[] v = porDia.computeIfAbsent((LocalDate) f[0], k -> new long[2]);
            if ("ERROR".equals(f[1])) v[0] += ((Number) f[2]).longValue(); else v[1] += ((Number) f[2]).longValue();
        }
        List<Map<String, Object>> diario = new ArrayList<>();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
            long[] v = porDia.getOrDefault(d, new long[2]);
            diario.add(Map.of("dia", d.toString(), "errores", v[0], "advertencias", v[1]));
        }
        r.put("incidenciasPorDia", diario);
        r.put("incidenciasPorSemana", agrupar(diario, d -> {
            LocalDate x = LocalDate.parse(d);
            return x.get(IsoFields.WEEK_BASED_YEAR) + "-S" + String.format("%02d", x.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        }));
        r.put("incidenciasPorMes", agrupar(diario, d -> d.substring(0, 7)));
        r.put("erroresPorModulo", incDias.porCategoria(desde, hasta).stream()
                .map(f -> Map.of("modulo", f[0] == null ? "sin categoría" : f[0], "ocurrencias", f[1])).toList());

        // Métricas por hora (≤ 3 días) o por día
        List<MetricaMuestra> ms = metricas.findByFechaBetweenOrderByFechaAsc(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
        boolean porHora = dias <= 3;
        Map<String, List<MetricaMuestra>> grupos = ms.stream().collect(Collectors.groupingBy(
                m -> porHora ? m.getFecha().withMinute(0).withSecond(0).withNano(0).toString() : m.getFecha().toLocalDate().toString(),
                TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> serie = new ArrayList<>();
        grupos.forEach((k, l) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", k);
            p.put("latenciaMs", media(l, MetricaMuestra::getLatenciaMediaMs));
            p.put("latenciaP95Ms", media(l, MetricaMuestra::getLatenciaP95Ms));
            p.put("memoriaMb", media(l, m -> m.getMemoriaUsadaMb() == null ? null : m.getMemoriaUsadaMb().doubleValue()));
            p.put("discoLibreGb", media(l, MetricaMuestra::getDiscoLibreGb));
            p.put("cpu", media(l, MetricaMuestra::getCpuSistema));
            p.put("fallosAudio", l.stream().mapToLong(m -> m.getFallosAudio() == null ? 0 : m.getFallosAudio()).sum());
            p.put("errores5xx", l.stream().mapToLong(m -> m.getErrores5xx() == null ? 0 : m.getErrores5xx()).sum());
            p.put("disponibilidad", disponibilidad(l));
            serie.add(p);
        });
        r.put("serie", serie);
        r.put("agrupacion", porHora ? "hora" : "día");

        // Disponibilidad: minutos medidos en que la base de datos, el almacenamiento y la API estaban bien
        long minutosPeriodo = Duration.between(desde.atStartOfDay(), LocalDateTime.now()).toMinutes();
        r.put("disponibilidad", Map.of("porcentaje", ms.isEmpty() ? "No disponible" : disponibilidad(ms),
                "minutosMedidos", ms.size(), "minutosDelPeriodo", minutosPeriodo,
                "nota", "Solo cuenta los minutos en que el servidor estaba encendido y midió"));

        r.put("endpointsConMasErrores", endpoints.masErrores(desde, hasta).stream().limit(15)
                .map(f -> Map.of("endpoint", f[0], "estado", f[1], "cuenta", f[2])).toList());
        Map<String, Object> estados = new LinkedHashMap<>();
        for (Incidencia.Estado e : Incidencia.Estado.values()) estados.put(e.name(), incidencias.countByEstado(e));
        r.put("incidenciasPorEstado", estados);
        List<Incidencia> resueltas = incidencias.resueltasDesde(desde.atStartOfDay());
        r.put("tiempoResolucion", resueltas.size() < 3 ? Map.of("nota", "Datos insuficientes (se necesitan al menos 3 incidencias resueltas)", "resueltas", resueltas.size())
                : Map.of("horasPromedio", MonitorService.redondear(resueltas.stream()
                        .mapToLong(i -> Duration.between(i.getPrimera(), i.getResueltaEn()).toMinutes()).average().orElse(0) / 60.0), "resueltas", resueltas.size()));

        // Comparación con el período anterior del mismo largo
        LocalDate antDesde = desde.minusDays(dias), antHasta = desde.minusDays(1);
        List<MetricaMuestra> antes = metricas.findByFechaBetweenOrderByFechaAsc(antDesde.atStartOfDay(), desde.atStartOfDay());
        r.put("comparacion", Map.of(
                "actual", resumen(ms, incDias.total(desde, hasta)),
                "anterior", antes.isEmpty() ? "Sin datos del período anterior" : resumen(antes, incDias.total(antDesde, antHasta))));
        return r;
    }

    private static Map<String, Object> resumen(List<MetricaMuestra> l, long incidencias) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incidencias", incidencias);
        m.put("errores5xx", l.stream().mapToLong(x -> x.getErrores5xx() == null ? 0 : x.getErrores5xx()).sum());
        m.put("fallosAudio", l.stream().mapToLong(x -> x.getFallosAudio() == null ? 0 : x.getFallosAudio()).sum());
        m.put("solicitudes", l.stream().mapToLong(x -> x.getSolicitudes() == null ? 0 : x.getSolicitudes()).sum());
        m.put("latenciaMediaMs", media(l, MetricaMuestra::getLatenciaMediaMs));
        m.put("disponibilidad", l.isEmpty() ? "No disponible" : disponibilidad(l));
        return m;
    }

    private static Object disponibilidad(List<MetricaMuestra> l) {
        if (l.isEmpty()) return null;
        long bien = l.stream().filter(m -> Boolean.TRUE.equals(m.getDbOk()) && Boolean.TRUE.equals(m.getAlmacenamientoOk())
                && (m.getSolicitudes() == null || m.getSolicitudes() == 0 || m.getErrores5xx() == null
                || (double) m.getErrores5xx() / m.getSolicitudes() <= 0.2)).count();
        return MonitorService.redondear(100.0 * bien / l.size());
    }

    private static Double media(List<MetricaMuestra> l, Function<MetricaMuestra, Double> f) {
        DoubleSummaryStatistics s = l.stream().map(f).filter(Objects::nonNull).mapToDouble(Double::doubleValue).summaryStatistics();
        return s.getCount() == 0 ? null : MonitorService.redondear(s.getAverage());
    }

    private static List<Map<String, Object>> agrupar(List<Map<String, Object>> diario, Function<String, String> clave) {
        Map<String, long[]> g = new TreeMap<>();
        for (Map<String, Object> d : diario) {
            long[] v = g.computeIfAbsent(clave.apply((String) d.get("dia")), k -> new long[2]);
            v[0] += (long) d.get("errores");
            v[1] += (long) d.get("advertencias");
        }
        return g.entrySet().stream().map(e -> Map.<String, Object>of("periodo", e.getKey(), "errores", e.getValue()[0], "advertencias", e.getValue()[1])).toList();
    }

    // ═══════════════════ CSV ═══════════════════

    public String csv(String tipo, int dias) {
        dias = Math.min(Math.max(dias, 1), 365);
        LocalDate hasta = LocalDate.now(), desde = hasta.minusDays(dias - 1L);
        StringBuilder sb = new StringBuilder("﻿"); // BOM: Excel abre bien los acentos
        switch (tipo) {
            case "incidencias" -> {
                sb.append("id;severidad;categoria;origen;tipo;mensaje;endpoint;primera;ultima;repeticiones;estado\n");
                var p = incidencias.buscar(null, null, null, null, null, desde.atStartOfDay(), null, PageRequest.of(0, 5000));
                for (Incidencia i : p) sb.append(String.join(";", s(i.getId()), s(i.getSeveridad()), s(i.getCategoria()), s(i.getOrigen()),
                        s(i.getTipo()), s(i.getMensaje()), s(i.getEndpoint()), s(i.getPrimera()), s(i.getUltima()), s(i.getRepeticiones()), s(i.getEstado()))).append('\n');
            }
            case "metricas" -> {
                sb.append("fecha;memoriaMb;memoriaMaxMb;cpuProceso;cpuSistema;discoLibreGb;solicitudes;errores4xx;errores5xx;latenciaMediaMs;latenciaP95Ms;fallosAudio;fallosLogin;dbOk;dbLatenciaMs\n");
                for (MetricaMuestra m : metricas.findByFechaBetweenOrderByFechaAsc(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay()))
                    sb.append(String.join(";", s(m.getFecha()), s(m.getMemoriaUsadaMb()), s(m.getMemoriaMaxMb()), s(m.getCpuProceso()), s(m.getCpuSistema()),
                            s(m.getDiscoLibreGb()), s(m.getSolicitudes()), s(m.getErrores4xx()), s(m.getErrores5xx()), s(m.getLatenciaMediaMs()),
                            s(m.getLatenciaP95Ms()), s(m.getFallosAudio()), s(m.getFallosLogin()), s(m.getDbOk()), s(m.getDbLatenciaMs()))).append('\n');
            }
            case "endpoints" -> {
                sb.append("endpoint;estadoHttp;cuenta\n");
                for (Object[] f : endpoints.masErrores(desde, hasta)) sb.append(String.join(";", s(f[0]), s(f[1]), s(f[2]))).append('\n');
            }
            default -> throw new IllegalArgumentException("Tipo de informe desconocido");
        }
        return sb.toString();
    }

    /** Celda CSV segura: sin saltos de línea ni ";" y sin fórmulas (evita inyección en Excel). */
    private static String s(Object o) {
        if (o == null) return "";
        String v = String.valueOf(o).replace("\r", " ").replace("\n", " ").replace(";", ",").replace("\"", "'");
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return v;
    }
}
