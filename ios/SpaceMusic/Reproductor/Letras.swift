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

    private(set) var sincroniaGlobal: Double = 0
    private(set) var revisionSincronia = ""
    var editandoSincronia = false

    func previsualizarGlobal(_ segundos: Double) { desfase = segundos - sincroniaGlobal }
    func terminarAjuste() {
        editandoSincronia = false
        desfase = cancionId.flatMap { desfases[String($0)] } ?? 0
    }
    func recargarAjuste() async throws {
        guard let id = cancionId else { return }
        let l: Letra = try await API.get("/api/letras/\(id)?sincronia=\(UUID().uuidString)")
        guard cancionId == id else { return }
        aplicar(l)
        desfase = 0
    }
    func guardarSincroniaGlobal(_ segundos: Double) async throws {
        guard let id = cancionId else { return }
        guard !revisionSincronia.isEmpty else { throw ErrorAPI(mensaje: "Recarga la letra antes de guardar", estado: 409) }
        let l: Letra = try await API.enviar("PUT", "/api/letras/\(id)/sincronia", ["segundos": segundos, "revision": revisionSincronia])
        guard cancionId == id else { return }
        desfases[String(id)] = nil
        UserDefaults.standard.set(desfases, forKey: "desfaseLetras")
        aplicar(l)
        desfase = 0
    }

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

    // MARK: Versos, elegir y traducir

    /// Versos que se muestran: las líneas sincronizadas (sin las pausas) o los renglones de la letra en texto.
    var versos: [(id: Int, texto: String)] {
        if sincronizada { return lineas.filter { !$0.interludio }.map { (id: $0.id, texto: $0.texto) } }
        return textoPlano.components(separatedBy: .newlines).enumerated()
            .map { (id: $0.offset, texto: $0.element.trimmingCharacters(in: .whitespaces)) }.filter { !$0.texto.isEmpty }
    }

    /// Elegir versos tocándolos en la letra (para la imagen), sin abrir otra ventana, como en la web.
    var seleccionando = false {
        didSet { if !seleccionando { elegidas = [] } }
    }
    var elegidas: Set<Int> = []
    static let maxVersos = 12

    func alternarVerso(_ id: Int) {
        if elegidas.contains(id) { elegidas.remove(id) } else if elegidas.count < Letras.maxVersos { elegidas.insert(id) }
    }

    /// La letra en bloques (estrofas), como al compartir en la web: se corta en las pausas, en las líneas vacías,
    /// en silencios de más de 6 s o a las 4 líneas.
    var bloques: [[(id: Int, texto: String)]] {
        var r: [[(id: Int, texto: String)]] = []
        var actual: [(id: Int, texto: String)] = []
        func cerrar() { if !actual.isEmpty { r.append(actual); actual = [] } }
        if sincronizada {
            var anterior: Double?
            for l in lineas {
                if l.interludio { cerrar(); anterior = nil; continue }
                if let a = anterior, l.tiempo - a > 6 { cerrar() }
                if actual.count >= 4 { cerrar() }
                actual.append((id: l.id, texto: l.texto))
                anterior = l.tiempo
            }
        } else {
            for (i, l) in textoPlano.components(separatedBy: .newlines).enumerated() {
                let t = l.trimmingCharacters(in: .whitespaces)
                if t.isEmpty { cerrar(); continue }
                if actual.count >= 4 { cerrar() }
                actual.append((id: i, texto: t))
            }
        }
        cerrar()
        return r
    }

    /// Toca un bloque: lo elige entero o lo quita (hasta 12 líneas en total).
    func alternarBloque(_ b: [(id: Int, texto: String)]) {
        let ids = Set(b.map(\.id))
        if ids.isSubset(of: elegidas) { elegidas.subtract(ids) }
        else if elegidas.union(ids).count <= Letras.maxVersos { elegidas.formUnion(ids) }
        else { Avisos.compartido.mostrar("Puedes elegir hasta \(Letras.maxVersos) líneas") }
    }

    /// Textos elegidos en el orden de la letra.
    var textosElegidos: [String] { versos.filter { elegidas.contains($0.id) }.map(\.texto) }

    /// Traducción de la letra (con la traducción del propio iPhone, sin enviar la letra a otros servicios).
    var traduciendo = false {
        didSet { if !traduciendo { errorTraduccion = nil } }
    }
    private(set) var traducciones: [Int: String] = [:]
    private(set) var traducidaId: Int?
    private(set) var errorTraduccion: String?
    var hayTraduccion: Bool { traducidaId == cancionId && !traducciones.isEmpty }

    func ponerTraducciones(_ t: [Int: String], de id: Int?) {
        guard id == cancionId else { return }
        // Las líneas que ya estaban en español vuelven iguales: no se repiten debajo
        let originales = Dictionary(uniqueKeysWithValues: versos.map { ($0.id, $0.texto) })
        traducciones = t.filter { k, v in
            let o = originales[k]?.lowercased().trimmingCharacters(in: .punctuationCharacters.union(.whitespaces))
            return o != v.lowercased().trimmingCharacters(in: .punctuationCharacters.union(.whitespaces))
        }
        traducidaId = id
        errorTraduccion = traducciones.isEmpty ? "La letra ya está en tu idioma" : nil
    }

    func falloTraduccion(_ texto: String) { errorTraduccion = texto }

    @ObservationIgnored private var tarea: Task<Void, Never>?

    func cargar(_ c: Cancion, forzar: Bool = false) {
        guard forzar || c.id != cancionId else { return }
        cancionId = c.id
        sincroniaGlobal = 0
        revisionSincronia = ""
        editandoSincronia = false
        desfase = desfases[String(c.id)] ?? 0
        seleccionando = false
        traducciones = [:]
        traducidaId = nil
        errorTraduccion = nil
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
        sincroniaGlobal = l.sincroniaGlobalSegundos
        revisionSincronia = l.revisionSincronia
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
