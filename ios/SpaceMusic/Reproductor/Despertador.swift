import Foundation
import Observation
import UserNotifications

@MainActor @Observable final class Despertador {
    static let compartido = Despertador()
    struct Plan: Codable {
        var fecha: Date
        var fuente: String
        var subida: Int
        var aleatorio: Bool
        var cuenta: Int
        var servidor: String
    }
    private(set) var plan: Plan?
    @ObservationIgnored private var tarea: Task<Void, Never>?
    private init() {
        if let d = UserDefaults.standard.data(forKey: "despertador"), let p = try? JSONDecoder().decode(Plan.self, from: d) { plan = p }
    }
    func programar(_ p: Plan) async throws {
        let centro = UNUserNotificationCenter.current()
        guard try await centro.requestAuthorization(options: [.alert, .sound]) else {
            throw ErrorAPI(mensaje: "Activa las notificaciones en Ajustes para usar el despertador.", estado: 0)
        }
        let contenido = UNMutableNotificationContent()
        contenido.title = "Despertador de SpaceMusic"
        contenido.body = "Toca para escuchar tu música."
        contenido.sound = .default
        contenido.userInfo = ["ruta": "#/despertador"]
        let intervalo = max(1, p.fecha.timeIntervalSinceNow)
        try await centro.add(UNNotificationRequest(identifier: "despertador", content: contenido, trigger: UNTimeIntervalNotificationTrigger(timeInterval: intervalo, repeats: false)))
        plan = p
        UserDefaults.standard.set(try JSONEncoder().encode(p), forKey: "despertador")
        vigilar()
    }
    func vigilar() {
        tarea?.cancel()
        guard let p = plan, p.cuenta == Sesion.compartida.usuario?.id, p.servidor == Sesion.compartida.servidor else { return }
        tarea = Task {
            if p.fecha > Date() { try? await Task.sleep(for: .seconds(p.fecha.timeIntervalSinceNow)) }
            guard !Task.isCancelled, p.fecha.timeIntervalSinceNow > -300 else { return }
            await sonar()
        }
    }
    func sonar() async {
        guard let p = plan, p.cuenta == Sesion.compartida.usuario?.id, p.servidor == Sesion.compartida.servidor else { return }
        plan = nil; UserDefaults.standard.removeObject(forKey: "despertador")
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["despertador"])
        do {
            let canciones: [Cancion]
            if p.fuente == "favoritos" { canciones = try await API.get("/api/usuario/favoritos") }
            else { let lista: Playlist = try await API.get("/api/playlists/\(p.fuente)"); canciones = lista.canciones }
            guard p.cuenta == Sesion.compartida.usuario?.id, p.servidor == Sesion.compartida.servidor else { return }
            guard !canciones.isEmpty else { throw ErrorAPI(mensaje: "La lista del despertador está vacía.", estado: 0) }
            Reproductor.compartido.reproducir(canciones, contexto: "alarma", mezclar: p.aleatorio)
            Reproductor.compartido.subirVolumenDurante(p.subida * 60)
        } catch { Avisos.compartido.error(error) }
    }
    func cancelar() {
        tarea?.cancel(); tarea = nil; plan = nil
        UserDefaults.standard.removeObject(forKey: "despertador")
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["despertador"])
    }
}
