import Foundation
import Observation

/// Datos de la cuenta que usan muchas pantallas: favoritos, playlists propias y guardados.
@MainActor
@Observable
final class Biblioteca {
    static let compartida = Biblioteca()

    private(set) var favoritos: Set<Int> = []
    private(set) var playlists: [Playlist] = []
    private(set) var albumesGuardados: [String] = []
    private(set) var playlistsGuardadas: [Int] = []

    func cargar() async {
        async let f: [Int]? = try? API.get("/api/usuario/favoritos/ids")
        async let p: [Playlist]? = try? API.get("/api/playlists")
        async let g: Guardados? = try? API.get("/api/usuario/guardados")
        if let f = await f { favoritos = Set(f) }
        if let p = await p { playlists = p }
        if let g = await g {
            albumesGuardados = g.albumes
            playlistsGuardadas = g.playlists
        }
    }

    func recargarPlaylists() async {
        if let p: [Playlist] = try? await API.get("/api/playlists") { playlists = p }
    }

    // MARK: Favoritos

    func esFavorita(_ id: Int) -> Bool { favoritos.contains(id) }

    func alternarFavorito(_ c: Cancion) {
        let antes = favoritos.contains(c.id)
        if antes { favoritos.remove(c.id) } else { favoritos.insert(c.id) }
        Avisos.compartido.mostrar(antes ? "Quitada de tus favoritos" : "Agregada a tus favoritos")
        Task {
            do {
                try await API.hacer(antes ? "DELETE" : "POST", "/api/usuario/favoritos/\(c.id)")
            } catch {
                if antes { favoritos.insert(c.id) } else { favoritos.remove(c.id) }
                Avisos.compartido.error(error)
            }
        }
    }

    // MARK: Guardados

    func albumGuardado(_ nombre: String) -> Bool { albumesGuardados.contains(nombre) }
    func playlistGuardada(_ id: Int) -> Bool { playlistsGuardadas.contains(id) }

    func alternarAlbum(_ nombre: String) {
        let antes = albumGuardado(nombre)
        if antes { albumesGuardados.removeAll { $0 == nombre } } else { albumesGuardados.insert(nombre, at: 0) }
        Avisos.compartido.mostrar(antes ? "Quitado de tu biblioteca" : "Guardado en tu biblioteca")
        Task { try? await API.hacer(antes ? "DELETE" : "POST", "/api/usuario/guardados/album?clave=\(API.codificar(nombre))") }
    }

    func alternarPlaylist(_ id: Int) {
        let antes = playlistGuardada(id)
        if antes { playlistsGuardadas.removeAll { $0 == id } } else { playlistsGuardadas.insert(id, at: 0) }
        Avisos.compartido.mostrar(antes ? "Quitada de tu biblioteca" : "Guardada en tu biblioteca")
        Task { try? await API.hacer(antes ? "DELETE" : "POST", "/api/usuario/guardados/playlist?clave=\(id)") }
    }

    // MARK: Playlists

    func crearPlaylist(nombre: String, descripcion: String = "", publica: Bool = false, canciones: [Int] = []) async throws -> Playlist {
        var cuerpo: [String: Any] = ["nombre": nombre, "descripcion": descripcion, "publica": publica]
        if !canciones.isEmpty { cuerpo["canciones"] = canciones }
        let p: Playlist = try await API.enviar("POST", "/api/playlists", cuerpo)
        await recargarPlaylists()
        return p
    }

    func agregar(_ canciones: [Cancion], a p: Playlist) async {
        do {
            try await API.hacer("POST", "/api/playlists/\(p.id)/canciones", ["ids": canciones.map(\.id)])
            Avisos.compartido.mostrar("Agregada a “\(p.nombre)”")
            await recargarPlaylists()
        } catch {
            Avisos.compartido.error(error)
        }
    }

    // MARK: Sesión

    /// Pide un token nuevo si al actual le quedan menos de 2 días (y trae el rol actualizado).
    func renovarSesionSiHaceFalta() async {
        let s = Sesion.compartida
        guard s.conectado, let resta = s.segundosRestantes(), resta < 2 * 86400 else { return }
        if let r: RespuestaSesion = try? await API.enviar("POST", "/api/auth/refresh") {
            s.guardar(token: r.token, usuario: r.usuario)
        }
    }

    func limpiar() {
        favoritos = []
        playlists = []
        albumesGuardados = []
        playlistsGuardadas = []
    }
}
