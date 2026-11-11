import Foundation
import Observation

/// Letra de la canción que suena (sincronizada si existe).
@MainActor
@Observable
final class Letras {
    static let compartido = Letras()

    private(set) var cancionId: Int?
    private(set) var lineas: [LineaLetra] = []
    private(set) var textoPlano = ""
    private(set) var cargando = false
    private(set) var mensaje: String?

    var sincronizada: Bool { !lineas.isEmpty }
    var hayLetra: Bool { sincronizada || !textoPlano.isEmpty }

    @ObservationIgnored private var tarea: Task<Void, Never>?

    func cargar(_ c: Cancion, forzar: Bool = false) {
        guard forzar || c.id != cancionId else { return }
        cancionId = c.id
        lineas = []
        textoPlano = ""
        mensaje = nil
        tarea?.cancel()
        cargando = false
        guard c.tieneLetra || forzar else { return }
        cargando = true
        tarea = Task {
            defer { if cancionId == c.id { cargando = false } }
            guard let l: Letra = try? await API.get("/api/letras/\(c.id)"), cancionId == c.id else { return }
            aplicar(l)
        }
    }

    /// Busca la letra en internet (la guarda el servidor).
    func buscarEnInternet(_ c: Cancion, forzar: Bool = false) async {
        cargando = true
        defer { if cancionId == c.id { cargando = false } }
        do {
            let l: Letra = try await API.enviar("POST", "/api/letras/\(c.id)/buscar" + (forzar ? "?forzar=true" : ""))
            guard cancionId == c.id else { return }
            if l.encontrada == false {
                mensaje = l.instrumental == true ? "Es una canción instrumental" : (l.message ?? "No se encontró la letra")
            } else {
                aplicar(l)
                if !hayLetra, let r: Letra = try? await API.get("/api/letras/\(c.id)"), cancionId == c.id { aplicar(r) }
            }
        } catch {
            guard cancionId == c.id, !Task.isCancelled else { return }
            mensaje = error.localizedDescription
        }
    }

    private func aplicar(_ l: Letra) {
        lineas = Letras.leerLRC(l.letraSincronizada)
        textoPlano = l.letra.isEmpty && lineas.isEmpty ? "" : l.letra
        if lineas.isEmpty, textoPlano.isEmpty, !l.letraSincronizada.isEmpty {
            textoPlano = l.letraSincronizada
        }
    }

    /// Línea que corresponde al segundo dado.
    func indice(en t: Double) -> Int? {
        guard !lineas.isEmpty else { return nil }
        var r: Int?
        for (i, l) in lineas.enumerated() {
            if l.tiempo <= t + 0.25 { r = i } else { break }
        }
        return r
    }

    static func leerLRC(_ texto: String) -> [LineaLetra] { LectorLRC.leer(texto) }
}
