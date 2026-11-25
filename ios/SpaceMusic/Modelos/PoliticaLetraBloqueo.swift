import Foundation

/// La portada completa está disponible tanto con letra como en Original.
enum PoliticaLetraBloqueo {
    static func usarVideo(pantallaCompleta: Bool, conLetra: Bool) -> Bool {
        pantallaCompleta
    }

    static func clavePortada(cancion: Int, fuente: String, estilo: String) -> String {
        "portada-estable|\(cancion)|\(fuente)|\(estilo)"
    }

    static func actualizar(palabras: Int, mostradas: Int, iluminar: Bool, vaiven: Bool,
                           ahora: Double, ultimo: Double) -> Bool {
        let pendiente = iluminar && palabras != mostradas
        return (pendiente || vaiven) && ahora - ultimo >= (iluminar ? 0.3 : 0.5)
    }
}
