import Foundation
import Network

/// Contrato de la web 3.5: `calidad=ligera` solicita AAC preparado por el servidor.
final class CalidadAudio: @unchecked Sendable {
    static let compartida = CalidadAudio()
    private let monitor = NWPathMonitor()
    private let cerrojo = NSLock()
    private var redLigera = false
    private init() {
        monitor.pathUpdateHandler = { [weak self] ruta in
            guard let self else { return }
            cerrojo.lock()
            redLigera = ruta.usesInterfaceType(.cellular) || ruta.isConstrained || ruta.isExpensive
            cerrojo.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "spacemusic.calidad"))
    }
    var ligera: Bool {
        let preferencia = UserDefaults.standard.string(forKey: "calidadAudio") ?? "auto"
        if preferencia != "auto" { return preferencia == "ligera" }
        cerrojo.lock(); defer { cerrojo.unlock() }
        return redLigera
    }
}
