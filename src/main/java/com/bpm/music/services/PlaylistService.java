package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.dtos.PlaylistDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.models.Playlist;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.repositories.PlaylistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final CancionRepository  cancionRepository;
    private final CancionService     cancionService;

    public PlaylistService(PlaylistRepository playlistRepository,
                           CancionRepository cancionRepository,
                           CancionService cancionService) {
        this.playlistRepository = playlistRepository;
        this.cancionRepository  = cancionRepository;
        this.cancionService     = cancionService;
    }

    // ✅ Solo playlists del usuario autenticado
    public List<PlaylistDTO> listarPorUsuario(Long usuarioId) {
        return playlistRepository.findByUsuarioIdWithCanciones(usuarioId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // Para compatibilidad legacy — no usar en código nuevo
    public List<PlaylistDTO> listarTodas() {
        return playlistRepository.findAllWithCanciones()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // ✅ Verificar que la playlist pertenece al usuario antes de devolverla
    public PlaylistDTO getById(Long id, Long usuarioId) {
        if (usuarioId != null) {
            return playlistRepository.findByIdAndUsuarioId(id, usuarioId)
                    .map(this::convertToDTO)
                    .orElse(null);
        }
        return playlistRepository.findById(id)
                .map(this::convertToDTO)
                .orElse(null);
    }

    // Overload para compatibilidad
    public PlaylistDTO getById(Long id) {
        return getById(id, null);
    }

    // ✅ Crear playlist asignando al usuario actual
    @Transactional
    public PlaylistDTO crear(Playlist playlist, Long usuarioId) {
        if (playlist.getPortadaUrl() == null || playlist.getPortadaUrl().isEmpty()) {
            playlist.setPortadaUrl("https://singlecolorimage.com/get/1DB954/300x300");
        }
        if (playlist.getCanciones() == null) playlist.setCanciones(new ArrayList<>());
        playlist.setCancionesCount(0);
        playlist.setUsuarioId(usuarioId);  // ← asignar dueño
        return convertToDTO(playlistRepository.save(playlist));
    }

    @Transactional
    public PlaylistDTO crear(Playlist playlist) {
        return crear(playlist, null);
    }

    // ✅ Solo puede agregar canciones a sus propias playlists
    @Transactional
    public PlaylistDTO agregarCancion(Long playlistId, Long cancionId, Long usuarioId) {
        Playlist playlist = _getPlaylistDelUsuario(playlistId, usuarioId);
        Cancion  cancion  = cancionRepository.findById(cancionId)
                .orElseThrow(() -> new RuntimeException("Canción no encontrada"));

        boolean existe = playlist.getCanciones().stream()
                .anyMatch(c -> c.getId().equals(cancionId));
        if (!existe) {
            playlist.getCanciones().add(cancion);
            playlist.setCancionesCount(playlist.getCanciones().size());
            playlist = playlistRepository.save(playlist);
        }
        return convertToDTO(playlist);
    }

    @Transactional
    public PlaylistDTO agregarCancion(Long playlistId, Long cancionId) {
        return agregarCancion(playlistId, cancionId, null);
    }

    @Transactional
    public PlaylistDTO removerCancion(Long playlistId, Long cancionId, Long usuarioId) {
        Playlist playlist = _getPlaylistDelUsuario(playlistId, usuarioId);
        playlist.getCanciones().removeIf(c -> c.getId().equals(cancionId));
        playlist.setCancionesCount(playlist.getCanciones().size());
        return convertToDTO(playlistRepository.save(playlist));
    }

    @Transactional
    public PlaylistDTO removerCancion(Long playlistId, Long cancionId) {
        return removerCancion(playlistId, cancionId, null);
    }

    @Transactional
    public void eliminar(Long id, Long usuarioId) {
        if (usuarioId != null) {
            Playlist p = _getPlaylistDelUsuario(id, usuarioId);
            playlistRepository.delete(p);
        } else {
            playlistRepository.deleteById(id);
        }
    }

    @Transactional
    public void eliminar(Long id) { eliminar(id, null); }

    // ── Helper: obtener playlist verificando dueño ──
    private Playlist _getPlaylistDelUsuario(Long playlistId, Long usuarioId) {
        if (usuarioId != null) {
            return playlistRepository.findByIdAndUsuarioId(playlistId, usuarioId)
                    .orElseThrow(() -> new RuntimeException("Playlist no encontrada o no tienes permiso"));
        }
        return playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist no encontrada"));
    }

    // ✅ Editar nombre y descripción de playlist
    @Transactional
    public PlaylistDTO editarPlaylist(Long id, Long usuarioId, String nombre, String descripcion) {
        Playlist p = _getPlaylistDelUsuario(id, usuarioId);
        if (nombre != null && !nombre.isBlank()) p.setNombre(nombre.trim());
        if (descripcion != null) p.setDescripcion(descripcion.trim());
        return convertToDTO(playlistRepository.save(p));
    }

    // ── Convertir a DTO ──
    private PlaylistDTO convertToDTO(Playlist p) {
        List<CancionDTO> cancionesDTO = p.getCanciones() != null
                ? p.getCanciones().stream()
                    .map(c -> cancionService.convertToDTO(c))
                    .collect(Collectors.toList())
                : new ArrayList<>();

        return new PlaylistDTO(
                p.getId(),
                p.getNombre(),
                p.getDescripcion(),
                p.getPortadaUrl(),
                p.getCancionesCount(),
                cancionesDTO
        );
    }
}
