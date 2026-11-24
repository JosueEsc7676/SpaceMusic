import Foundation

/// Reglas independientes de UIKit: nunca reconstruir videos para sincronizar letra.
enum PoliticaLetraBloqueo {
    static func usarVideo(pantallaCompleta: Bool, conLetra: Bool) -> Bool {
        pantallaCompleta && !conLetra
    }

    static func actualizar(palabras: Int, mostradas: Int, iluminar: Bool, vaiven: Bool,
                           ahora: Double, ultimo: Double) -> Bool {
        let pendiente = iluminar && palabras != mostradas
        return (pendiente || vaiven) && ahora - ultimo >= (iluminar ? 0.3 : 0.5)
    }
}
