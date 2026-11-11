import Foundation
import Observation

struct LineaLetra: Identifiable, Equatable {
    let id: Int
    let tiempo: Double
    let texto: String
}

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

    /// Convierte "[01:23.45] texto" en líneas con tiempo.
    static func leerLRC(_ lrc: String) -> [LineaLetra] {
        var r: [(Double, String)] = []
        let patron = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]"#)
        for fila in lrc.components(separatedBy: .newlines) {
            let ns = fila as NSString
            let marcas = patron.matches(in: fila, range: NSRange(location: 0, length: ns.length))
            guard !marcas.isEmpty, let ultima = marcas.last else { continue }
            let texto = ns.substring(from: ultima.range.location + ultima.range.length).trimmingCharacters(in: .whitespaces)
            for m in marcas {
                let min = Double(ns.substring(with: m.range(at: 1))) ?? 0
                let seg = Double(ns.substring(with: m.range(at: 2))) ?? 0
                var frac = 0.0
                if m.range(at: 3).location != NSNotFound {
                    let f = ns.substring(with: m.range(at: 3))
                    frac = (Double(f) ?? 0) / pow(10, Double(f.count))
                }
                r.append((min * 60 + seg + frac, texto))
            }
        }
        return r.sorted { $0.0 < $1.0 }.enumerated().map { LineaLetra(id: $0.offset, tiempo: $0.element.0, texto: $0.element.1) }
    }
}
