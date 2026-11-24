import SwiftUI

/// Logo oficial de Google (la «G» de cuatro colores), para el botón «Continuar con Google».
struct LogoGoogle: View {
    var tamano: CGFloat = 20

    var body: some View {
        Canvas { ctx, size in
            let k = size.width / 48
            ctx.fill(Path { p in
                p.move(to: CGPoint(x: 24.000 * k, y: 9.500 * k))
                p.addCurve(to: CGPoint(x: 33.210 * k, y: 13.100 * k), control1: CGPoint(x: 27.540 * k, y: 9.500 * k), control2: CGPoint(x: 30.710 * k, y: 10.720 * k))
                p.addLine(to: CGPoint(x: 40.060 * k, y: 6.250 * k))
                p.addCurve(to: CGPoint(x: 24.000 * k, y: 0.000 * k), control1: CGPoint(x: 35.900 * k, y: 2.380 * k), control2: CGPoint(x: 30.470 * k, y: 0.000 * k))
                p.addCurve(to: CGPoint(x: 2.560 * k, y: 13.220 * k), control1: CGPoint(x: 14.620 * k, y: 0.000 * k), control2: CGPoint(x: 6.510 * k, y: 5.380 * k))
                p.addLine(to: CGPoint(x: 10.540 * k, y: 19.410 * k))
                p.addCurve(to: CGPoint(x: 24.000 * k, y: 9.500 * k), control1: CGPoint(x: 12.430 * k, y: 13.720 * k), control2: CGPoint(x: 17.740 * k, y: 9.500 * k))
                p.closeSubpath()
            }, with: .color(Color(red: 0.918, green: 0.263, blue: 0.208)))
            ctx.fill(Path { p in
                p.move(to: CGPoint(x: 46.980 * k, y: 24.550 * k))
                p.addCurve(to: CGPoint(x: 46.600 * k, y: 20.000 * k), control1: CGPoint(x: 46.980 * k, y: 22.980 * k), control2: CGPoint(x: 46.830 * k, y: 21.460 * k))
                p.addLine(to: CGPoint(x: 24.000 * k, y: 20.000 * k))
                p.addLine(to: CGPoint(x: 24.000 * k, y: 29.020 * k))
                p.addLine(to: CGPoint(x: 36.940 * k, y: 29.020 * k))
                p.addCurve(to: CGPoint(x: 32.160 * k, y: 36.200 * k), control1: CGPoint(x: 36.360 * k, y: 31.980 * k), control2: CGPoint(x: 34.680 * k, y: 34.500 * k))
                p.addLine(to: CGPoint(x: 39.890 * k, y: 42.200 * k))
                p.addCurve(to: CGPoint(x: 46.980 * k, y: 24.550 * k), control1: CGPoint(x: 44.400 * k, y: 38.020 * k), control2: CGPoint(x: 46.980 * k, y: 31.840 * k))
                p.closeSubpath()
            }, with: .color(Color(red: 0.259, green: 0.522, blue: 0.957)))
            ctx.fill(Path { p in
                p.move(to: CGPoint(x: 10.530 * k, y: 28.590 * k))
                p.addCurve(to: CGPoint(x: 9.770 * k, y: 24.000 * k), control1: CGPoint(x: 10.050 * k, y: 27.140 * k), control2: CGPoint(x: 9.770 * k, y: 25.600 * k))
                p.addCurve(to: CGPoint(x: 10.530 * k, y: 19.410 * k), control1: CGPoint(x: 9.770 * k, y: 22.400 * k), control2: CGPoint(x: 10.040 * k, y: 20.860 * k))
                p.addLine(to: CGPoint(x: 2.550 * k, y: 13.220 * k))
                p.addCurve(to: CGPoint(x: 0.000 * k, y: 24.000 * k), control1: CGPoint(x: 0.920 * k, y: 16.460 * k), control2: CGPoint(x: 0.000 * k, y: 20.120 * k))
                p.addCurve(to: CGPoint(x: 2.560 * k, y: 34.780 * k), control1: CGPoint(x: 0.000 * k, y: 27.880 * k), control2: CGPoint(x: 0.920 * k, y: 31.540 * k))
                p.addLine(to: CGPoint(x: 10.530 * k, y: 28.590 * k))
                p.closeSubpath()
            }, with: .color(Color(red: 0.984, green: 0.737, blue: 0.020)))
            ctx.fill(Path { p in
                p.move(to: CGPoint(x: 24.000 * k, y: 48.000 * k))
                p.addCurve(to: CGPoint(x: 39.890 * k, y: 42.190 * k), control1: CGPoint(x: 30.480 * k, y: 48.000 * k), control2: CGPoint(x: 35.930 * k, y: 45.870 * k))
                p.addLine(to: CGPoint(x: 32.160 * k, y: 36.190 * k))
                p.addCurve(to: CGPoint(x: 24.000 * k, y: 38.490 * k), control1: CGPoint(x: 30.010 * k, y: 37.640 * k), control2: CGPoint(x: 27.240 * k, y: 38.490 * k))
                p.addCurve(to: CGPoint(x: 10.530 * k, y: 28.580 * k), control1: CGPoint(x: 17.740 * k, y: 38.490 * k), control2: CGPoint(x: 12.430 * k, y: 34.270 * k))
                p.addLine(to: CGPoint(x: 2.550 * k, y: 34.770 * k))
                p.addCurve(to: CGPoint(x: 24.000 * k, y: 48.000 * k), control1: CGPoint(x: 6.510 * k, y: 42.620 * k), control2: CGPoint(x: 14.620 * k, y: 48.000 * k))
                p.closeSubpath()
            }, with: .color(Color(red: 0.204, green: 0.659, blue: 0.325)))
        }
        .frame(width: tamano, height: tamano)
        .accessibilityHidden(true)
    }
}
