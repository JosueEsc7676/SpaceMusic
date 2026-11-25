import Foundation

/// La portada completa está disponible tanto con letra como en Original.
enum PoliticaLetraBloqueo {
    static func usarVideo(pantallaCompleta: Bool, conLetra: Bool) -> Bool {
        pantallaCompleta
    }

    static func clavePortada(cancion: Int, fuente: String, estilo: String) -> String {
        "portada-estable|\(cancion)|\(fuente)|\(estilo)"
    }

    static func textoActividad(_ texto: String, bytes: Int) -> String {
        let limpio = texto.components(separatedBy: .controlCharacters).joined(separator: " ")
        var datos = Data(limpio.utf8.prefix(max(0, bytes)))
        while !datos.isEmpty {
            if let resultado = String(data: datos, encoding: .utf8) { return resultado }
            datos.removeLast()
        }
        return ""
    }

    static func actualizar(palabras: Int, mostradas: Int, iluminar: Bool, vaiven: Bool,
                           ahora: Double, ultimo: Double) -> Bool {
        let pendiente = iluminar && palabras != mostradas
        return (pendiente || vaiven) && ahora - ultimo >= (iluminar ? 0.3 : 0.5)
    }
}
