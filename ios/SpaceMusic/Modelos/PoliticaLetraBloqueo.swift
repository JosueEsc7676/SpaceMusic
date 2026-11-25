import Foundation

/// La portada completa está disponible tanto con letra como en Original.
enum PoliticaLetraBloqueo {
    static func usarVideo(pantallaCompleta: Bool, conLetra: Bool) -> Bool {
        pantallaCompleta
    }

    static func actualizar(palabras: Int, mostradas: Int, iluminar: Bool, vaiven: Bool,
                           ahora: Double, ultimo: Double) -> Bool {
        let pendiente = iluminar && palabras != mostradas
        return (pendiente || vaiven) && ahora - ultimo >= (iluminar ? 0.3 : 0.5)
    }
}

/// Una tarea antigua no puede sustituir un verso, canción o estilo más reciente.
struct ControlArteCompleto {
    private(set) var solicitud: String?
    mutating func pedir(_ clave: String) -> Bool {
        guard solicitud != clave else { return false }
        solicitud = clave
        return true
    }
    func vigente(_ clave: String) -> Bool { solicitud == clave }
    mutating func reiniciar() { solicitud = nil }
    mutating func reintentar(_ clave: String) { if vigente(clave) { reiniciar() } }
}
