import Foundation
import Observation

/// Otro dispositivo abierto con la misma cuenta (web, Android o iPhone), tal como lo informa el servidor.
struct DispositivoRemoto: Decodable, Identifiable, Hashable {
    let id: String
    let nombre: String
    let plataforma: String
    let cancion: Cancion?
    let segundo: Double
    let duracion: Double
    let sonando: Bool
    let volumen: Double
}

private struct ListaDispositivos: Decodable { let dispositivos: [DispositivoRemoto] }
private struct OrdenCanciones: Decodable { let canciones: [Cancion] }

/// Controlar otro dispositivo de la cuenta (como «Spotify Connect»), igual que en la web y en Android.
///
/// Este iPhone informa al servidor qué suena (POST /api/control/presencia: al cambiar algo y cada 30 s)
/// y recibe las órdenes por la conexión en vivo (evento «control-orden»); solo ejecuta las que son para él.
/// Desde aquí también se ven los otros dispositivos (evento «control-dispositivos») y se les mandan órdenes.
@MainActor
@Observable
final class ControlRemoto {
    static let compartido = ControlRemoto()

    private(set) var dispositivos: [DispositivoRemoto] = []
    /// Los demás dispositivos de la cuenta (sin este).
    var otros: [DispositivoRemoto] { dispositivos.filter { $0.id != id } }
    /// Otro dispositivo donde está sonando música ahora.
    var sonandoEnOtro: DispositivoRemoto? { otros.first { $0.sonando } }

    /// Identificador fijo de este iPhone (aleatorio, sin datos personales).
    let id: String = {
        let clave = "sm.dispositivo"
        if let v = UserDefaults.standard.string(forKey: clave), v.range(of: "^[A-Za-z0-9_-]{8,64}$", options: .regularExpression) != nil { return v }
        let nuevo = "i" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(24)
        UserDefaults.standard.set(nuevo, forKey: clave)
        return nuevo
    }()

    /// Nombre con el que aparece en los otros dispositivos (se puede cambiar).
    var nombre: String {
        get { UserDefaults.standard.string(forKey: "sm.dispositivo.nombre").flatMap { $0.isEmpty ? nil : $0 } ?? "App iPhone" }
        set {
            let n = String(newValue.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
            UserDefaults.standard.set(n, forKey: "sm.dispositivo.nombre")
            Task { await informar() }
        }
    }

    @ObservationIgnored private var vigilancia: Task<Void, Never>?
    @ObservationIgnored private var ultimo: (cancion: Int?, sonando: Bool, segundo: Double, volumen: Double, cuando: Date)?
    @ObservationIgnored private var ultimoLatido = Date.distantPast

    // MARK: Informar el estado de este iPhone

    /// Al conectarse: se anuncia y revisa cada segundo si cambió algo (canción, pausa, salto, volumen).
    func iniciar() {
        guard vigilancia == nil, Sesion.compartida.conectado else { return }
        vigilancia = Task { [weak self] in
            await self?.informar()
            await self?.cargar()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                await self?.revisar()
            }
        }
    }

    /// Al cerrar sesión: deja de aparecer en los otros dispositivos al instante.
    func detener() {
        vigilancia?.cancel()
        vigilancia = nil
        ultimo = nil
        dispositivos = []
        // La petición se arma ya (con el token actual): al cerrar sesión el token se borra enseguida
        if Sesion.compartida.conectado, let r = try? API.peticion("/api/control/presencia/\(id)", metodo: "DELETE") {
            Task.detached { _ = try? await URLSession.shared.data(for: r) }
        }
    }

    private func revisar() async {
        guard Sesion.compartida.conectado else { return }
        let r = Reproductor.compartido
        let ahora = (cancion: r.actual?.id, sonando: r.sonando, segundo: r.tiempo, volumen: r.volumen)
        var cambio = Date().timeIntervalSince(ultimoLatido) > 30
        if let u = ultimo {
            let esperado = u.segundo + (u.sonando ? Date().timeIntervalSince(u.cuando) : 0)
            cambio = cambio || u.cancion != ahora.cancion || u.sonando != ahora.sonando
                || abs(u.volumen - ahora.volumen) > 0.02 || abs(esperado - ahora.segundo) > 3
        } else {
            cambio = true
        }
        if cambio { await informar() }
    }

    func informar() async {
        guard Sesion.compartida.conectado else { return }
        let r = Reproductor.compartido
        ultimo = (r.actual?.id, r.sonando, r.tiempo, r.volumen, Date())
        ultimoLatido = Date()
        var cuerpo: [String: Any] = [
            "dispositivo": id, "nombre": nombre, "plataforma": "ios",
            "segundo": r.tiempo, "duracion": r.duracion, "sonando": r.sonando, "volumen": r.volumen,
        ]
        if let c = r.actual?.id { cuerpo["cancionId"] = c }
        try? await API.hacer("POST", "/api/control/presencia", cuerpo)
    }

    // MARK: Otros dispositivos

    func cargar() async {
        guard Sesion.compartida.conectado,
              let l: [DispositivoRemoto] = try? await API.get("/api/control/dispositivos?yo=\(id)", tiempo: 15) else { return }
        dispositivos = l
    }

    /// Eventos en vivo: «control-dispositivos» (la lista cambió) y «control-orden» (una orden).
    func evento(_ tipo: String, datos: Data) {
        switch tipo {
        case "control-dispositivos":
            if let l = try? JSONDecoder().decode(ListaDispositivos.self, from: datos) { dispositivos = l.dispositivos }
        case "control-orden":
            guard let o = try? JSONSerialization.jsonObject(with: datos) as? [String: Any], o["para"] as? String == id else { return }
            ejecutar(o, datos: datos)
        default:
            break
        }
    }

    private func nombreDe(_ dispositivo: String?) -> String {
        dispositivos.first { $0.id == dispositivo }?.nombre ?? "otro dispositivo"
    }

    private func ejecutar(_ o: [String: Any], datos: Data) {
        let r = Reproductor.compartido
        let numero = { (k: String) -> Double in (o[k] as? NSNumber)?.doubleValue ?? 0 }
        switch o["accion"] as? String {
        case "play": if r.actual != nil && !r.sonando { r.continuar() }
        case "pause": if r.sonando { r.pausar() }
        case "toggle": r.alternar()
        case "next": r.siguiente()
        case "prev": r.anterior()
        case "seek": r.buscar(numero("segundo"))
        case "volumen": r.fijarVolumen(numero("volumen"))
        case "reproducir", "transferir":
            guard let canciones = (try? JSONDecoder().decode(OrdenCanciones.self, from: datos))?.canciones, !canciones.isEmpty else { return }
            let indice = min(max(0, Int(numero("indice"))), canciones.count - 1)
            r.reproducir(canciones, desde: indice, contexto: "remoto")
            buscarCuandoEsteLista(numero("segundo"))
            Avisos.compartido.mostrar("Sonando aquí por orden de \(nombreDe(o["de"] as? String))")
        default:
            return
        }
        Task { try? await Task.sleep(for: .milliseconds(400)); await informar() }
    }

    /// Adelanta al segundo pedido en cuanto la canción nueva se puede adelantar (como mucho 10 s de espera).
    private func buscarCuandoEsteLista(_ segundo: Double) {
        guard segundo > 1 else { return }
        Task {
            for _ in 0..<40 {
                if Reproductor.compartido.listaParaBuscar { Reproductor.compartido.buscar(segundo); return }
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
    }

    // MARK: Mandar órdenes

    func ordenar(_ dispositivo: String, _ accion: String, _ extra: [String: Any] = [:]) async throws {
        var cuerpo = extra
        cuerpo["accion"] = accion
        cuerpo["de"] = id
        try await API.hacer("POST", "/api/control/\(API.segmento(dispositivo))/orden", cuerpo)
    }

    /// Trae a este iPhone lo que suena en otro dispositivo (desde el mismo segundo) y lo pausa allá.
    func escucharAqui(_ d: DispositivoRemoto) async {
        guard let c = d.cancion else { return }
        Reproductor.compartido.reproducir([c], desde: 0, contexto: "remoto")
        buscarCuandoEsteLista(d.segundo)
        if d.sonando { try? await ordenar(d.id, "pause") }
    }

    /// Pasa lo que suena aquí (y lo que sigue en la cola) a otro dispositivo y lo pausa aquí.
    func pasarA(_ d: DispositivoRemoto) async throws {
        let r = Reproductor.compartido
        guard let actual = r.actual else { return }
        let lista = [actual] + r.siguientes.prefix(99)
        try await ordenar(d.id, "transferir", ["cancionIds": lista.map(\.id), "indice": 0, "segundo": r.tiempo])
        r.pausar()
        Avisos.compartido.mostrar("Ahora suena en \(d.nombre)")
    }
}
