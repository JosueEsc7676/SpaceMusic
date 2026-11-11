import Foundation
import CryptoKit
import Network
import Observation

/// Copias privadas, separadas por servidor y cuenta; nunca guardan el token.
enum CopiaLocal {
    static func carpeta() -> URL? {
        guard let id = Sesion.compartida.usuario?.id else { return nil }
        let servidor = huella(Sesion.compartida.servidor)
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("Copias/\(servidor)/\(id)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var valores = URLResourceValues(); valores.isExcludedFromBackup = true
        var directorio = url; try? directorio.setResourceValues(valores)
        return url
    }
    static func huella(_ valor: String) -> String {
        SHA256.hash(data: Data(valor.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    static func archivo(_ clave: String) -> URL? { carpeta()?.appendingPathComponent(huella(clave) + ".json") }
    static func guardar(_ datos: Data, en url: URL?) {
        guard let url else { return }
        try? datos.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    static func borrar() { if let url = carpeta() { try? FileManager.default.removeItem(at: url) } }
    static func admite(_ ruta: String) -> Bool {
        ruta == "/api/inicio" || ruta == "/api/playlists" || ruta == "/api/albumes" ||
        ruta.hasPrefix("/api/usuario/favoritos") || ruta.hasPrefix("/api/usuario/guardados") ||
        ruta.hasPrefix("/api/mensajes") || ruta.hasPrefix("/api/letras/") ||
        ruta.hasPrefix("/api/playlists/") || ruta.hasPrefix("/api/albumes/detalle")
    }
}

@MainActor @Observable
final class Conexion {
    static let compartida = Conexion()
    private(set) var disponible = true
    var mostrandoCopia = false
    @ObservationIgnored private let monitor = NWPathMonitor()
    private init() {
        monitor.pathUpdateHandler = { camino in
            let disponible = camino.status == .satisfied
            Task { @MainActor in
                self.disponible = disponible
                if disponible { self.mostrandoCopia = false }
            }
        }
        monitor.start(queue: DispatchQueue(label: "SpaceMusic.conexion"))
    }
}

extension Notification.Name {
    static let cancionActualizada = Notification.Name("SpaceMusic.cancionActualizada")
}
