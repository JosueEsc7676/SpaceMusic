import Foundation
import Observation

/// Datos de la cuenta que usan muchas pantallas: favoritos, playlists propias y guardados.
@MainActor
@Observable
final class Biblioteca {
    static let compartida = Biblioteca()

    private(set) var favoritos: Set<Int> = []
    private var favoritosPendientes: Set<Int> = []
    private var albumesPendientes: Set<String> = []
    private var playlistsPendientes: Set<Int> = []
    private(set) var playlists: [Playlist] = []
    private(set) var albumesGuardados: [String] = []
    private(set) var playlistsGuardadas: [Int] = []
    @ObservationIgnored private var carga: Task<Void, Never>?
    @ObservationIgnored private var version = UUID()

    func cargar() async {
        if let carga { await carga.value; return }
        let versionActual = version
        let nueva = Task { await cargarDatos() }
        carga = nueva
        await nueva.value
        if versionActual == version { carga = nil }
    }

    private func cargarDatos() async {
        let cuenta = Sesion.compartida.usuario?.id
        let servidor = Sesion.compartida.servidor
        async let f: [Int]? = try? API.get("/api/usuario/favoritos/ids")
        async let p: [Playlist]? = try? API.get("/api/playlists")
        async let g: Guardados? = try? API.get("/api/usuario/guardados")
        let resultado = await (f, p, g)
        guard !Task.isCancelled, cuenta == Sesion.compartida.usuario?.id, servidor == Sesion.compartida.servidor else { return }
        if let lista = resultado.0 { favoritos = Set(lista) }
        if let lista = resultado.1 { playlists = lista }
        if let guardados = resultado.2 {
            albumesGuardados = guardados.albumes
            playlistsGuardadas = guardados.playlists
        }
    }

    func recargarPlaylists() async {
        let versionActual = version
        if let p: [Playlist] = try? await API.get("/api/playlists"), versionActual == version { playlists = p }
    }

    // MARK: Favoritos

    func esFavorita(_ id: Int) -> Bool { favoritos.contains(id) }

    func agregarFavoritos(_ canciones: [Cancion]) async {
        let cuenta = Sesion.compartida.usuario?.id
        do {
            for cancion in canciones where !favoritos.contains(cancion.id) {
                try Task.checkCancellation()
                guard cuenta == Sesion.compartida.usuario?.id else { return }
                try await API.hacer("POST", "/api/usuario/favoritos/\(cancion.id)")
                guard cuenta == Sesion.compartida.usuario?.id else { return }
                favoritos.insert(cancion.id)
            }
            Avisos.compartido.mostrar("Canciones agregadas a favoritos")
        } catch { Avisos.compartido.error(error) }
    }

    func alternarFavorito(_ c: Cancion) {
        let versionActual = version
        guard !favoritosPendientes.contains(c.id) else { return }
        favoritosPendientes.insert(c.id)
        let cuenta = Sesion.compartida.usuario?.id
        let antes = favoritos.contains(c.id)
        if antes { favoritos.remove(c.id) } else { favoritos.insert(c.id) }
        Avisos.compartido.mostrar(antes ? "Quitada de tus favoritos" : "Agregada a tus favoritos")
        Task {
            defer { if versionActual == version { favoritosPendientes.remove(c.id) } }
            do {
                try await API.hacer(antes ? "DELETE" : "POST", "/api/usuario/favoritos/\(c.id)")
            } catch {
                guard cuenta == Sesion.compartida.usuario?.id, versionActual == version else { return }
                if antes { favoritos.insert(c.id) } else { favoritos.remove(c.id) }
                Avisos.compartido.error(error)
            }
        }
    }

    // MARK: Guardados

    func albumGuardado(_ nombre: String) -> Bool { albumesGuardados.contains(nombre) }
    func playlistGuardada(_ id: Int) -> Bool { playlistsGuardadas.contains(id) }

    func alternarAlbum(_ nombre: String) {
        let versionActual = version
        let cuenta = Sesion.compartida.usuario?.id
        guard !albumesPendientes.contains(nombre) else { return }; albumesPendientes.insert(nombre)
        let antes = albumGuardado(nombre)
        if antes { albumesGuardados.removeAll { $0 == nombre } } else { albumesGuardados.insert(nombre, at: 0) }
        Avisos.compartido.mostrar(antes ? "Quitado de tu biblioteca" : "Guardado en tu biblioteca")
        Task {
            defer { if versionActual == version { albumesPendientes.remove(nombre) } }
            do { try await API.hacer(antes ? "DELETE" : "POST", "/api/usuario/guardados/album?clave=\(API.codificar(nombre))") }
            catch {
                guard cuenta == Sesion.compartida.usuario?.id, versionActual == version else { return }
                if antes { albumesGuardados.insert(nombre, at: 0) } else { albumesGuardados.removeAll { $0 == nombre } }
                Avisos.compartido.error(error)
            }
        }
    }

    func alternarPlaylist(_ id: Int) {
        let versionActual = version
        let cuenta = Sesion.compartida.usuario?.id
        guard !playlistsPendientes.contains(id) else { return }; playlistsPendientes.insert(id)
        let antes = playlistGuardada(id)
        if antes { playlistsGuardadas.removeAll { $0 == id } } else { playlistsGuardadas.insert(id, at: 0) }
        Avisos.compartido.mostrar(antes ? "Quitada de tu biblioteca" : "Guardada en tu biblioteca")
        Task {
            defer { if versionActual == version { playlistsPendientes.remove(id) } }
            do { try await API.hacer(antes ? "DELETE" : "POST", "/api/usuario/guardados/playlist?clave=\(id)") }
            catch {
                guard cuenta == Sesion.compartida.usuario?.id, versionActual == version else { return }
                if antes { playlistsGuardadas.insert(id, at: 0) } else { playlistsGuardadas.removeAll { $0 == id } }
                Avisos.compartido.error(error)
            }
        }
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
        version = UUID()
        favoritosPendientes = []; albumesPendientes = []; playlistsPendientes = []
        carga?.cancel(); carga = nil
        favoritos = []
        playlists = []
        albumesGuardados = []
        playlistsGuardadas = []
    }
}
