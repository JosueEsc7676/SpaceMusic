import UIKit

/// Imagen de la pantalla bloqueada, con el diseño de la referencia (portada a pantalla completa y la letra encima
/// en letra manuscrita, centrada):
/// - la imagen es CUADRADA y grande: así iOS la pone a pantalla completa (una vertical la mostraba como tarjeta);
///   iOS recorta los lados y deja visible solo la columna central (ancho de pantalla ÷ alto), por eso la letra va
///   dentro de esa columna;
/// - la portada se ve nítida; abajo se oscurece poco a poco para que la letra y los controles se lean;
/// - la letra va centrada un poco debajo de la mitad, en letra manuscrita blanca con una sombra suave;
/// - entre versos, transición fluida: la línea anterior sube y se desvanece mientras la nueva entra desde abajo.
enum ArteBloqueo {
    /// La línea que suena (la siguiente ya no se dibuja: el diseño de referencia muestra solo una).
    typealias Letra = (actual: String, siguiente: String?)

    /// La portada ya preparada para la canción (se hace una vez por canción).
    final class Base: @unchecked Sendable {
        let fondo: UIImage
        /// Qué tan clara es la portada donde va la letra (0 oscura, 1 blanca): define cuánto se oscurece detrás.
        let luz: CGFloat
        init(fondo: UIImage, luz: CGFloat) { self.fondo = fondo; self.luz = luz }
    }

    /// Luz promedio de una zona de la imagen (0 a 1), leyendo una versión de 8 × 8 píxeles.
    private static func luz(de img: UIImage, zona: CGRect) -> CGFloat {
        let f = UIGraphicsImageRendererFormat.default()
        f.scale = 1
        let chica = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8), format: f).image { _ in
            let ex = 8 / zona.width, ey = 8 / zona.height
            img.draw(in: CGRect(x: -zona.minX * ex, y: -zona.minY * ey, width: img.size.width * ex, height: img.size.height * ey))
        }
        guard let cg = chica.cgImage, let datos = cg.dataProvider?.data, let p = CFDataGetBytePtr(datos) else { return 0.5 }
        let bpp = max(cg.bitsPerPixel / 8, 3)
        var suma = 0.0
        var n = 0.0
        for y in 0..<cg.height {
            for x in 0..<cg.width {
                let i = y * cg.bytesPerRow + x * bpp
                suma += (Double(p[i]) + Double(p[i + 1]) + Double(p[i + 2])) / (3 * 255)
                n += 1
            }
        }
        return n > 0 ? CGFloat(suma / n) : 0.5
    }

    /// Lado de la imagen cuadrada.
    static let lado: CGFloat = 1500
    static var tamano: CGSize { CGSize(width: lado, height: lado) }
    /// Parte del ancho que se ve a pantalla completa (ancho ÷ alto de la pantalla). Lo fija `ajustarPantalla()`.
    nonisolated(unsafe) static var visible: CGFloat = 0.462

    @MainActor static func ajustarPantalla() {
        let b = UIScreen.main.bounds.size
        let r = min(b.width, b.height) / max(1, max(b.width, b.height))
        visible = min(max(r, 0.42), 0.75)
    }

    private static var formato: UIGraphicsImageRendererFormat {
        let f = UIGraphicsImageRendererFormat.default()
        f.scale = 1
        f.opaque = true
        return f
    }

    private static func cubrir(_ tam: CGSize, en destino: CGRect) -> CGRect {
        guard tam.width > 0, tam.height > 0 else { return destino }
        let escala = max(destino.width / tam.width, destino.height / tam.height)
        let w = tam.width * escala, h = tam.height * escala
        return CGRect(x: destino.midX - w / 2, y: destino.midY - h / 2, width: w, height: h)
    }

    /// Portada nítida a pantalla completa con el degradado oscuro de abajo (fuera del hilo principal).
    static func base(_ portada: UIImage?) -> Base {
        let marco = CGRect(origin: .zero, size: tamano)
        let columna = lado * visible
        // Qué tan clara es la portada justo donde va la letra
        var claridad: CGFloat = 0
        if let portada {
            let solo = UIGraphicsImageRenderer(size: tamano, format: formato).image { _ in portada.draw(in: cubrir(portada.size, en: marco)) }
            claridad = luz(de: solo, zona: CGRect(x: (lado - columna) / 2, y: lado * 0.40, width: columna, height: lado * 0.26))
        }
        let fondo = UIGraphicsImageRenderer(size: tamano, format: formato).image { ctx in
            UIColor.black.setFill()
            ctx.fill(marco)
            if let portada {
                ctx.cgContext.interpolationQuality = .high
                portada.draw(in: cubrir(portada.size, en: marco))
            }
            // Sombra en las cuatro esquinas de lo que se ve (la columna central): una viñeta ovalada
            let c = ctx.cgContext
            let vineta = [UIColor(white: 0, alpha: 0).cgColor, UIColor(white: 0, alpha: 0).cgColor,
                          UIColor(white: 0, alpha: 0.38 + 0.2 * claridad).cgColor]
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: vineta as CFArray, locations: [0, 0.55, 1]) {
                c.saveGState()
                c.translateBy(x: lado / 2, y: lado * 0.5)
                c.scaleBy(x: columna * 0.62, y: lado * 0.62)
                c.drawRadialGradient(g, startCenter: .zero, startRadius: 0, endCenter: .zero, endRadius: 1, options: [.drawsAfterEndLocation])
                c.restoreGState()
            }
            // Arriba un velo muy leve; desde la mitad, cada vez más oscuro (como la referencia)
            let colores = [UIColor(white: 0, alpha: 0.10).cgColor, UIColor(white: 0, alpha: 0.12).cgColor,
                           UIColor(white: 0, alpha: 0.42).cgColor, UIColor(white: 0, alpha: 0.78).cgColor]
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colores as CFArray, locations: [0, 0.38, 0.62, 1]) {
                ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: lado), options: [])
            }
        }
        return Base(fondo: fondo, luz: claridad)
    }

    /// Letra manuscrita como la de la referencia (Savoye; si no está, Snell Roundhand).
    private static func manuscrita(_ tam: CGFloat) -> UIFont {
        UIFont(name: "SavoyeLetPlain", size: tam) ?? UIFont(name: "SnellRoundhand", size: tam * 0.8) ?? .italicSystemFont(ofSize: tam * 0.6)
    }

    /// Dibuja la línea centrada en la columna visible, un poco debajo de la mitad.
    private static func dibujar(_ texto: String, alfa: CGFloat, desplazamiento dy: CGFloat, luz: CGFloat) {
        guard alfa > 0.01, !texto.isEmpty else { return }
        let columna = lado * visible
        let ancho = columna * 0.86
        let x = (lado - ancho) / 2
        let estilo = NSMutableParagraphStyle()
        estilo.alignment = .center
        estilo.lineBreakMode = .byWordWrapping
        estilo.lineHeightMultiple = 0.82
        let sombra = NSShadow()
        sombra.shadowColor = UIColor(white: 0, alpha: (0.6 + 0.3 * luz) * alfa)
        sombra.shadowBlurRadius = 14
        sombra.shadowOffset = CGSize(width: 0, height: 2)
        func atributos(_ tam: CGFloat) -> [NSAttributedString.Key: Any] {
            [.font: manuscrita(tam), .foregroundColor: UIColor(white: 1, alpha: alfa), .paragraphStyle: estilo, .shadow: sombra]
        }
        func alto(_ a: [NSAttributedString.Key: Any]) -> CGFloat {
            ceil((texto as NSString).boundingRect(with: CGSize(width: ancho, height: .greatestFiniteMagnitude),
                                                  options: .usesLineFragmentOrigin, attributes: a, context: nil).height)
        }
        // Lo más grande posible sin pasar de 3 renglones (cada renglón mide como 0,95 del tamaño de letra)
        var tam: CGFloat = 132
        var a = atributos(tam)
        var h = alto(a)
        while (h > tam * 0.95 * 3 + 20 || h > lado * 0.3) && tam > 64 {
            tam -= 6
            a = atributos(tam)
            h = alto(a)
        }
        // Centro de la letra al 53 % del alto (debajo del sujeto de la portada y encima de los controles)
        let y = lado * 0.53 - h / 2 + dy
        // Halo oscuro y suave detrás de la letra: más fuerte cuanto más clara es la portada (álbumes claros)
        let fuerza = (0.28 + 0.5 * luz) * alfa
        if let c = UIGraphicsGetCurrentContext(), fuerza > 0.02,
           let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                              colors: [UIColor(white: 0, alpha: fuerza).cgColor, UIColor(white: 0, alpha: fuerza * 0.6).cgColor,
                                       UIColor(white: 0, alpha: 0).cgColor] as CFArray, locations: [0, 0.5, 1]) {
            c.saveGState()
            c.translateBy(x: lado / 2, y: y + h / 2)
            c.scaleBy(x: ancho * 0.72, y: max(h, lado * 0.08) * 1.25)
            c.drawRadialGradient(g, startCenter: .zero, startRadius: 0, endCenter: .zero, endRadius: 1, options: [])
            c.restoreGState()
        }
        (texto as NSString).draw(with: CGRect(x: x, y: y, width: ancho, height: h + 10),
                                 options: .usesLineFragmentOrigin, attributes: a, context: nil)
    }

    /// Un cuadro de la transición de `de` a `a` (p de 0 a 1).
    static func cuadro(_ base: Base, de: Letra?, a: Letra?, p: CGFloat) -> UIImage {
        let marco = CGRect(origin: .zero, size: tamano)
        let e = p * p * (3 - 2 * p) // suavizado (entra y sale despacio)
        return UIGraphicsImageRenderer(size: tamano, format: formato).image { _ in
            base.fondo.draw(in: marco)
            let salto = lado * 0.03
            if let de { dibujar(de.actual, alfa: 1 - e, desplazamiento: -salto * e, luz: base.luz) }
            if let a { dibujar(a.actual, alfa: e, desplazamiento: salto * (1 - e), luz: base.luz) }
        }
    }
}
