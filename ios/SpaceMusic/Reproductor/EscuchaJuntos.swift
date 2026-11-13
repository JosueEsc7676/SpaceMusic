import Foundation
import Observation

struct SalaJuntos: Decodable, Identifiable {
    struct Entrada: Decodable {
        var cancion: Cancion?
        var de: PersonaBreve?
    }
    var id: Int
    var dentro: Bool
    var soyAnfitrion: Bool
    var anfitrion: PersonaBreve?
    var cancion: Cancion?
    var segundo: Double
    var sonando: Bool
    var personas: [PersonaBreve]
    var invitados: [PersonaBreve]
    var cola: [Entrada]
    var reacciones: [String]
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0; dentro = c.bool("dentro") ?? false
        soyAnfitrion = c.bool("soyAnfitrion") ?? false; anfitrion = c.v("anfitrion"); cancion = c.v("cancion")
        segundo = c.numero("segundo") ?? 0; sonando = c.bool("sonando") ?? false
        personas = c.lista("personas"); invitados = c.lista("invitados"); cola = c.lista("cola")
        reacciones = c.lista("reacciones")
    }
}

/// Comparte un único Reproductor con las salas; los eventos SSE corrigen a los oyentes.
@MainActor @Observable final class EscuchaJuntos {
    static let compartida = EscuchaJuntos()
    private(set) var sala: SalaJuntos?
    @ObservationIgnored private var revision: Task<Void, Never>?
    @ObservationIgnored private var sincronizando: Task<Void, Never>?
    @ObservationIgnored private var ultimo: (id: Int?, segundo: Double, sonando: Bool, fecha: Date)?
    @ObservationIgnored private var agregadas: Set<Int> = []
    var esOyente: Bool { sala?.dentro == true && sala?.soyAnfitrion == false }

    func activar(_ s: SalaJuntos) {
        guard s.dentro else { return }
        if sala?.id != s.id { agregadas = []; ultimo = nil }
        recibir(s)
        revision?.cancel()
        let cuenta = Sesion.compartida.usuario?.id
        revision = Task {
            var ciclos = 0
            while !Task.isCancelled, cuenta == Sesion.compartida.usuario?.id, sala?.id == s.id {
                if sala?.soyAnfitrion == true { await enviarEstado() }
                if ciclos % 10 == 0 {
                    do {
                        let nueva: SalaJuntos = try await API.get("/api/juntos/\(s.id)")
                        guard !Task.isCancelled, cuenta == Sesion.compartida.usuario?.id else { return }
                        recibir(nueva)
                    } catch let e as ErrorAPI where e.estado == 404 { detener(); return }
                    catch {}
                }
                ciclos += 1
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    func retomar() async {
        let cuenta = Sesion.compartida.usuario?.id
        if let salas: [SalaJuntos] = try? await API.get("/api/juntos"),
           cuenta == Sesion.compartida.usuario?.id, let s = salas.first(where: { $0.dentro }) { activar(s) }
    }

    func evento(_ tipo: String, datos: Data) {
        if tipo == "juntos", let s = try? JSONDecoder().decode(SalaJuntos.self, from: datos), sala?.id == s.id { recibir(s) }
        if tipo == "juntos-fin", let j = try? JSONSerialization.jsonObject(with: datos) as? [String: Any], j["sala"] as? Int == sala?.id {
            detener(); Avisos.compartido.mostrar("La sala terminó")
        }
        if tipo == "juntos-reaccion", let j = try? JSONSerialization.jsonObject(with: datos) as? [String: Any],
           j["sala"] as? Int == sala?.id, let emoji = j["emoji"] as? String {
            Avisos.compartido.mostrar("\(j["nombre"] as? String ?? "") \(emoji)")
        }
    }

    func recibir(_ s: SalaJuntos) {
        guard s.dentro else { detener(); return }
        sala = s
        let r = Reproductor.compartido
        if s.soyAnfitrion {
            let nuevas = s.cola.compactMap(\.cancion).filter { !agregadas.contains($0.id) }
            nuevas.forEach { agregadas.insert($0.id) }
            if r.actual == nil, !nuevas.isEmpty { r.reproducir(nuevas, contexto: "juntos") }
            else if !nuevas.isEmpty { r.agregarAlFinal(nuevas) }
        } else {
            sincronizando?.cancel()
            let recibida = Date()
            sincronizando = Task {
                guard let c = s.cancion else { r.pausar(); return }
                if r.actual?.id != c.id { r.reproducir([c], contexto: "juntos-oyente") }
                // El seek se hace cuando AVFoundation tiene el archivo preparado, no antes de cargarlo.
                for _ in 0..<200 {
                    guard !Task.isCancelled, sala?.id == s.id else { return }
                    if r.listaParaBuscar { break }
                    try? await Task.sleep(for: .milliseconds(100))
                }
                guard !Task.isCancelled, sala?.id == s.id, r.actual?.id == c.id, r.listaParaBuscar else { return }
                let objetivo = s.segundo + (s.sonando ? Date().timeIntervalSince(recibida) : 0) + 0.15
                if abs(r.tiempoExacto - objetivo) > 2 { r.buscar(objetivo) }
                if s.sonando { if !r.sonando { r.continuar() } } else { r.pausar() }
            }
        }
    }

    private func enviarEstado() async {
        guard let s = sala, s.soyAnfitrion else { return }
        let r = Reproductor.compartido, ahora = Date()
        if let u = ultimo, u.id == r.actual?.id, u.sonando == r.sonando {
            let esperado = u.segundo + (u.sonando ? ahora.timeIntervalSince(u.fecha) : 0)
            if abs(esperado - r.tiempoExacto) < 3 { return }
        }
        var cuerpo: [String: Any] = ["segundo": r.tiempoExacto, "sonando": r.sonando]
        cuerpo["cancionId"] = r.actual.map { $0.id as Any } ?? NSNull()
        do {
            try await API.hacer("POST", "/api/juntos/\(s.id)/estado", cuerpo)
            ultimo = (r.actual?.id, r.tiempoExacto, r.sonando, ahora)
        } catch {}
    }

    func salir() async throws {
        guard let s = sala else { return }
        try await API.hacer("POST", "/api/juntos/\(s.id)/salir")
        detener()
    }
    func detener() {
        let oyente = esOyente
        revision?.cancel(); revision = nil; sincronizando?.cancel(); sincronizando = nil
        sala = nil; agregadas = []; ultimo = nil
        if oyente { Reproductor.compartido.pausar() }
    }
}
