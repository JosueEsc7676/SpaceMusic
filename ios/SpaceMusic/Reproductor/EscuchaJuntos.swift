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
    /// Hora del servidor (ms) en la que vale «segundo».
    var ts: Double?
    var version: Int
    var hayAnterior: Bool
    /// Lo último que hizo alguien (para avisar «Ana pausó»).
    var ultimaAccion: [String: String]?
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0; dentro = c.bool("dentro") ?? false
        soyAnfitrion = c.bool("soyAnfitrion") ?? false; anfitrion = c.v("anfitrion"); cancion = c.v("cancion")
        segundo = c.numero("segundo") ?? 0; sonando = c.bool("sonando") ?? false
        personas = c.lista("personas"); invitados = c.lista("invitados"); cola = c.lista("cola")
        ts = c.numero("ts"); version = c.entero("version") ?? -1; hayAnterior = c.bool("hayAnterior") ?? false
        ultimaAccion = c.v("ultimaAccion")
    }
}

/// SyncPlay con el control compartido, igual que la web:
/// - El servidor guarda el estado (canción, segundo con su hora, pausa, cola, versión) y todos lo siguen.
/// - Cualquier participante controla: los botones del reproductor de la app (y los de la pantalla bloqueada) mandan
///   la orden al SyncPlay (reproducir, pausar, siguiente, anterior, mover la posición o poner otra canción).
/// - Sincronía fina con el reloj del servidor: desfases pequeños se corrigen con la velocidad (0,95–1,05),
///   los grandes con un salto.
@MainActor @Observable final class EscuchaJuntos {
    static let compartida = EscuchaJuntos()
    private(set) var sala: SalaJuntos?
    @ObservationIgnored private var revision: Task<Void, Never>?
    @ObservationIgnored private var aplicandoTarea: Task<Void, Never>?
    @ObservationIgnored private var version = -1
    /// Corrección de la sincronía (aprende cuánto tarda en volver a sonar tras un salto; sin saltos en bucle).
    @ObservationIgnored private var ajuste = AjusteSincronia()
    /// Versión del estado ya aplicada: solo un estado nuevo (alguien movió, reanudó…) provoca un salto inmediato.
    @ObservationIgnored private var aplicada = -1
    private var reloj: Double { ProcessInfo.processInfo.systemUptime }
    var enSyncPlay: Bool { sala?.dentro == true }

    // MARK: Crear e invitar

    func crear() async throws -> SalaJuntos {
        if let sala, sala.dentro {
            guard sala.soyAnfitrion else { throw ErrorAPI(mensaje: "Sal del SyncPlay actual antes de iniciar otro.", estado: 0) }
            return sala
        }
        let nueva: SalaJuntos = try await API.enviar("POST", "/api/juntos", [:])
        activar(nueva)
        return nueva
    }

    /// Invita mediante la API existente y deja en el chat un enlace que ambos clientes pueden abrir.
    func invitarPorMensaje(_ username: String) async throws -> (SalaJuntos, Mensaje) {
        let actual = try await crear()
        let invitada: SalaJuntos = try await API.enviar("POST", "/api/juntos", ["username": username])
        guard invitada.id == actual.id,
              let enlace = InvitacionSyncPlay.enlace(sala: actual.id, servidor: Sesion.compartida.servidor) else {
            throw ErrorAPI(mensaje: "No se pudo preparar el enlace de SyncPlay.", estado: 0)
        }
        recibir(invitada)
        let mensaje: Mensaje = try await API.enviar("POST", "/api/mensajes/\(API.segmento(username))",
            ["texto": "Te invito a escuchar conmigo en SyncPlay\n\(enlace.absoluteString)"])
        return (invitada, mensaje)
    }

    // MARK: Ciclo de vida

    func activar(_ s: SalaJuntos) {
        guard s.dentro else { return }
        if sala?.id != s.id { version = -1; aplicada = -1; ajuste = AjusteSincronia() }
        Reproductor.compartido.controlExterno = { [weak self] a in self?.interceptar(a) ?? false }
        Reproductor.compartido.bufferAmplio = true
        recibir(s)
        revision?.cancel()
        let cuenta = Sesion.compartida.usuario?.id
        revision = Task {
            var ciclos = 0
            while !Task.isCancelled, cuenta == Sesion.compartida.usuario?.id, sala?.id == s.id {
                afinar()
                if ciclos % 30 == 29 { // respaldo cada 15 s por si se perdió un aviso
                    do {
                        let nueva: SalaJuntos = try await API.get("/api/juntos/\(s.id)")
                        guard !Task.isCancelled, cuenta == Sesion.compartida.usuario?.id else { return }
                        recibir(nueva)
                    } catch let e as ErrorAPI where e.estado == 404 { detener(); return }
                    catch {}
                }
                ciclos += 1
                try? await Task.sleep(for: .milliseconds(500))
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
            detener(); Avisos.compartido.mostrar(j["motivo"] as? String ?? "Este SyncPlay terminó")
        }
    }

    func salir() async throws {
        guard let s = sala else { return }
        try await API.hacer("POST", "/api/juntos/\(s.id)/salir")
        detener()
    }

    func detener() {
        revision?.cancel(); revision = nil; aplicandoTarea?.cancel(); aplicandoTarea = nil
        sala = nil; version = -1
        let r = Reproductor.compartido
        r.controlExterno = nil
        r.bufferAmplio = false
        r.quitarPausaLocal()
        r.ajustarVelocidad(1)
    }

    // MARK: Seguir el estado del servidor

    /// Posición del SyncPlay ahora mismo con el reloj del servidor.
    func objetivo(_ s: SalaJuntos? = nil) -> Double {
        guard let s = s ?? sala else { return 0 }
        var t = s.segundo
        if s.sonando, let ts = s.ts { t += max(0, ControlRemoto.compartido.ahoraServidor - ts) / 1000 }
        if let d = s.cancion?.duracionSegundos, d > 0 { t = min(t, Double(d)) }
        return t
    }

    func recibir(_ s: SalaJuntos) {
        guard s.dentro else { detener(); return }
        if s.version >= 0 && s.version < version { return } // llegó tarde: ya hay uno más nuevo
        let anterior = sala
        version = max(version, s.version)
        sala = s
        aplicar()
        // Aviso de lo que hizo otra persona
        if let u = s.ultimaAccion, let quien = u["username"], !quien.isEmpty, quien != Sesion.compartida.usuario?.username,
           let a = anterior, a.version < s.version {
            let que = ["play": "reanudó", "pause": "pausó", "seek": "movió la canción", "next": "pasó a la siguiente",
                       "prev": "volvió a la anterior", "poner": "puso otra canción"][u["accion"] ?? ""]
            if let que { Avisos.compartido.mostrar("\(u["nombre"] ?? "Alguien") \(que)") }
        }
    }

    /// Pone el reproductor en el estado del SyncPlay (canción, pausa y posición).
    /// `forzar`: el cambio lo hizo esta persona ahora mismo (se aplica aunque la versión sea la misma).
    private func aplicar(forzar: Bool = false) {
        guard let s = sala else { return }
        let r = Reproductor.compartido
        guard let c = s.cancion else { r.sinControlExterno { r.pausar() }; return }
        let nuevo = forzar || s.version < 0 || s.version != aplicada
        aplicada = s.version
        if r.actual?.id != c.id {
            r.sinControlExterno { r.reproducir([c] + s.cola.compactMap(\.cancion), desde: 0, contexto: "juntos-oyente") }
            aplicandoTarea?.cancel()
            aplicandoTarea = Task {
                // El salto se hace cuando AVFoundation tiene el archivo preparado
                for _ in 0..<200 {
                    guard !Task.isCancelled, sala?.cancion?.id == c.id else { return }
                    if r.listaParaBuscar { break }
                    try? await Task.sleep(for: .milliseconds(100))
                }
                guard !Task.isCancelled, let actual = sala, actual.cancion?.id == c.id, r.listaParaBuscar else { return }
                // Se apunta un poco adelante (lo que tarda en empezar a sonar) y se mide para aprenderlo
                ajuste.trasCargar(ahora: reloj)
                r.sinControlExterno {
                    r.buscar(objetivo() + (actual.sonando ? ajuste.adelanto : 0))
                    if !actual.sonando || r.pausaLocal { r.pausar() }
                }
            }
            return
        }
        r.sinControlExterno {
            if s.sonando, !r.sonando, !r.pausaLocal { r.continuar() }
            else if !s.sonando, r.sonando { r.pausar() }
        }
        guard r.listaParaBuscar else { return }
        let desfase = r.tiempoExacto - objetivo()
        if !s.sonando {
            if abs(desfase) > 0.3 { r.sinControlExterno { r.buscar(objetivo()) } }
            return
        }
        // Un estado nuevo se sigue enseguida; el resto lo corrige afinar() sin saltar en bucle
        guard nuevo, r.sonando else { return }
        let o = ajuste.decidir(desfase: desfase, ahora: reloj, forzar: true)
        if let x = o.saltar { r.sinControlExterno { r.buscar(objetivo() + x) } }
    }

    /// Corrección fina cada medio segundo: velocidad para desfases medianos, salto (con espera) para los grandes.
    private func afinar() {
        guard let s = sala, s.sonando, let c = s.cancion else { return }
        let r = Reproductor.compartido
        guard !r.pausaLocal, r.actual?.id == c.id, r.sonando, r.listaParaBuscar else { r.ajustarVelocidad(1); return }
        // Acelerar pide más datos: solo si hay audio de sobra cargado (si no, AVPlayer se detiene a cargar)
        let o = ajuste.decidir(desfase: r.tiempoExacto - objetivo(), ahora: reloj, puedeAcelerar: r.bufferHolgado)
        r.ajustarVelocidad(o.velocidad)
        if let x = o.saltar { r.sinControlExterno { r.buscar(objetivo() + x) } }
    }

    // MARK: Control compartido

    /// Los botones del reproductor, dentro de un SyncPlay, mandan la orden al SyncPlay (para todos).
    private func interceptar(_ a: Reproductor.AccionExterna) -> Bool {
        guard let s = sala, s.dentro else { return false }
        let r = Reproductor.compartido
        switch a {
        case .poner(let canciones, let desde, _):
            guard canciones.indices.contains(desde) else { return true }
            let siguientes = canciones.dropFirst(desde + 1).prefix(20).map(\.id)
            control("poner", ["cancionId": canciones[desde].id, "cola": Array(siguientes)])
        case .continuar:
            if r.pausaLocal, s.sonando { r.quitarPausaLocal(); aplicar(forzar: true) } else { control("play") }
        case .pausar: control("pause")
        case .alternar:
            // En pausa solo aquí (llamada, audífonos…) mientras los demás siguen: vuelve a sonar al mismo punto
            if r.pausaLocal, s.sonando { r.quitarPausaLocal(); aplicar(forzar: true) } else { control(s.sonando ? "pause" : "play") }
        case .retomarLocal:
            r.quitarPausaLocal(); aplicar(forzar: true)
        case .siguiente:
            var datos: [String: Any] = [:]
            if let id = s.cancion?.id { datos["desde"] = id }
            control("next", datos)
        case .anterior: control("prev")
        case .buscar(let t): control("seek", ["segundo": t])
        }
        return true
    }

    /// Envía la orden. Pausa, reproducir y posición se aplican aquí al instante (el servidor lo confirma).
    func control(_ accion: String, _ datos: [String: Any] = [:]) {
        guard var s = sala else { return }
        if ["play", "pause", "seek"].contains(accion) {
            s.segundo = accion == "seek" ? ((datos["segundo"] as? Double) ?? 0) : objetivo(s)
            s.ts = ControlRemoto.compartido.ahoraServidor
            if accion != "seek" { s.sonando = accion == "play" }
            sala = s
            Reproductor.compartido.quitarPausaLocal()
            aplicar(forzar: true)
        }
        var cuerpo = datos
        cuerpo["accion"] = accion
        let id = s.id
        Task {
            do {
                let nueva: SalaJuntos = try await API.enviar("POST", "/api/juntos/\(id)/control", cuerpo)
                recibir(nueva)
            } catch let e as ErrorAPI where e.estado == 404 {
                detener(); Avisos.compartido.mostrar("Este SyncPlay terminó")
            } catch {
                Avisos.compartido.mostrar(error.localizedDescription)
            }
        }
    }
}
