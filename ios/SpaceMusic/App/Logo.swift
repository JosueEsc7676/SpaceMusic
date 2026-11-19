import SwiftUI

/// Logo de SpaceMusic en blanco y negro: círculo blanco con ondas de sonido negras
/// (el mismo de la web, Android y el ícono de la app).
struct LogoSpaceMusic: View {
    var tamano: CGFloat = 96
    /// Aparición del círculo (0 a 1).
    var disco: CGFloat = 1
    /// Altura de cada una de las 7 ondas (0 a 1); se anima para que crezcan.
    var ondas: [CGFloat] = Array(repeating: 1, count: 7)
    /// Las ondas se mueven suave todo el tiempo, como un ecualizador (inicio de sesión).
    var vivo = false

    @Environment(\.accessibilityReduceMotion) private var sinMovimiento

    // Medidas sobre un círculo de 352 (las mismas del SVG de la web)
    static let altos: [CGFloat] = [60, 136, 96, 188, 96, 136, 60]
    static let separacion: CGFloat = 42
    static let ancho: CGFloat = 26
    private static let periodos: [Double] = [0.85, 1.15, 1.0, 1.25, 0.95, 1.1, 0.9]

    var body: some View {
        if vivo && !sinMovimiento {
            TimelineView(.animation) { linea in
                dibujo(t: linea.date.timeIntervalSinceReferenceDate)
            }
        } else {
            dibujo(t: nil)
        }
    }

    private func dibujo(t: Double?) -> some View {
        let u = tamano / 352
        return ZStack {
            Circle()
                .fill(Color.white)
                .scaleEffect(disco)
                .opacity(Double(min(1, disco * 1.5)))
            HStack(spacing: (Self.separacion - Self.ancho) * u) {
                ForEach(0..<7, id: \.self) { i in
                    Capsule()
                        .fill(Color.black)
                        .frame(width: Self.ancho * u, height: Self.altos[i] * u)
                        .scaleEffect(x: 1, y: max(0.001, escala(i, t: t)), anchor: .center)
                }
            }
        }
        .frame(width: tamano, height: tamano)
        .accessibilityLabel("SpaceMusic")
    }

    private func escala(_ i: Int, t: Double?) -> CGFloat {
        let base = ondas.indices.contains(i) ? ondas[i] : 1
        guard let t else { return base }
        // Entre 0,6 y 1 de su altura, cada onda a su ritmo
        let fase = sin((t / Self.periodos[i]) * 2 * .pi + Double(i) * 0.9)
        return base * CGFloat(0.8 + 0.2 * fase)
    }
}

/// Retrasos para que las ondas crezcan desde el centro hacia afuera.
private let retrasoOnda: [Double] = [0.24, 0.16, 0.08, 0, 0.08, 0.16, 0.24]

/// Animación al abrir la app: aparece el círculo, las ondas crecen desde el centro, laten dos veces y se desvanece.
struct Bienvenida: View {
    var alTerminar: () -> Void
    @Environment(\.accessibilityReduceMotion) private var sinMovimiento
    @State private var disco: CGFloat = 0
    @State private var ondas: [CGFloat] = Array(repeating: 0, count: 7)
    @State private var nombre = false
    @State private var brillo = false
    @State private var salida = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            Circle()
                .fill(Color.white.opacity(brillo ? 0.10 : 0))
                .frame(width: 260, height: 260)
                .blur(radius: 70)
            VStack(spacing: 22) {
                LogoSpaceMusic(tamano: 112, disco: disco, ondas: ondas)
                    .scaleEffect(salida ? 1.08 : 1)
                Text("SpaceMusic")
                    .font(.system(size: 30, weight: .heavy, design: .rounded))
                    .tracking(nombre ? 0.5 : 6)
                    .foregroundStyle(.white)
                    .opacity(nombre ? 1 : 0)
                    .offset(y: nombre ? 0 : 8)
            }
        }
        .opacity(salida ? 0 : 1)
        .allowsHitTesting(!salida)
        .task { await animar() }
    }

    private func todas(_ v: CGFloat, animacion: (Int) -> Animation) {
        for i in 0..<7 { withAnimation(animacion(i)) { ondas[i] = v } }
    }

    private func animar() async {
        if sinMovimiento {
            disco = 1; ondas = Array(repeating: 1, count: 7); nombre = true
            try? await Task.sleep(for: .milliseconds(450))
            withAnimation(.easeOut(duration: 0.25)) { salida = true }
            try? await Task.sleep(for: .milliseconds(260))
            alTerminar()
            return
        }
        try? await Task.sleep(for: .milliseconds(80))
        withAnimation(.spring(response: 0.45, dampingFraction: 0.62)) { disco = 1; brillo = true }
        try? await Task.sleep(for: .milliseconds(180))
        todas(1) { .spring(response: 0.36, dampingFraction: 0.55).delay(retrasoOnda[$0]) }
        try? await Task.sleep(for: .milliseconds(320))
        withAnimation(.easeOut(duration: 0.45)) { nombre = true }
        try? await Task.sleep(for: .milliseconds(260))
        // Dos latidos, como un ecualizador
        for _ in 0..<2 {
            todas(0.55) { .easeInOut(duration: 0.17).delay(retrasoOnda[$0] / 2) }
            try? await Task.sleep(for: .milliseconds(190))
            todas(1) { .easeInOut(duration: 0.17).delay(retrasoOnda[$0] / 2) }
            try? await Task.sleep(for: .milliseconds(190))
        }
        try? await Task.sleep(for: .milliseconds(200))
        withAnimation(.easeIn(duration: 0.35)) { salida = true }
        try? await Task.sleep(for: .milliseconds(360))
        alTerminar()
    }
}
