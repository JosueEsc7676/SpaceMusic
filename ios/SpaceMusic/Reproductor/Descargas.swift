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

    /// Lee la lista de la cuenta abierta.
    func cargar() {
        let uid = Sesion.compartida.usuario?.id
        guard uid != usuarioCargado else { return }
        usuarioCargado = uid
        canciones = []
        ids = []
        guard let u = indiceURL, let d = try? Data(contentsOf: u),
              let lista = try? JSONDecoder().decode([Cancion].self, from: d) else { return }
        canciones = lista.filter { archivoExiste($0) }
        ids = Set(canciones.map(\.id))
    }

    func descargada(_ id: Int) -> Bool { ids.contains(id) }

    /// Archivo local de la canción, si está descargada.
    func archivo(de id: Int) -> URL? {
        guard ids.contains(id), let c = canciones.first(where: { $0.id == id }) else { return nil }
        let u = ruta(de: c)
        return u.flatMap { FileManager.default.fileExists(atPath: $0.path) ? $0 : nil }
    }

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
