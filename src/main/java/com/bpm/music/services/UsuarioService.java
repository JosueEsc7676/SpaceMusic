package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.*;
import com.bpm.music.repositories.*;
import com.bpm.music.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.*;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;

@Service
public class UsuarioService {

    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private UsuarioFavoritoRepository favRepo;
    @Autowired private UsuarioReproduccionRepository reproRepo;
    @Autowired private CancionRepository cancionRepo;
    @Autowired private CancionService cancionService;
    @Autowired private JavaMailSender mailSender;
    @Autowired private JwtUtil jwtUtil;

    @Value("${music.playlist-images-path:./playlist-images}")
    private String uploadsPath;

    @Value("${spring.mail.username}")
    private String fromEmail;

    // =============================================
    // FAVORITOS POR USUARIO
    // =============================================
    public List<CancionDTO> getFavoritos(Long userId) {
        return favRepo.findByUsuarioIdOrderByFechaAgregadoDesc(userId)
                .stream()
                .map(f -> cancionService.convertToDTO(f.getCancion()))
                .toList();
    }

    @Transactional
    public Map<String, Object> toggleFavorito(Long userId, Long cancionId) {
        boolean existe = favRepo.existsByUsuarioIdAndCancionId(userId, cancionId);
        if (existe) {
            favRepo.deleteByUsuarioIdAndCancionId(userId, cancionId);
            return Map.of("favorito", false, "message", "Eliminado de favoritos");
        } else {
            Usuario u = usuarioRepo.findById(userId).orElseThrow();
            Cancion c = cancionRepo.findById(cancionId).orElseThrow();
            favRepo.save(UsuarioFavorito.builder().usuario(u).cancion(c).build());
            return Map.of("favorito", true, "message", "Agregado a favoritos");
        }
    }

    public boolean isFavorito(Long userId, Long cancionId) {
        return favRepo.existsByUsuarioIdAndCancionId(userId, cancionId);
    }

    // =============================================
    // REGISTRAR REPRODUCCIÓN
    // =============================================
    @Transactional
    public void registrarReproduccion(Long userId, Long cancionId, String contexto) {
        try {
            Usuario u = usuarioRepo.findById(userId).orElse(null);
            Cancion c = cancionRepo.findById(cancionId).orElse(null);
            if (u == null || c == null) return;

            reproRepo.save(UsuarioReproduccion.builder()
                    .usuario(u).cancion(c).contexto(contexto)
                    .build());
        } catch (Exception e) {
            System.err.println("Error registrando reproducción: " + e.getMessage());
        }
    }

    // =============================================
    // ESTADÍSTICAS PROPIAS DEL USUARIO
    // =============================================
    public Map<String, Object> getEstadisticas(Long userId) {
        Map<String, Object> stats = new HashMap<>();

        long totalRepros = reproRepo.countByUsuarioId(userId);
        long totalFavs   = favRepo.countByUsuarioId(userId);
        Long totalSeg    = reproRepo.findTotalSegundosEscuchados(userId);
        if (totalSeg == null) totalSeg = 0L;

        // Tiempo escuchado formateado
        long horas   = totalSeg / 3600;
        long minutos = (totalSeg % 3600) / 60;
        String tiempoFormateado = horas > 0 ? horas + "h " + minutos + "m" : minutos + " min";

        stats.put("totalReproducciones", totalRepros);
        stats.put("totalFavoritos",      totalFavs);
        stats.put("tiempoEscuchado",     tiempoFormateado);
        stats.put("segundosEscuchados",  totalSeg);

        // Top canciones
        List<Map<String, Object>> topCanciones = new ArrayList<>();
        for (Object[] row : reproRepo.findTopCancionesUsuario(userId, 10)) {
            Map<String, Object> c = new HashMap<>();
            c.put("id",      ((Number) row[0]).longValue());
            c.put("nombre",  row[1]);
            c.put("artista", row[2]);
            c.put("album",   row[3]);
            c.put("veces",   ((Number) row[4]).intValue());
            topCanciones.add(c);
        }
        stats.put("topCanciones", topCanciones);

        // Top artistas
        List<Map<String, Object>> topArtistas = new ArrayList<>();
        for (Object[] row : reproRepo.findTopArtistasUsuario(userId, 8)) {
            Map<String, Object> a = new HashMap<>();
            a.put("artista", row[0]);
            a.put("veces",   ((Number) row[1]).intValue());
            topArtistas.add(a);
        }
        stats.put("topArtistas", topArtistas);

        // Top géneros
        List<Map<String, Object>> topGeneros = new ArrayList<>();
        for (Object[] row : reproRepo.findTopGenerosUsuario(userId)) {
            Map<String, Object> g = new HashMap<>();
            g.put("genero", row[0]);
            g.put("veces",  ((Number) row[1]).intValue());
            topGeneros.add(g);
        }
        stats.put("topGeneros", topGeneros);

        // Reproducciones por mes
        List<Map<String, Object>> porMes = new ArrayList<>();
        for (Object[] row : reproRepo.findReproduccionesPorMes(userId)) {
            int anio = ((Number) row[0]).intValue();
            int mes  = ((Number) row[1]).intValue();
            Map<String, Object> m = new HashMap<>();
            m.put("anio",     anio);
            m.put("mes",      mes);
            m.put("cantidad", ((Number) row[2]).intValue());
            m.put("etiqueta", Month.of(mes).getDisplayName(TextStyle.SHORT, new Locale("es", "ES"))
                              + " " + (anio % 100));
            porMes.add(m);
        }
        stats.put("porMes", porMes);

        return stats;
    }

    // =============================================
    // SUBIR FOTO DE PERFIL
    // =============================================
    @Transactional
    public Map<String, Object> subirFotoPerfil(Long userId, MultipartFile file) {
        try {
            File dir = new File(uploadsPath + "/avatars");
            if (!dir.exists()) dir.mkdirs();

            String ext   = _getExt(file.getOriginalFilename());
            String fname = "avatar_" + userId + "_" + System.currentTimeMillis() + ext;
            Path   ruta  = Paths.get(dir.getAbsolutePath(), fname);

            Files.write(ruta, file.getBytes());

            String url = "/api/usuario/avatar/" + fname;
            Usuario u  = usuarioRepo.findById(userId).orElseThrow();
            u.setFotoPerfil(url);
            usuarioRepo.save(u);

            return Map.of("ok", true, "fotoPerfil", url);
        } catch (Exception e) {
            return Map.of("error", "Error al subir la foto: " + e.getMessage());
        }
    }

    // =============================================
    // REENVIAR VERIFICACIÓN
    // =============================================
    @Transactional
    public Map<String, Object> reenviarVerificacion(String email) {
        var opt = usuarioRepo.findByEmail(email.toLowerCase().trim());
        if (opt.isEmpty() || opt.get().isEmailVerificado())
            return Map.of("ok", true, "message", "Si aplica, recibirás un correo.");

        Usuario u     = opt.get();
        String token  = UUID.randomUUID().toString();
        u.setVerificationToken(token);
        usuarioRepo.save(u);

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(email);
            msg.setSubject("SpaceMusic - Verifica tu cuenta");
            msg.setText("Hola " + u.getUsername() + ",\n\n" +
                "Enlace de verificación:\n" +
                "http://localhost:8080/api/auth/verify?token=" + token + "\n\n— SpaceMusic");
            mailSender.send(msg);
        } catch (Exception e) {
            System.err.println("Email error: " + e.getMessage());
        }

        return Map.of("ok", true, "message", "Correo enviado.");
    }

    private String _getExt(String filename) {
        if (filename == null || !filename.contains(".")) return ".jpg";
        return filename.substring(filename.lastIndexOf('.'));
    }
}
