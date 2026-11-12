import SwiftUI

/// Colores de SpaceMusic (los mismos de la web: fondo negro y aurora lila/celeste/rosa).
enum Tema {
    static let fondo = Color.black
    static let elevado = Color(red: 0.043, green: 0.043, blue: 0.055)
    static let tarjeta = Color.white.opacity(0.06)
    static let linea = Color.white.opacity(0.08)
    static let texto = Color(red: 0.98, green: 0.98, blue: 0.98)
    static let secundario = Color.white.opacity(0.6)
    static let lila = Color(red: 0.706, green: 0.647, blue: 1.0)
    /// Color de acento elegido en «Color de la app» (los mismos de la web). Por defecto Cielo, el celeste de siempre.
    static var celeste: Color { acentos.first { $0.nombre == acentoGuardado }?.color ?? acentos[2].color }
    static let acentoPorDefecto = "Cielo"
    static var acentoGuardado: String { UserDefaults.standard.string(forKey: "acento") ?? acentoPorDefecto }
    static let acentos: [(nombre: String, color: Color)] = [
        ("Perla", Color(red: 0.961, green: 0.961, blue: 0.969)), ("Iris", Color(red: 0.706, green: 0.647, blue: 1.0)),
        ("Cielo", Color(red: 0.486, green: 0.784, blue: 1.0)), ("Rosa", Color(red: 1.0, green: 0.639, blue: 0.784)),
        ("Coral", Color(red: 1.0, green: 0.561, blue: 0.471)), ("Ámbar", Color(red: 0.957, green: 0.769, blue: 0.416)),
    ]
    static let rosa = Color(red: 1.0, green: 0.639, blue: 0.784)
    static let peligro = Color(red: 1.0, green: 0.42, blue: 0.42)

    static let aurora = LinearGradient(colors: [lila, celeste, rosa], startPoint: .leading, endPoint: .trailing)

    /// Paleta de géneros de la web (`GENRE_COLORS` en ui.js), en el mismo orden.
    private static let coloresGenero: [UInt32] = [0x6c5ce7, 0x3a7bd5, 0xc2547a, 0xd07a4a, 0x3c9d9b, 0x8e5bd1, 0xb8863b, 0x4d6fd8,
                                                  0xa34fa0, 0x5c8a6a, 0xc96a5b, 0x4b9ec9, 0x7d6bb8, 0xb0596f, 0x5e7fa8, 0x9a7a52]

    /// Mismo color que `genreColor(nombre)` de la web: hash ×31 sobre el nombre sin acentos y en minúsculas.
    /// Devuelve los componentes (0…1) para poder oscurecerlos como el `color-mix` de la web.
    static func colorGenero(_ nombre: String) -> (Double, Double, Double) {
        let normal = nombre.folding(options: .diacriticInsensitive, locale: nil).lowercased()
        var h: UInt32 = 0
        for escalar in normal.unicodeScalars {
            let codigo = escalar.value <= 0xFFFF ? escalar.value : (0xD800 + ((escalar.value - 0x10000) >> 10))
            h = h &* 31 &+ codigo
        }
        let c = coloresGenero[Int(h % UInt32(coloresGenero.count))]
        return (Double((c >> 16) & 0xff) / 255, Double((c >> 8) & 0xff) / 255, Double(c & 0xff) / 255)
    }
}

extension View {
    /// Título de sección.
    func titulo() -> some View {
        font(.system(.title3, design: .rounded).weight(.bold)).foregroundStyle(Tema.texto)
    }
}

/// Botón principal blanco, como en la web.
struct BotonPrincipal: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .padding(.horizontal, 22)
            .padding(.vertical, 12)
            .background(Capsule().fill(Color.white))
            .foregroundStyle(.black)
            .opacity(configuration.isPressed ? 0.75 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}

struct BotonSecundario: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .background(Capsule().strokeBorder(Color.white.opacity(0.25)))
            .foregroundStyle(Tema.texto)
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// Pantalla de carga, error o vacío.
struct EstadoVista: View {
    var icono = "music.note"
    let titulo: String
    var texto: String? = nil
    var reintentar: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icono).font(.system(size: 40)).foregroundStyle(Tema.secundario)
            Text(titulo).font(.headline)
            if let texto { Text(texto).font(.subheadline).foregroundStyle(Tema.secundario).multilineTextAlignment(.center) }
            if let reintentar {
                Button("Reintentar", action: reintentar).buttonStyle(BotonSecundario()).padding(.top, 4)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, minHeight: 300)
    }
}

struct Cargando: View {
    var body: some View {
        ProgressView().controlSize(.large).frame(maxWidth: .infinity, minHeight: 300)
    }
}

/// Aviso breve que aparece abajo ("Agregada a favoritos").
@MainActor
@Observable
final class Avisos {
    static let compartido = Avisos()
    private(set) var texto: String?
    /// Botón opcional del aviso (por ejemplo «Deshacer»).
    private(set) var accion: (titulo: String, hacer: () -> Void)?
    private var tarea: Task<Void, Never>?

    func mostrar(_ t: String, accion: (titulo: String, hacer: () -> Void)? = nil) {
        texto = t
        self.accion = accion
        tarea?.cancel()
        let espera: Double = accion == nil ? 2.2 : 6
        tarea = Task {
            try? await Task.sleep(for: .seconds(espera))
            if !Task.isCancelled { texto = nil; self.accion = nil }
        }
    }

    func ejecutarAccion() {
        let a = accion
        texto = nil; accion = nil; tarea?.cancel()
        a?.hacer()
    }

    func error(_ e: Error) {
        if e is CancellationError { return }
        mostrar(e.localizedDescription)
    }
}
