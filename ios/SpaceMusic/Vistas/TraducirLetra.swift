import SwiftUI
#if canImport(Translation)
import Translation
#endif

/// Traduce la letra con la traducción del propio iPhone (Apple Translation): no se manda la letra a otros servicios
/// y funciona sin conexión si el idioma está descargado (iOS lo ofrece la primera vez).
/// - iOS 18: la traducción aparece debajo de cada línea de la letra.
/// - iOS 17.4: se abre la hoja de traducción del sistema con la letra completa.
/// - Antes de iOS 17.4 no hay traductor (el botón no aparece).
struct TraducirLetra: ViewModifier {
    static var disponible: Bool {
        if #available(iOS 17.4, *) { return true }
        return false
    }

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.modifier(TraducirLineas())
        } else if #available(iOS 17.4, *) {
            content.modifier(TraducirHoja())
        } else {
            content
        }
    }
}

/// iOS 18: traduce todas las líneas de una vez y las muestra debajo de cada una.
@available(iOS 18.0, *)
private struct TraducirLineas: ViewModifier {
    @State private var letras = Letras.compartido
    @State private var configuracion: TranslationSession.Configuration?

    func body(content: Content) -> some View {
        content
            .translationTask(configuracion) { sesion in
                let (id, versos) = await MainActor.run { (Letras.compartido.cancionId, Letras.compartido.versos) }
                guard !versos.isEmpty else { return }
                let pedidos = versos.map { TranslationSession.Request(sourceText: $0.texto, clientIdentifier: String($0.id)) }
                do {
                    let respuestas = try await sesion.translations(from: pedidos)
                    var mapa: [Int: String] = [:]
                    for r in respuestas {
                        if let c = r.clientIdentifier, let i = Int(c) { mapa[i] = r.targetText }
                    }
                    await MainActor.run { Letras.compartido.ponerTraducciones(mapa, de: id) }
                } catch {
                    await MainActor.run { Letras.compartido.falloTraduccion("No se pudo traducir esta letra") }
                }
            }
            .onChange(of: letras.traduciendo) { _, activo in
                if activo { pedir() }
            }
            .onChange(of: letras.cancionId) { _, _ in
                if letras.traduciendo { pedir() }
            }
            .onChange(of: letras.versos.count) { _, _ in
                if letras.traduciendo && !letras.hayTraduccion { pedir() }
            }
    }

    private func pedir() {
        guard !letras.hayTraduccion else { return }
        // Del idioma de la letra (lo detecta el iPhone) al idioma del teléfono
        if configuracion == nil {
            configuracion = TranslationSession.Configuration(source: nil, target: Locale.current.language)
        } else {
            configuracion?.invalidate()
        }
    }
}

/// iOS 17.4: hoja de traducción del sistema con la letra completa.
@available(iOS 17.4, *)
private struct TraducirHoja: ViewModifier {
    @State private var letras = Letras.compartido

    func body(content: Content) -> some View {
        content.translationPresentation(
            isPresented: Binding(get: { letras.traduciendo }, set: { letras.traduciendo = $0 }),
            text: letras.versos.map(\.texto).joined(separator: "\n"))
    }
}
