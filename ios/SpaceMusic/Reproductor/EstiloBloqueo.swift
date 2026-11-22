import UIKit

/// Cómo se ve la pantalla bloqueada (se guarda en el iPhone). «Original» deja solo la portada, sin letra ni sombra.
struct EstiloBloqueo: Codable, Equatable, Sendable {
    enum Modo: String, Codable, CaseIterable, Sendable { case letra, original }
    enum Posicion: String, Codable, CaseIterable, Sendable { case abajo, centro, arriba }
    enum Fuente: String, Codable, CaseIterable, Sendable { case manuscrita, moderna, elegante, maquina, gruesa }
    enum ColorLetra: String, Codable, CaseIterable, Sendable { case blanco, crema, amarillo, rosa, celeste }

    var modo: Modo = .letra
    /// Dónde va la letra, siempre dentro de la portada y centrada.
    var posicion: Posicion = .abajo
    var fuente: Fuente = .manuscrita
    /// 0,7 a 1,5 del tamaño normal.
    var tamano: Double = 1
    var color: ColorLetra = .blanco
    /// Cada verso un poco movido e inclinado (siempre derecho y legible, y sincronizado).
    var desordenada = false
    /// Muestra también el verso siguiente, más pequeño y atenuado.
    var siguiente = false
    var mayusculas = false
    /// Desenfoque de la portada para leer mejor la letra (0 nítida … 1 muy borrosa).
    var desenfoque: Double = 0
    /// Oscurecido de la portada (0 nada … 1 fuerte).
    var sombra: Double = 0.5

    private static let clave = "estiloBloqueo"

    static var guardado: EstiloBloqueo {
        guard let d = UserDefaults.standard.data(forKey: clave), let e = try? JSONDecoder().decode(EstiloBloqueo.self, from: d) else { return EstiloBloqueo() }
        return e
    }

    func guardar() {
        if let d = try? JSONEncoder().encode(self) { UserDefaults.standard.set(d, forKey: Self.clave) }
    }

    // MARK: Textos para la pantalla de ajustes

    static func nombre(_ f: Fuente) -> String {
        switch f {
        case .manuscrita: "Manuscrita"
        case .moderna: "Moderna"
        case .elegante: "Elegante"
        case .maquina: "Máquina de escribir"
        case .gruesa: "Gruesa"
        }
    }

    static func nombre(_ p: Posicion) -> String {
        switch p { case .abajo: "Abajo"; case .centro: "Centro"; case .arriba: "Arriba" }
    }

    static func nombre(_ c: ColorLetra) -> String {
        switch c { case .blanco: "Blanco"; case .crema: "Crema"; case .amarillo: "Amarillo"; case .rosa: "Rosa"; case .celeste: "Celeste" }
    }

    static func uiColor(_ c: ColorLetra) -> UIColor {
        switch c {
        case .blanco: .white
        case .crema: UIColor(red: 1, green: 0.95, blue: 0.84, alpha: 1)
        case .amarillo: UIColor(red: 1, green: 0.86, blue: 0.3, alpha: 1)
        case .rosa: UIColor(red: 1, green: 0.55, blue: 0.75, alpha: 1)
        case .celeste: UIColor(red: 0.55, green: 0.85, blue: 1, alpha: 1)
        }
    }

    /// Tipografía y su tamaño máximo (cada una se ve distinta al mismo tamaño).
    static func fuente(_ f: Fuente, _ tam: CGFloat) -> UIFont {
        switch f {
        case .manuscrita:
            return UIFont(name: "SavoyeLetPlain", size: tam) ?? UIFont(name: "SnellRoundhand", size: tam * 0.8) ?? .italicSystemFont(ofSize: tam * 0.6)
        case .moderna:
            let base = UIFont.systemFont(ofSize: tam * 0.62, weight: .bold)
            if let d = base.fontDescriptor.withDesign(.rounded) { return UIFont(descriptor: d, size: tam * 0.62) }
            return base
        case .elegante:
            return UIFont(name: "Didot-Italic", size: tam * 0.7) ?? UIFont(name: "Georgia-Italic", size: tam * 0.66) ?? .italicSystemFont(ofSize: tam * 0.62)
        case .maquina:
            return UIFont(name: "AmericanTypewriter-Semibold", size: tam * 0.56) ?? .monospacedSystemFont(ofSize: tam * 0.52, weight: .semibold)
        case .gruesa:
            return UIFont(name: "AvenirNextCondensed-Heavy", size: tam * 0.7) ?? .systemFont(ofSize: tam * 0.62, weight: .black)
        }
    }

    /// Alto de un renglón respecto al tamaño pedido (para limitar a 3 renglones).
    static func renglon(_ f: Fuente) -> CGFloat {
        switch f { case .manuscrita: 0.95; case .moderna: 0.78; case .elegante: 0.9; case .maquina: 0.72; case .gruesa: 0.86 }
    }
}
