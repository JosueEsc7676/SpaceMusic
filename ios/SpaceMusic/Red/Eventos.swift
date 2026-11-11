import Foundation
import Observation
import UserNotifications

/// Un mensaje que llegó por la conexión en vivo.
struct EventoMensaje {
    let con: String
    let nombre: String
    let mensaje: Mensaje
}

/// Conexión en vivo con el servidor (/api/eventos): mensajes, "Visto" y notificaciones al instante.
/// Además lleva el contador de mensajes y notificaciones sin leer.
@MainActor
@Observable
final class Eventos {
    static let compartido = Eventos()

    private(set) var mensajesSinLeer = 0
    private(set) var notificacionesSinLeer = 0
    /// Último mensaje recibido (las pantallas de chat lo escuchan).
    private(set) var ultimoMensaje: EventoMensaje?
    /// Usuario que vio los mensajes por última vez.
    private(set) var ultimoVisto: (por: String, cuando: Date)?
    /// Conversación abierta en pantalla (para no avisar de sus mensajes).
    var chatAbierto: String?
    var appActiva = true

    @ObservationIgnored private var tarea: Task<Void, Never>?
    @ObservationIgnored private var sondeo: Task<Void, Never>?
    @ObservationIgnored private let sesionSSE: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 90
        c.timeoutIntervalForResource = 24 * 3600
        return URLSession(configuration: c)
    }()

    func conectar() {
        guard tarea == nil, Sesion.compartida.conectado else { return }
        tarea = Task { await bucle() }
        Task { await actualizarResumen() }
        // Respaldo por si la conexión en vivo se corta
        sondeo?.cancel()
        sondeo = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                await actualizarResumen()
            }
        }
    }

    func desconectar() {
        tarea?.cancel()
        tarea = nil
        sondeo?.cancel()
        sondeo = nil
    }

    func limpiar() {
        desconectar()
        mensajesSinLeer = 0
        notificacionesSinLeer = 0
        ultimoMensaje = nil
        ultimoVisto = nil
        chatAbierto = nil
    }

    func actualizarResumen() async {
        guard Sesion.compartida.conectado,
              let r: ResumenAvisos = try? await API.get("/api/notificaciones/resumen", tiempo: 15) else { return }
        mensajesSinLeer = r.mensajes
        notificacionesSinLeer = r.notificaciones
    }

    func notificacionesLeidas() { notificacionesSinLeer = 0 }

    private func bucle() async {
        var espera: Double = 2
        while !Task.isCancelled, Sesion.compartida.conectado {
            do {
                var r = try API.peticion("/api/eventos", tiempo: 90)
                r.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                let (bytes, resp) = try await sesionSSE.bytes(for: r)
                guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                espera = 2
                var nombre = "message"
                var datos = ""
                for try await linea in bytes.lines {
                    if linea.hasPrefix("event:") {
                        nombre = linea.dropFirst(6).trimmingCharacters(in: .whitespaces)
                    } else if linea.hasPrefix("data:") {
                        datos += linea.dropFirst(5).trimmingCharacters(in: .whitespaces)
                        // El servidor envía cada evento en una sola línea de datos
                        procesar(nombre, datos)
                        nombre = "message"
                        datos = ""
                    }
                }
            } catch {
                if Task.isCancelled { break }
            }
            try? await Task.sleep(for: .seconds(espera))
            espera = min(espera * 2, 30)
        }
        tarea = nil
    }

    private func procesar(_ evento: String, _ datos: String) {
        let d = Data(datos.utf8)
        switch evento {
        case "mensaje":
            guard let json = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
                  let con = json["con"] as? String,
                  let mj = json["mensaje"],
                  let md = try? JSONSerialization.data(withJSONObject: mj),
                  let m = try? JSONDecoder().decode(Mensaje.self, from: md) else { return }
            let ev = EventoMensaje(con: con, nombre: json["nombre"] as? String ?? con, mensaje: m)
            ultimoMensaje = ev
            if !m.mio && chatAbierto != con {
                mensajesSinLeer += 1
                avisar(titulo: ev.nombre, texto: m.cancion.map { "🎵 \($0.nombre) · \($0.artista)" } ?? m.texto, ruta: "#/mensajes/\(con)")
            }
        case "visto":
            if let json = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let por = json["por"] as? String {
                ultimoVisto = (por, Date())
            }
        case "notificacion":
            Task { await actualizarResumen() }
        default:
            break
        }
    }

    /// Notificación del sistema cuando la app no está en pantalla.
    private func avisar(titulo: String, texto: String, ruta: String) {
        guard !appActiva else { return }
        let c = UNMutableNotificationContent()
        c.title = titulo
        c.body = texto
        c.sound = .default
        c.userInfo = ["ruta": ruta]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }

    static func pedirPermiso() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }
}
