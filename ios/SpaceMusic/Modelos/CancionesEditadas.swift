import Observation

@MainActor @Observable
final class CancionesEditadas {
    static let compartidas = CancionesEditadas()
    private var valores: [Int: Cancion] = [:]
    func actual(_ cancion: Cancion) -> Cancion { valores[cancion.id] ?? cancion }
    func guardar(_ cancion: Cancion) { valores[cancion.id] = cancion }
    func limpiar() { valores = [:] }
}
