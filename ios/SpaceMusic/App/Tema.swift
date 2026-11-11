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
    static let celeste = Color(red: 0.486, green: 0.784, blue: 1.0)
    static let rosa = Color(red: 1.0, green: 0.639, blue: 0.784)
    static let peligro = Color(red: 1.0, green: 0.42, blue: 0.42)

    static let aurora = LinearGradient(colors: [lila, celeste, rosa], startPoint: .leading, endPoint: .trailing)
}

/// Espacio al final de las listas para que el mini reproductor no tape nada.
struct EspacioReproductor: ViewModifier {
    var activo = true
    @State private var reproductor = Reproductor.compartido
    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            Color.clear.frame(height: reproductor.actual == nil || !activo ? 0 : 68)
        }
    }
}

extension View {
    func espacioReproductor(activo: Bool = true) -> some View { modifier(EspacioReproductor(activo: activo)) }

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
    private var tarea: Task<Void, Never>?

    func mostrar(_ t: String) {
        texto = t
        tarea?.cancel()
        tarea = Task {
            try? await Task.sleep(for: .seconds(2.2))
            if !Task.isCancelled { texto = nil }
        }
    }

    func error(_ e: Error) {
        if e is CancellationError { return }
        mostrar(e.localizedDescription)
    }
}
