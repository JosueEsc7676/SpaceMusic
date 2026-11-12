import Foundation
import Observation

/// Canciones guardadas en el iPhone para escucharlas sin conexión.
/// Cada cuenta tiene su propia carpeta; al cerrar sesión se borran.
@MainActor
@Observable
final class Descargas {
    static let compartido = Descargas()

    private(set) var canciones: [Cancion] = []
    /// Progreso de las descargas en curso (0…1), por id de canción.
    private(set) var enCurso: [Int: Double] = [:]

    @ObservationIgnored private var ids: Set<Int> = []
    @ObservationIgnored private var usuarioCargado: Int?
    @ObservationIgnored private var pendientes: [Cancion] = []
    @ObservationIgnored private var tarea: Task<Void, Never>?
    @ObservationIgnored private var generacion = UUID()
    private(set) var fallidas: [Int: Cancion] = [:]
    private(set) var errores: [Int: String] = [:]

    // MARK: Guardadas al escucharlas (como el modo sin conexión de la web)
    /// Canciones que se guardaron solas al escucharlas (las más recientes primero). No salen en Descargas,
    /// pero suenan sin conexión. Se conservan como máximo `limiteAutomaticas`.
    private(set) var automaticas: [Cancion] = []
    @ObservationIgnored private var bajandoAutomatica: Set<Int> = []
    static let limiteAutomaticas = 300
    var guardarAlEscuchar: Bool {
        get { UserDefaults.standard.object(forKey: "guardarAlEscuchar") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "guardarAlEscuchar") }
    }

    private var carpeta: URL? {
        guard let uid = Sesion.compartida.usuario?.id else { return nil }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Descargas/\(uid)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var valores = URLResourceValues()
        valores.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(valores)
        return dir
    }

    private var indiceURL: URL? { carpeta?.appendingPathComponent("indice.json") }
    private var carpetaAutomaticas: URL? {
        guard let c = carpeta?.appendingPathComponent("escuchadas", isDirectory: true) else { return nil }
        try? FileManager.default.createDirectory(at: c, withIntermediateDirectories: true)
        return c
    }
    private func rutaAutomatica(de c: Cancion) -> URL? {
        let ext = (c.formato?.lowercased()).flatMap { $0.isEmpty ? nil : $0 } ?? "mp3"
        return carpetaAutomaticas?.appendingPathComponent("\(c.id).\(ext)")
    }

    /// Lee la lista de la cuenta abierta.
    func cargar() {
        let uid = Sesion.compartida.usuario?.id
        guard uid != usuarioCargado else { return }
        usuarioCargado = uid
        canciones = []
        ids = []
        automaticas = []
        if let u = carpetaAutomaticas?.appendingPathComponent("indice.json"), let d = try? Data(contentsOf: u),
           let lista = try? JSONDecoder().decode([Cancion].self, from: d) {
            automaticas = lista.filter { c in rutaAutomatica(de: c).map { FileManager.default.fileExists(atPath: $0.path) } ?? false }
        }
        guard let u = indiceURL, let d = try? Data(contentsOf: u),
              let lista = try? JSONDecoder().decode([Cancion].self, from: d) else { return }
        canciones = lista.filter { archivoExiste($0) }
        ids = Set(canciones.map(\.id))
    }

    /// Guarda en segundo plano la canción que se está escuchando (y la siguiente) para poder oírla sin conexión.
    /// Usa la misma ruta de audio que la reproducción, así no depende del permiso de descarga del servidor.
    func guardarAlEscucharla(_ c: Cancion) {
        guard guardarAlEscuchar, Sesion.compartida.conectado, !ids.contains(c.id), !bajandoAutomatica.contains(c.id),
              !automaticas.contains(where: { $0.id == c.id }),
              let origen = API.audioURL(c.id), let destino = rutaAutomatica(de: c) else { return }
        bajandoAutomatica.insert(c.id)
        let version = generacion
        Task {
            defer { bajandoAutomatica.remove(c.id) }
            guard let resultado = try? await API.sesionURL.download(from: origen) else { return }
            let (temporal, resp) = resultado
            guard version == generacion, (resp as? HTTPURLResponse)?.statusCode == 200 else {
                try? FileManager.default.removeItem(at: temporal); return
            }
            try? FileManager.default.removeItem(at: destino)
            guard (try? FileManager.default.moveItem(at: temporal, to: destino)) != nil else { return }
            automaticas.removeAll { $0.id == c.id }
            automaticas.insert(c, at: 0)
            while automaticas.count > Self.limiteAutomaticas, let vieja = automaticas.popLast() {
                if let u = rutaAutomatica(de: vieja) { try? FileManager.default.removeItem(at: u) }
            }
            guardarIndiceAutomaticas()
        }
    }

    /// Hay una canción guardándose para sin conexión (el reproductor espera a que termine para precargar).
    var guardandoAlEscuchar: Bool { !bajandoAutomatica.isEmpty }

    func borrarAutomaticas() {
        if let dir = carpetaAutomaticas { try? FileManager.default.removeItem(at: dir) }
        automaticas = []
    }

    private func guardarIndiceAutomaticas() {
        guard let u = carpetaAutomaticas?.appendingPathComponent("indice.json"), let d = try? JSONEncoder().encode(automaticas) else { return }
        try? d.write(to: u, options: .atomic)
    }

    func descargada(_ id: Int) -> Bool { ids.contains(id) }

    /// Archivo local de la canción, si está descargada.
    func archivo(de id: Int) -> URL? {
        if ids.contains(id), let c = canciones.first(where: { $0.id == id }),
           let u = ruta(de: c), FileManager.default.fileExists(atPath: u.path) { return u }
        if let c = automaticas.first(where: { $0.id == id }),
           let u = rutaAutomatica(de: c), FileManager.default.fileExists(atPath: u.path) { return u }
        return nil
    }

    /// Se puede escuchar sin conexión (descargada a mano o guardada al escucharla).
    func disponibleSinConexion(_ id: Int) -> Bool { ids.contains(id) || automaticas.contains { $0.id == id } }

    private func ruta(de c: Cancion) -> URL? {
        let ext = (c.formato?.lowercased()).flatMap { $0.isEmpty ? nil : $0 } ?? "mp3"
        return carpeta?.appendingPathComponent("\(c.id).\(ext)")
    }

    private func archivoExiste(_ c: Cancion) -> Bool {
        ruta(de: c).map { FileManager.default.fileExists(atPath: $0.path) } ?? false
    }

    func descargar(_ lista: [Cancion]) {
        for c in lista where !ids.contains(c.id) && enCurso[c.id] == nil {
            enCurso[c.id] = 0
            fallidas[c.id] = nil; errores[c.id] = nil
            pendientes.append(c)
        }
        guard tarea == nil else { return }
        let version = generacion
        tarea = Task { await procesarPendientes(version) }
    }

    /// De una en una, para no ocupar la conexión de la música que suena.
    private func procesarPendientes(_ version: UUID) async {
        defer { if version == generacion { tarea = nil } }
        while !pendientes.isEmpty, !Task.isCancelled, version == generacion {
            let c = pendientes.removeFirst()
            await bajar(c, version: version)
        }
    }

    func cancelarPendientes() {
        generacion = UUID(); tarea?.cancel(); tarea = nil
        pendientes = []; enCurso = [:]
    }

    func reintentar() { descargar(Array(fallidas.values)) }

    private func bajar(_ c: Cancion, version: UUID) async {
        guard let origen = API.descargaURL(c.id), let destino = ruta(de: c) else {
            enCurso[c.id] = nil
            return
        }
        enCurso[c.id] = 0.01
        do {
            let (temporal, resp) = try await API.sesionURL.download(from: origen)
            guard !Task.isCancelled, version == generacion else {
                try? FileManager.default.removeItem(at: temporal); return
            }
            guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw ErrorAPI(mensaje: "Error", estado: 0) }
            try? FileManager.default.removeItem(at: destino)
            try FileManager.default.moveItem(at: temporal, to: destino)
            canciones.removeAll { $0.id == c.id }
            canciones.insert(c, at: 0)
            ids.insert(c.id)
            guardarIndice()
        } catch {
            guard version == generacion, !Task.isCancelled else { return }
            fallidas[c.id] = c
            errores[c.id] = error.localizedDescription
        }
        enCurso[c.id] = nil
    }

    func borrar(_ c: Cancion) {
        if let u = ruta(de: c) { try? FileManager.default.removeItem(at: u) }
        canciones.removeAll { $0.id == c.id }
        ids.remove(c.id)
        guardarIndice()
    }

    func borrarTodo() {
        cancelarPendientes()
        if let dir = carpeta { try? FileManager.default.removeItem(at: dir) }
        canciones = []
        ids = []
        automaticas = []
        enCurso = [:]
        usuarioCargado = nil
        fallidas = [:]; errores = [:]
    }

    /// Espacio que ocupan las descargas, en bytes.
    func espacio() -> Int64 {
        guard let dir = carpeta,
              let archivos = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        return archivos.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    private func guardarIndice() {
        guard let u = indiceURL, let d = try? JSONEncoder().encode(canciones) else { return }
        try? d.write(to: u, options: .atomic)
    }
}
