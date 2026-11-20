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
    /// Hora del servidor (ms) en la que valía «segundo».
    var ts: Double? = nil
    /// Es el dispositivo activo de la cuenta (el que suena, como en Spotify Connect).
    var activo: Bool? = nil
    /// Lo que sigue en su cola.
    var cola: [Cancion]? = nil
}

private struct ListaDispositivos: Decodable { let dispositivos: [DispositivoRemoto]; var version: Int? = nil }
private struct Cluster: Decodable { let version: Int; let activo: String?; let dispositivos: [DispositivoRemoto] }
private struct OrdenCanciones: Decodable { let canciones: [Cancion] }
private struct OrdenEnviada: Decodable { let id: String }

struct ErrorControl: LocalizedError {
    let mensaje: String
    var errorDescription: String? { mensaje }
}

/// Controlar la reproducción entre los dispositivos de la cuenta con el modelo de «Spotify Connect», igual que la web:
///
/// - Conexión permanente por WebSocket (`/api/control/ws`, como el «dealer» de Spotify): llegan el estado de la cuenta
///   («cluster»: dispositivos, cuál está activo y versión) y las órdenes; salen el estado de este iPhone y las confirmaciones.
/// - Cada orden lleva identificador y el dispositivo destino la confirma: aquí se sabe si se aplicó o por qué no.
/// - Un solo dispositivo suena a la vez: si este empieza a sonar, el servidor pausa el que sonaba.
/// - Reloj sincronizado con el servidor (ping/pong) para mostrar la posición exacta de los otros.
/// Si el WebSocket no está disponible se usa la vía anterior (HTTP + eventos en vivo «control-*»).
@MainActor
@Observable
final class ControlRemoto {
    static let compartido = ControlRemoto()

    private(set) var dispositivos: [DispositivoRemoto] = []
    private(set) var activo: String?
    private(set) var conectadoEnVivo = false
    /// Los demás dispositivos de la cuenta (sin este).
    var otros: [DispositivoRemoto] { dispositivos.filter { $0.id != id } }
    /// Otro dispositivo donde está sonando música ahora.
    var sonandoEnOtro: DispositivoRemoto? { otros.first { $0.sonando } }
    /// Dispositivo que se controla con el reproductor de siempre (como Spotify Connect), o nil si la música es de aquí.
    var controlando: DispositivoRemoto? { Reproductor.compartido.remoto }

    /// Elegido en «Dispositivos». Sin elección, se controla solo el dispositivo activo de la cuenta cuando aquí no suena nada.
    @ObservationIgnored private var elegido: String?
    /// Dispositivo que se dejó de controlar a propósito («Este iPhone»): no se vuelve a tomar solo hasta que vuelva a sonar.
    @ObservationIgnored private var ignorado: String? { didSet { if ignorado != nil { ignoradoSonaba = true } } }
    /// Si el ignorado estaba sonando en el último estado: vuelve a tomarse solo cuando pasa de pausa a sonando.
    @ObservationIgnored private var ignoradoSonaba = true

    /// A qué dispositivo apunta el reproductor ahora.
    var destino: DispositivoRemoto? {
        guard Sesion.compartida.conectado, !EscuchaJuntos.compartida.enSyncPlay else { return nil }
        let r = Reproductor.compartido
        if let elegido, let d = otros.first(where: { $0.id == elegido }) { return d }
        // Si aquí suena música, la música es de aquí
        guard r.remoto != nil || !r.sonando else { return nil }
        guard let a = activo, a != id, a != ignorado, let d = otros.first(where: { $0.id == a }), d.cancion != nil else { return nil }
        return d
    }

    /// Pone el reproductor en el dispositivo que corresponde (o lo devuelve a este iPhone).
    func reflejar() { Reproductor.compartido.reflejar(destino) }

    /// «Dispositivos» → otro dispositivo: desde ahora la música suena allá y se controla con el mismo reproductor.
    /// Si aquí estaba sonando algo, pasa allá desde el mismo segundo (y aquí se pausa).
    func elegir(_ d: DispositivoRemoto) async {
        let r = Reproductor.compartido
        if r.remoto == nil, let actual = r.actual, r.sonando || d.cancion == nil {
            let lista = [actual] + r.siguientes.prefix(99)
            let segundo = r.tiempo
            do {
                try await ordenar(d.id, "transferir", ["cancionIds": lista.map(\.id), "indice": 0, "segundo": segundo])
            } catch {
                Avisos.compartido.error(error)
                return
            }
            r.sinControlExterno { r.pausar() }
        }
        elegido = d.id
        ignorado = nil
        reflejar()
        Avisos.compartido.mostrar("Ahora suena en \(d.nombre)")
    }

    /// «Dispositivos» → este iPhone: si el otro estaba sonando, la música sigue aquí desde el mismo segundo.
    func elegirEsteIPhone() async {
        elegido = nil
        let r = Reproductor.compartido
        guard let d = r.remoto else { return }
        ignorado = d.id
        if d.sonando, let c = d.cancion {
            let lista = [c] + (d.cola ?? [])
            let segundo = posicion(d)
            r.salirDeRemoto(restaurar: false)
            r.reproducir(lista, desde: 0, contexto: "remoto")
            buscarCuandoEsteLista(segundo)
            try? await ordenar(d.id, "pause")
        } else {
            r.salirDeRemoto(restaurar: true)
        }
    }

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
            Task { await informar(forzar: true) }
        }
    }

    @ObservationIgnored private var vigilancia: Task<Void, Never>?
    @ObservationIgnored private var lectura: Task<Void, Never>?
    @ObservationIgnored private var socket: URLSessionWebSocketTask?
    @ObservationIgnored private let sesionWS = URLSession(configuration: .default)
    @ObservationIgnored private var espera: Double = 1
    @ObservationIgnored private var ultimo: (cancion: Int?, sonando: Bool, segundo: Double, volumen: Double, cola: [Int], cuando: Date)?
    @ObservationIgnored private var ultimoLatido = Date.distantPast
    @ObservationIgnored private var ultimoPing = Date.distantPast
    @ObservationIgnored private var seq = Int(Date().timeIntervalSince1970 * 1000)
    @ObservationIgnored private var version = -1
    /// Diferencia con la hora del servidor (ms) y la menor demora medida (como NTP).
    @ObservationIgnored private var desfase: Double = 0
    @ObservationIgnored private var mejorDemora = Double.infinity
    /// Órdenes enviadas que esperan confirmación: por referencia propia y, cuando el servidor la acepta, por su id.
    @ObservationIgnored private var porRef: [String: CheckedContinuation<Void, Error>] = [:]
    @ObservationIgnored private var refDeId: [String: String] = [:]

    // MARK: Reloj

    /// Hora del servidor en milisegundos (con el desfase medido por ping/pong); también la usa SyncPlay.
    var ahoraServidor: Double { Date().timeIntervalSince1970 * 1000 + desfase }

    /// Segundo en que va otro dispositivo ahora mismo.
    func posicion(_ d: DispositivoRemoto) -> Double {
        var s = d.segundo
        if d.sonando, let ts = d.ts { s += max(0, ahoraServidor - ts) / 1000 }
        return d.duracion > 0 ? min(s, d.duracion) : s
    }

    // MARK: Ciclo de vida

    /// Al conectarse: abre el WebSocket y revisa cada segundo si cambió algo (canción, pausa, salto, volumen, cola).
    func iniciar() {
        guard vigilancia == nil, Sesion.compartida.conectado else { return }
        conectar()
        vigilancia = Task { [weak self] in
            await self?.informar(forzar: true)
            await self?.cargar()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                await self?.revisar()
            }
        }
    }

    /// Al cerrar sesión: cierra la conexión y deja de aparecer en los otros dispositivos al instante.
    func detener() {
        vigilancia?.cancel(); vigilancia = nil
        lectura?.cancel(); lectura = nil
        socket?.cancel(with: .goingAway, reason: nil); socket = nil
        conectadoEnVivo = false
        fallarPendientes("Se cerró la sesión")
        ultimo = nil
        dispositivos = []
        activo = nil
        version = -1
        // La petición se arma ya (con el token actual): al cerrar sesión el token se borra enseguida
        if Sesion.compartida.conectado, let r = try? API.peticion("/api/control/presencia/\(id)", metodo: "DELETE") {
            Task.detached { _ = try? await URLSession.shared.data(for: r) }
        }
    }

    // MARK: WebSocket

    private func urlWS() -> URL? {
        guard let token = Sesion.compartida.token,
              var c = URLComponents(string: Sesion.compartida.servidor) else { return nil }
        c.scheme = c.scheme == "http" ? "ws" : "wss"
        c.path = "/api/control/ws"
        c.queryItems = [URLQueryItem(name: "t", value: token)]
        return c.url
    }

    private func conectar() {
        guard socket == nil, Sesion.compartida.conectado, let url = urlWS() else { return }
        let t = sesionWS.webSocketTask(with: url)
        socket = t
        t.resume()
        lectura = Task { [weak self] in await self?.leer(t) }
        Task { [weak self] in
            guard let self else { return }
            var hola = self.estado()
            hola["tipo"] = "hola"
            await self.enviar(hola)
            self.ping()
        }
    }

    private func leer(_ t: URLSessionWebSocketTask) async {
        while !Task.isCancelled {
            do {
                let m = try await t.receive()
                if !conectadoEnVivo { conectadoEnVivo = true; espera = 1 }
                switch m {
                case .string(let s): recibir(Data(s.utf8))
                case .data(let d): recibir(d)
                @unknown default: break
                }
            } catch {
                break
            }
        }
        guard socket === t else { return }
        // Se cortó: las órdenes pendientes fallan ya y se reconecta con espera creciente
        socket = nil
        conectadoEnVivo = false
        fallarPendientes("Se perdió la conexión")
        guard vigilancia != nil, Sesion.compartida.conectado else { return }
        let s = espera
        espera = min(espera * 2, 30)
        try? await Task.sleep(for: .seconds(s))
        if vigilancia != nil { conectar() }
    }

    @discardableResult
    private func enviar(_ m: [String: Any]) async -> Bool {
        guard let socket, let d = try? JSONSerialization.data(withJSONObject: m), let s = String(data: d, encoding: .utf8) else { return false }
        do { try await socket.send(.string(s)); return true } catch { return false }
    }

    private func ping() {
        ultimoPing = Date()
        Task { await enviar(["tipo": "ping", "t": Date().timeIntervalSince1970 * 1000]) }
    }

    private func recibir(_ datos: Data) {
        guard let m = try? JSONSerialization.jsonObject(with: datos) as? [String: Any] else { return }
        switch m["tipo"] as? String {
        case "hola":
            if let s = (m["servidorMs"] as? NSNumber)?.doubleValue, mejorDemora == .infinity { desfase = s - Date().timeIntervalSince1970 * 1000 }
        case "pong":
            guard let t = (m["t"] as? NSNumber)?.doubleValue, let s = (m["servidorMs"] as? NSNumber)?.doubleValue else { return }
            let demora = Date().timeIntervalSince1970 * 1000 - t
            if demora <= mejorDemora * 1.5 || demora < 150 {
                mejorDemora = min(mejorDemora, demora)
                desfase = s - (t + demora / 2)
            }
        case "cluster":
            if let c = try? JSONDecoder().decode(Cluster.self, from: datos) { aplicar(version: c.version, activo: c.activo, c.dispositivos) }
        case "orden":
            ejecutar(m, datos: datos, porWS: true)
        case "orden-enviada":
            if let ref = m["ref"] as? String, let id = m["id"] as? String, porRef[ref] != nil { refDeId[id] = ref }
        case "ack":
            confirmar(m)
        default:
            break
        }
    }

    private func aplicar(version v: Int?, activo a: String??, _ lista: [DispositivoRemoto]) {
        if let v { if v < version { return }; version = v }   // un cluster que llegó tarde no pisa al actual
        if let a { activo = a }
        dispositivos = lista
        // El dispositivo que se dejó de controlar volvió a sonar: se vuelve a mostrar aquí
        if let i = ignorado {
            if let d = lista.first(where: { $0.id == i }) {
                if d.sonando && !ignoradoSonaba { ignorado = nil } else { ignoradoSonaba = d.sonando }
            } else {
                ignorado = nil
            }
        }
        if let e = elegido, !lista.contains(where: { $0.id == e }) { elegido = nil }
        reflejar()
    }

    // MARK: Estado de este iPhone

    private func estado() -> [String: Any] {
        let r = Reproductor.compartido
        // Controlando otro dispositivo aquí no suena nada: se informa vacío (si no, parecería que suena en dos)
        if r.remoto != nil {
            return ["dispositivo": id, "nombre": nombre, "plataforma": "ios", "segundo": 0, "duracion": 0,
                    "volumen": r.volumen, "inicio": 0, "sonando": false, "cola": [Int]()]
        }
        var e: [String: Any] = [
            "dispositivo": id, "nombre": nombre, "plataforma": "ios",
            "segundo": r.tiempo, "duracion": r.duracion, "volumen": r.volumen, "inicio": r.inicioUsuario,
            // En un SyncPlay la música la decide SyncPlay: no compite por ser «el activo» de la cuenta
            "sonando": r.sonando && !EscuchaJuntos.compartida.enSyncPlay,
            "cola": r.siguientes.prefix(10).map(\.id),
        ]
        if let c = r.actual?.id { e["cancionId"] = c }
        return e
    }

    private func revisar() async {
        guard Sesion.compartida.conectado else { return }
        if socket == nil { conectar() }
        if conectadoEnVivo, Date().timeIntervalSince(ultimoPing) > 25 { ping() }
        let r = Reproductor.compartido
        let cola = r.siguientes.prefix(10).map(\.id)
        var cambio = Date().timeIntervalSince(ultimoLatido) > 30
        if let u = ultimo {
            let esperado = u.segundo + (u.sonando ? Date().timeIntervalSince(u.cuando) : 0)
            cambio = cambio || u.cancion != r.actual?.id || u.sonando != r.sonando || u.cola != cola
                || abs(u.volumen - r.volumen) > 0.02 || abs(esperado - r.tiempo) > 2
        } else {
            cambio = true
        }
        if cambio { await informar() }
        reflejar()
    }

    func informar(forzar: Bool = false) async {
        guard Sesion.compartida.conectado else { return }
        let r = Reproductor.compartido
        ultimo = (r.actual?.id, r.sonando, r.tiempo, r.volumen, r.siguientes.prefix(10).map(\.id), Date())
        ultimoLatido = Date()
        var e = estado()
        seq += 1
        e["tipo"] = "estado"
        e["seq"] = seq
        if conectadoEnVivo, await enviar(e) { return }
        e.removeValue(forKey: "tipo")
        try? await API.hacer("POST", "/api/control/presencia", e)
    }

    // MARK: Otros dispositivos

    func cargar() async {
        if conectadoEnVivo { await enviar(["tipo": "cluster"]); return }
        guard Sesion.compartida.conectado,
              let l: [DispositivoRemoto] = try? await API.get("/api/control/dispositivos?yo=\(id)", tiempo: 15) else { return }
        dispositivos = l
    }

    /// Vía anterior (sin WebSocket): eventos en vivo «control-dispositivos», «control-orden» y «control-ack».
    func evento(_ tipo: String, datos: Data) {
        switch tipo {
        case "control-dispositivos":
            guard !conectadoEnVivo, let l = try? JSONDecoder().decode(ListaDispositivos.self, from: datos) else { return }
            aplicar(version: l.version, activo: nil, l.dispositivos)
        case "control-orden":
            guard !conectadoEnVivo, let o = try? JSONSerialization.jsonObject(with: datos) as? [String: Any] else { return }
            ejecutar(o, datos: datos, porWS: false)
        case "control-ack":
            if let a = try? JSONSerialization.jsonObject(with: datos) as? [String: Any] { confirmar(a) }
        default:
            break
        }
    }

    private func nombreDe(_ dispositivo: String?) -> String {
        dispositivos.first { $0.id == dispositivo }?.nombre ?? "otro dispositivo"
    }

    // MARK: Órdenes que llegan

    private func ejecutar(_ o: [String: Any], datos: Data, porWS: Bool) {
        guard o["para"] as? String == id else { return }
        // La pausa automática del servidor (otro dispositivo empezó a sonar) no aplica dentro de SyncPlay
        if o["de"] as? String == "servidor", EscuchaJuntos.compartida.enSyncPlay {
            if porWS, let idOrden = o["id"] as? String { Task { await enviar(["tipo": "ack", "id": idOrden, "ok": true]) } }
            return
        }
        let r = Reproductor.compartido
        let numero = { (k: String) -> Double in (o[k] as? NSNumber)?.doubleValue ?? 0 }
        var ok = true
        var error: String?
        let accion = o["accion"] as? String
        if r.remoto != nil {
            // Aquí no suena nada (se controla otro dispositivo): solo «reproducir aquí» trae la música
            guard accion == "reproducir" || accion == "transferir" else {
                if porWS, let idOrden = o["id"] as? String { Task { await enviar(["tipo": "ack", "id": idOrden, "ok": true]) } }
                return
            }
            elegido = nil
            ignorado = r.remoto?.id
            r.salirDeRemoto(restaurar: false)
        }
        switch accion {
        case "play":
            if r.actual != nil { if !r.sonando { r.continuar() } } else { ok = false; error = "No hay nada para reproducir" }
        case "pause": if r.sonando { r.pausar() }
        case "toggle": r.alternar()
        case "next": r.siguiente()
        case "prev": r.anterior()
        case "seek": r.buscar(numero("segundo"))
        case "volumen": r.fijarVolumen(numero("volumen"))
        case "cola":
            r.reemplazarSiguientes((try? JSONDecoder().decode(OrdenCanciones.self, from: datos))?.canciones ?? [])
        case "reproducir", "transferir":
            if let canciones = (try? JSONDecoder().decode(OrdenCanciones.self, from: datos))?.canciones, !canciones.isEmpty {
                let indice = min(max(0, Int(numero("indice"))), canciones.count - 1)
                r.reproducir(canciones, desde: indice, contexto: "remoto")
                buscarCuandoEsteLista(numero("segundo"))
                Avisos.compartido.mostrar("Sonando aquí por orden de \(nombreDe(o["de"] as? String))")
            } else { ok = false; error = "No llegaron las canciones" }
        default:
            ok = false; error = "Orden desconocida"
        }
        if porWS, let idOrden = o["id"] as? String {
            var ack: [String: Any] = ["tipo": "ack", "id": idOrden, "ok": ok]
            if let error { ack["error"] = error }
            Task { await enviar(ack) }
        }
        Task { try? await Task.sleep(for: .milliseconds(300)); await informar(forzar: true) }
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

    private func confirmar(_ a: [String: Any]) {
        let ref = (a["id"] as? String).flatMap { refDeId.removeValue(forKey: $0) } ?? (a["ref"] as? String)
        guard let ref, let c = porRef.removeValue(forKey: ref) else { return }
        if a["ok"] as? Bool == false {
            c.resume(throwing: ErrorControl(mensaje: a["error"] as? String ?? "El dispositivo no pudo hacerlo"))
        } else {
            c.resume()
        }
    }

    private func fallarPendientes(_ motivo: String) {
        let pendientes = porRef
        porRef = [:]
        refDeId = [:]
        for c in pendientes.values { c.resume(throwing: ErrorControl(mensaje: motivo)) }
    }

    /// Envía una orden a otro dispositivo y espera su confirmación (máximo 5 s). «activo» = el que está sonando.
    func ordenar(_ dispositivo: String, _ accion: String, _ extra: [String: Any] = [:]) async throws {
        let ref = String(UUID().uuidString.prefix(8))
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            Task { @MainActor in self.registrarOrden(ref, c, dispositivo, accion, extra) }
        }
    }

    /// Guarda la espera de confirmación, envía la orden (WebSocket o HTTP) y pone el límite de 5 s.
    private func registrarOrden(_ ref: String, _ c: CheckedContinuation<Void, Error>, _ dispositivo: String,
                                _ accion: String, _ extra: [String: Any]) {
        porRef[ref] = c
        Task {
            var m = extra
            m["tipo"] = "orden"; m["ref"] = ref; m["para"] = dispositivo; m["accion"] = accion
            if conectadoEnVivo, await enviar(m) { return }
            // Sin WebSocket: por HTTP; la confirmación llega por los eventos en vivo («control-ack»)
            var cuerpo = extra
            cuerpo["accion"] = accion; cuerpo["de"] = id
            do {
                let r: OrdenEnviada = try await API.enviar("POST", "/api/control/\(API.segmento(dispositivo))/orden", cuerpo)
                if porRef[ref] != nil { refDeId[r.id] = ref }
            } catch {
                porRef.removeValue(forKey: ref)?.resume(throwing: error)
            }
        }
        Task {
            try? await Task.sleep(for: .seconds(5))
            if let pendiente = porRef.removeValue(forKey: ref) {
                refDeId = refDeId.filter { $0.value != ref }
                pendiente.resume(throwing: ErrorControl(mensaje: "El dispositivo no respondió"))
            }
        }
    }

    /// Trae a este iPhone lo que suena en otro dispositivo (desde el mismo segundo); el servidor pausa el otro.
    func escucharAqui(_ d: DispositivoRemoto) async {
        guard let c = d.cancion else { return }
        elegido = nil
        ignorado = d.id
        Reproductor.compartido.salirDeRemoto(restaurar: false)
        Reproductor.compartido.reproducir([c] + (d.cola ?? []), desde: 0, contexto: "remoto")
        buscarCuandoEsteLista(posicion(d))
        if d.sonando { try? await ordenar(d.id, "pause") }
    }

    /// Pasa lo que suena aquí (y lo que sigue en la cola) a otro dispositivo; este se pausa cuando el otro confirma.
    func pasarA(_ d: DispositivoRemoto) async throws {
        let r = Reproductor.compartido
        guard let actual = r.actual else { return }
        let lista = [actual] + r.siguientes.prefix(99)
        try await ordenar(d.id, "transferir", ["cancionIds": lista.map(\.id), "indice": 0, "segundo": r.tiempo])
        r.pausar()
        Avisos.compartido.mostrar("Ahora suena en \(d.nombre)")
    }
}
