import SwiftUI

/// Las dos corcheas del ícono de la app (en un cuadro de 100 × 100): plicas y barra como un solo trazo.
struct TrazoNota: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 100
        let o = CGPoint(x: rect.midX - 50 * s, y: rect.midY - 50 * s)
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: o.x + x * s, y: o.y + y * s) }
        var path = Path()
        path.move(to: p(40, 71))
        path.addLine(to: p(40, 30))
        path.addLine(to: p(77, 21))
        path.addLine(to: p(77, 63))
        return path
    }
}

/// Cabeza de una nota (óvalo inclinado, como en el ícono).
struct CabezaNota: Shape {
    var centro: CGPoint
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 100
        let o = CGPoint(x: rect.midX - 50 * s, y: rect.midY - 50 * s)
        let ancho = 21 * s, alto = 17 * s
        let elipse = Path(ellipseIn: CGRect(x: -ancho / 2, y: -alto / 2, width: ancho, height: alto))
        return elipse
            .applying(CGAffineTransform(rotationAngle: -.pi / 12))
            .applying(CGAffineTransform(translationX: o.x + centro.x * s, y: o.y + centro.y * s))
    }
}

/// Logo de SpaceMusic en blanco y negro: el mismo de la pantalla de inicio del iPhone.
/// `progreso` va de 0 a 1 para animarlo (primero el trazo, después las cabezas).
struct LogoSpaceMusic: View {
    var tamano: CGFloat = 96
    var trazo: CGFloat = 1
    var cabezas: CGFloat = 1
    var conFondo = true

    var body: some View {
        ZStack {
            if conFondo {
                RoundedRectangle(cornerRadius: tamano * 0.225, style: .continuous)
                    .fill(Color.black)
                RoundedRectangle(cornerRadius: tamano * 0.225, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.16), lineWidth: 1)
            }
            TrazoNota()
                .trim(from: 0, to: trazo)
                .stroke(Color.white, style: StrokeStyle(lineWidth: tamano * 0.07, lineCap: .round, lineJoin: .round))
            ForEach([CGPoint(x: 31.5, y: 72), CGPoint(x: 68.5, y: 64)], id: \.x) { c in
                CabezaNota(centro: c)
                    .fill(Color.white)
                    .scaleEffect(cabezas, anchor: UnitPoint(x: c.x / 100, y: c.y / 100))
                    .opacity(Double(min(1, cabezas * 1.5)))
            }
        }
        .frame(width: tamano, height: tamano)
        .accessibilityLabel("SpaceMusic")
    }
}

/// Animación al abrir la app: se dibuja la nota, aparecen las cabezas y el nombre, y se desvanece.
struct Bienvenida: View {
    var alTerminar: () -> Void
    @Environment(\.accessibilityReduceMotion) private var sinMovimiento
    @State private var trazo: CGFloat = 0
    @State private var cabezas: CGFloat = 0
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
                LogoSpaceMusic(tamano: 112, trazo: trazo, cabezas: cabezas)
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

    private func animar() async {
        if sinMovimiento {
            trazo = 1; cabezas = 1; nombre = true
            try? await Task.sleep(for: .milliseconds(450))
            withAnimation(.easeOut(duration: 0.25)) { salida = true }
            try? await Task.sleep(for: .milliseconds(260))
            alTerminar()
            return
        }
        try? await Task.sleep(for: .milliseconds(80))
        withAnimation(.easeInOut(duration: 0.6)) { trazo = 1; brillo = true }
        try? await Task.sleep(for: .milliseconds(420))
        withAnimation(.spring(response: 0.38, dampingFraction: 0.6)) { cabezas = 1 }
        try? await Task.sleep(for: .milliseconds(200))
        withAnimation(.easeOut(duration: 0.45)) { nombre = true }
        try? await Task.sleep(for: .milliseconds(700))
        withAnimation(.easeIn(duration: 0.35)) { salida = true }
        try? await Task.sleep(for: .milliseconds(360))
        alTerminar()
    }
}
