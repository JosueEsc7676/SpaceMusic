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

    /// Ajuste de sincronía de la canción actual, en segundos (como «Ajustar sincronía» de la web).
    /// Positivo: la letra va más tarde. Negativo: va antes. Se guarda por canción en este iPhone.
    private(set) var desfase: Double = 0
    @ObservationIgnored private var desfases: [String: Double] = UserDefaults.standard.dictionary(forKey: "desfaseLetras") as? [String: Double] ?? [:]

    /// Tiempo de la letra que corresponde al tiempo del audio.
    func tiempoLetra(_ audio: Double) -> Double { audio - desfase }

    /// Suma `delta` segundos al ajuste; `nil` lo restablece.
    func ajustarDesfase(_ delta: Double?) {
        guard let id = cancionId else { return }
        let nuevo = delta.map { ((desfase + $0) * 10).rounded() / 10 } ?? 0
        desfase = nuevo
        if nuevo == 0 { desfases[String(id)] = nil } else { desfases[String(id)] = nuevo }
        UserDefaults.standard.set(desfases, forKey: "desfaseLetras")
    }
    var hayLetra: Bool { sincronizada || !textoPlano.isEmpty }

    @ObservationIgnored private var tarea: Task<Void, Never>?

    func cargar(_ c: Cancion, forzar: Bool = false) {
        guard forzar || c.id != cancionId else { return }
        cancionId = c.id
        desfase = desfases[String(c.id)] ?? 0
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
        LectorLRC.indice(lineas, en: t + 0.25)
    }

    static func leerLRC(_ texto: String) -> [LineaLetra] { LectorLRC.leer(texto) }
}
