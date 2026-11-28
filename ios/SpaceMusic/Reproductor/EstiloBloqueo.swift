import UIKit

/// Cómo se ve la pantalla bloqueada (se guarda en el iPhone). «Original» deja solo la portada, sin letra ni sombra.
struct EstiloBloqueo: Codable, Equatable, Sendable {
    enum Modo: String, Codable, CaseIterable, Sendable { case letra, original }
    enum Posicion: String, Codable, CaseIterable, Sendable { case abajo, centro, arriba, flotante }
    enum Fuente: String, Codable, CaseIterable, Sendable {
        case manuscrita, moderna, elegante, maquina, gruesa
        // 3.23: modernas y extravagantes, y una hecha a mano con estilo
        case futura, clarendon, rockwell, copperplate, aMano
    }
    enum ColorLetra: String, Codable, CaseIterable, Sendable { case blanco, crema, amarillo, rosa, celeste }
    enum Alineacion: String, Codable, CaseIterable, Sendable { case centro, izquierda, derecha }
    enum Efecto: String, Codable, CaseIterable, Sendable { case sombra, resplandor, ninguno }
    enum FondoLetra: String, Codable, CaseIterable, Sendable { case ninguno, caja, banda }
    enum Filtro: String, Codable, CaseIterable, Sendable { case normal, blancoNegro, sepia, vivo }

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
    /// Alineación del verso (siempre dentro de la portada).
    var alineacion: Alineacion = .centro
    /// Sombra oscura, resplandor del color de la letra o nada.
    var efecto: Efecto = .sombra
    /// Contorno oscuro alrededor de cada letra (se lee sobre cualquier portada).
    var contorno = false
    /// Recuadro translúcido detrás del verso o una banda de lado a lado.
    var fondoLetra: FondoLetra = .ninguno
    /// Espacio entre renglones (0,8 a 1,4).
    var interlineado: Double = 1
    /// Espacio entre letras (0 a 0,2 del tamaño).
    var espaciado: Double = 0
    /// Filtro de la portada: normal, blanco y negro, sepia o colores vivos.
    var filtro: Filtro = .normal
    /// Cada palabra se ilumina cuando suena (karaoke); las que faltan quedan atenuadas.
    var iluminar = false
    /// Con «Flotante» la letra además se mece suavemente mientras suena.
    var mecer = true
    /// Imagen de más resolución para verla nítida a pantalla completa (al tocar la portada en la pantalla bloqueada).
    var pantallaCompleta = false
    /// Color elegido libremente (#RRGGBB); si está, reemplaza al de la lista.
    var colorLibre: String? = nil

    init() {}

    /// El color con el que se dibuja la letra.
    var colorLetra: UIColor {
        if let h = colorLibre, h.count == 7, h.hasPrefix("#"), let v = UInt32(h.dropFirst(), radix: 16) {
            return UIColor(red: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255, blue: CGFloat(v & 0xff) / 255, alpha: 1)
        }
        return EstiloBloqueo.uiColor(color)
    }

    /// Lee lo guardado aunque falten opciones nuevas (cada una toma su valor normal).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = EstiloBloqueo.base
        modo = (try? c.decodeIfPresent(Modo.self, forKey: .modo)) ?? d.modo
        posicion = (try? c.decodeIfPresent(Posicion.self, forKey: .posicion)) ?? d.posicion
        fuente = (try? c.decodeIfPresent(Fuente.self, forKey: .fuente)) ?? d.fuente
        tamano = (try? c.decodeIfPresent(Double.self, forKey: .tamano)) ?? d.tamano
        color = (try? c.decodeIfPresent(ColorLetra.self, forKey: .color)) ?? d.color
        desordenada = (try? c.decodeIfPresent(Bool.self, forKey: .desordenada)) ?? d.desordenada
        siguiente = (try? c.decodeIfPresent(Bool.self, forKey: .siguiente)) ?? d.siguiente
        mayusculas = (try? c.decodeIfPresent(Bool.self, forKey: .mayusculas)) ?? d.mayusculas
        desenfoque = (try? c.decodeIfPresent(Double.self, forKey: .desenfoque)) ?? d.desenfoque
        sombra = (try? c.decodeIfPresent(Double.self, forKey: .sombra)) ?? d.sombra
        alineacion = (try? c.decodeIfPresent(Alineacion.self, forKey: .alineacion)) ?? d.alineacion
        efecto = (try? c.decodeIfPresent(Efecto.self, forKey: .efecto)) ?? d.efecto
        contorno = (try? c.decodeIfPresent(Bool.self, forKey: .contorno)) ?? d.contorno
        fondoLetra = (try? c.decodeIfPresent(FondoLetra.self, forKey: .fondoLetra)) ?? d.fondoLetra
        interlineado = (try? c.decodeIfPresent(Double.self, forKey: .interlineado)) ?? d.interlineado
        espaciado = (try? c.decodeIfPresent(Double.self, forKey: .espaciado)) ?? d.espaciado
        filtro = (try? c.decodeIfPresent(Filtro.self, forKey: .filtro)) ?? d.filtro
        iluminar = (try? c.decodeIfPresent(Bool.self, forKey: .iluminar)) ?? d.iluminar
        mecer = (try? c.decodeIfPresent(Bool.self, forKey: .mecer)) ?? d.mecer
        pantallaCompleta = (try? c.decodeIfPresent(Bool.self, forKey: .pantallaCompleta)) ?? d.pantallaCompleta
        colorLibre = try? c.decodeIfPresent(String.self, forKey: .colorLibre)
    }

    private static let base = EstiloBloqueo()

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
        case .futura: "Futura condensada"
        case .clarendon: "Clarendon"
        case .rockwell: "Rockwell"
        case .copperplate: "Copperplate"
        case .aMano: "Hecha a mano"
        }
    }

    static func nombre(_ p: Posicion) -> String {
        switch p { case .abajo: "Abajo"; case .centro: "Centro"; case .arriba: "Arriba"; case .flotante: "Flotante" }
    }

    static func nombre(_ a: Alineacion) -> String {
        switch a { case .centro: "Centro"; case .izquierda: "Izquierda"; case .derecha: "Derecha" }
    }

    static func nombre(_ e: Efecto) -> String {
        switch e { case .sombra: "Sombra"; case .resplandor: "Resplandor"; case .ninguno: "Ninguno" }
    }

    static func nombre(_ f: FondoLetra) -> String {
        switch f { case .ninguno: "Sin fondo"; case .caja: "Recuadro"; case .banda: "Banda" }
    }

    static func nombre(_ f: Filtro) -> String {
        switch f { case .normal: "Normal"; case .blancoNegro: "Blanco y negro"; case .sepia: "Sepia"; case .vivo: "Vivo" }
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
        case .futura:
            return UIFont(name: "Futura-CondensedExtraBold", size: tam * 0.72) ?? .systemFont(ofSize: tam * 0.62, weight: .heavy)
        case .clarendon:
            return UIFont(name: "Superclarendon-Black", size: tam * 0.5) ?? UIFont(name: "Georgia-Bold", size: tam * 0.56) ?? .systemFont(ofSize: tam * 0.52, weight: .black)
        case .rockwell:
            return UIFont(name: "Rockwell-Bold", size: tam * 0.6) ?? .systemFont(ofSize: tam * 0.56, weight: .bold)
        case .copperplate:
            return UIFont(name: "Copperplate-Bold", size: tam * 0.58) ?? .systemFont(ofSize: tam * 0.54, weight: .semibold)
        case .aMano:
            return UIFont(name: "BradleyHandITCTT-Bold", size: tam * 0.68) ?? UIFont(name: "Noteworthy-Bold", size: tam * 0.6) ?? .italicSystemFont(ofSize: tam * 0.6)
        }
    }

    /// Alto de un renglón respecto al tamaño pedido (para limitar a 3 renglones).
    static func renglon(_ f: Fuente) -> CGFloat {
        switch f {
        case .manuscrita: 0.95; case .moderna: 0.78; case .elegante: 0.9; case .maquina: 0.72; case .gruesa: 0.86
        case .futura: 0.86; case .clarendon: 0.7; case .rockwell: 0.74; case .copperplate: 0.66; case .aMano: 0.86
        }
    }
}
