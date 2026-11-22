import CoreImage
import UIKit

/// Imagen de la pantalla bloqueada, con el diseño de la referencia (portada a pantalla completa y la letra encima
/// en letra manuscrita, centrada):
/// - la imagen es CUADRADA y grande: así iOS la pone a pantalla completa (una vertical la mostraba como tarjeta);
///   iOS recorta los lados y deja visible solo la columna central (ancho de pantalla ÷ alto), por eso la letra va
///   dentro de esa columna;
/// - la portada se ve nítida; abajo se oscurece poco a poco para que la letra y los controles se lean;
/// - la letra va dentro de la portada, centrada, en la parte de abajo (o donde se elija en `EstiloBloqueo`);
/// - entre versos, transición fluida: la línea anterior sube y se desvanece mientras la nueva entra desde abajo.
enum ArteBloqueo {
    /// La línea que suena (la siguiente ya no se dibuja: el diseño de referencia muestra solo una).
    typealias Letra = (actual: String, siguiente: String?)

    /// La portada ya preparada para la canción (se hace una vez por canción y estilo).
    final class Base: @unchecked Sendable {
        let fondo: UIImage
        /// Qué tan clara es la portada donde va la letra (0 oscura, 1 blanca): define cuánto se oscurece detrás.
        let luz: CGFloat
        let estilo: EstiloBloqueo
        init(fondo: UIImage, luz: CGFloat, estilo: EstiloBloqueo) { self.fondo = fondo; self.luz = luz; self.estilo = estilo }
    }

    /// Centro vertical de la letra según la posición elegida (siempre dentro de la portada).
    static func centroY(_ p: EstiloBloqueo.Posicion) -> CGFloat {
        switch p { case .abajo: lado * 0.79; case .centro: lado * 0.53; case .arriba: lado * 0.26 }
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

    /// Portada desenfocada (Core Image), del mismo tamaño.
    private static func desenfocar(_ img: UIImage, radio: CGFloat) -> UIImage {
        guard radio > 0.5, let cg = img.cgImage else { return img }
        let ci = CIImage(cgImage: cg)
        guard let f = CIFilter(name: "CIGaussianBlur") else { return img }
        f.setValue(ci.clampedToExtent(), forKey: kCIInputImageKey)
        f.setValue(radio, forKey: kCIInputRadiusKey)
        guard let salida = f.outputImage?.cropped(to: ci.extent),
              let hecho = CIContext(options: [.useSoftwareRenderer: false]).createCGImage(salida, from: ci.extent) else { return img }
        return UIImage(cgImage: hecho)
    }

    /// Filtro de color de la portada (blanco y negro, sepia o colores vivos).
    private static func filtrar(_ img: UIImage, _ f: EstiloBloqueo.Filtro) -> UIImage {
        guard f != .normal, let cg = img.cgImage else { return img }
        let ci = CIImage(cgImage: cg)
        let filtro: CIFilter?
        switch f {
        case .blancoNegro: filtro = CIFilter(name: "CIPhotoEffectMono")
        case .sepia:
            filtro = CIFilter(name: "CISepiaTone")
            filtro?.setValue(0.85, forKey: kCIInputIntensityKey)
        case .vivo:
            filtro = CIFilter(name: "CIColorControls")
            filtro?.setValue(1.45, forKey: kCIInputSaturationKey)
            filtro?.setValue(1.06, forKey: kCIInputContrastKey)
        case .normal: filtro = nil
        }
        guard let filtro else { return img }
        filtro.setValue(ci, forKey: kCIInputImageKey)
        guard let salida = filtro.outputImage,
              let hecho = CIContext(options: [.useSoftwareRenderer: false]).createCGImage(salida, from: ci.extent) else { return img }
        return UIImage(cgImage: hecho)
    }

    /// Portada a pantalla completa con el estilo elegido: desenfoque, oscurecido y, donde va la letra, un poco más
    /// de sombra para leerla (fuera del hilo principal).
    static func base(_ portada: UIImage?, estilo: EstiloBloqueo = .guardado) -> Base {
        let marco = CGRect(origin: .zero, size: tamano)
        let columna = lado * visible
        let cy = centroY(estilo.posicion)
        var claridad: CGFloat = 0
        var cuadrada: UIImage?
        if let portada {
            let solo = UIGraphicsImageRenderer(size: tamano, format: formato).image { _ in portada.draw(in: cubrir(portada.size, en: marco)) }
            claridad = luz(de: solo, zona: CGRect(x: (lado - columna) / 2, y: max(0, cy - lado * 0.13), width: columna, height: lado * 0.26))
            cuadrada = desenfocar(filtrar(solo, estilo.filtro), radio: CGFloat(estilo.desenfoque) * 42)
        }
        let s = CGFloat(estilo.sombra)
        let fondo = UIGraphicsImageRenderer(size: tamano, format: formato).image { ctx in
            UIColor.black.setFill()
            ctx.fill(marco)
            cuadrada?.draw(in: marco)
            // Oscurecido parejo de toda la portada (lo elige la persona; un poco más en portadas claras)
            ctx.cgContext.setFillColor(UIColor(white: 0, alpha: s * (0.22 + 0.3 * claridad)).cgColor)
            ctx.cgContext.fill(marco)
            // Franja más oscura donde va la letra, que se funde con el resto
            let a = s * (0.35 + 0.25 * claridad)
            let c0 = UIColor(white: 0, alpha: 0).cgColor, c1 = UIColor(white: 0, alpha: a).cgColor
            let (colores, lugares): ([CGColor], [CGFloat]) = {
                switch estilo.posicion {
                case .abajo: return ([c0, c0, c1, c1], [0, 0.5, 0.85, 1])
                case .arriba: return ([c1, c1, c0, c0], [0, 0.12, 0.5, 1])
                case .centro: return ([c0, c1, c0], [0.25, 0.53, 0.8])
                }
            }()
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colores as CFArray, locations: lugares) {
                ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: lado), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
            }
        }
        return Base(fondo: fondo, luz: claridad, estilo: estilo)
    }

    /// Número estable a partir del texto (el mismo verso cae siempre igual en «desordenada»).
    private static func semilla(_ t: String) -> CGFloat {
        var h: UInt32 = 2166136261
        for b in t.utf8 { h = (h ^ UInt32(b)) &* 16777619 }
        return CGFloat(h % 1000) / 1000
    }

    /// Dibuja el verso (y, si se pidió, el siguiente) centrado dentro de la portada en la posición elegida.
    private static func dibujar(_ letra: Letra, alfa: CGFloat, desplazamiento dy: CGFloat, base: Base) {
        let e = base.estilo
        let texto = e.mayusculas ? letra.actual.uppercased() : letra.actual
        guard alfa > 0.01, !texto.isEmpty else { return }
        let columna = lado * visible
        let ancho = columna * 0.86
        let estiloP = NSMutableParagraphStyle()
        estiloP.alignment = e.alineacion == .izquierda ? .left : e.alineacion == .derecha ? .right : .center
        estiloP.lineBreakMode = .byWordWrapping
        estiloP.lineHeightMultiple = (e.fuente == .manuscrita ? 0.82 : 0.95) * CGFloat(e.interlineado)
        let color = EstiloBloqueo.uiColor(e.color)
        let sombra = NSShadow()
        switch e.efecto {
        case .sombra:
            sombra.shadowColor = UIColor(white: 0, alpha: (0.55 + 0.3 * base.luz) * alfa)
            sombra.shadowBlurRadius = 14
            sombra.shadowOffset = CGSize(width: 0, height: 2)
        case .resplandor:
            sombra.shadowColor = color.withAlphaComponent(0.85 * alfa)
            sombra.shadowBlurRadius = 30
            sombra.shadowOffset = .zero
        case .ninguno:
            sombra.shadowColor = UIColor.clear
        }
        func atributos(_ tam: CGFloat, _ a: CGFloat) -> [NSAttributedString.Key: Any] {
            var r: [NSAttributedString.Key: Any] = [.font: EstiloBloqueo.fuente(e.fuente, tam), .foregroundColor: color.withAlphaComponent(a),
                                                   .paragraphStyle: estiloP, .shadow: sombra, .kern: tam * CGFloat(e.espaciado)]
            if e.contorno {
                // Ancho negativo: relleno y contorno a la vez
                r[.strokeColor] = UIColor(white: 0, alpha: 0.9 * a)
                r[.strokeWidth] = -3.5
            }
            return r
        }
        func alto(_ t: String, _ a: [NSAttributedString.Key: Any]) -> CGFloat {
            ceil((t as NSString).boundingRect(with: CGSize(width: ancho, height: .greatestFiniteMagnitude),
                                               options: .usesLineFragmentOrigin, attributes: a, context: nil).height)
        }
        // Lo más grande posible (según el tamaño elegido) sin pasar de 3 renglones
        let renglon = EstiloBloqueo.renglon(e.fuente)
        var tam: CGFloat = 132 * CGFloat(e.tamano)
        var a = atributos(tam, alfa)
        var h = alto(texto, a)
        while (h > tam * renglon * 3 + 20 || h > lado * 0.3) && tam > 50 {
            tam -= 6
            a = atributos(tam, alfa)
            h = alto(texto, a)
        }
        // Verso siguiente, chico y atenuado, debajo
        var sigTexto: String?
        var sigA: [NSAttributedString.Key: Any] = [:]
        var sigH: CGFloat = 0
        if e.siguiente, let s = letra.siguiente?.trimmingCharacters(in: .whitespaces), !s.isEmpty {
            sigTexto = e.mayusculas ? s.uppercased() : s
            sigA = atributos(max(40, tam * 0.5), alfa * 0.5)
            sigH = min(alto(sigTexto!, sigA), lado * 0.12)
        }
        let separacion: CGFloat = sigTexto == nil ? 0 : 18
        let total = h + separacion + sigH
        let anchoTexto = min(ancho, ceil((texto as NSString).boundingRect(with: CGSize(width: ancho, height: .greatestFiniteMagnitude),
                                                                            options: .usesLineFragmentOrigin, attributes: a, context: nil).width))
        // Centrado en la posición elegida y siempre dentro de la portada
        var y = centroY(e.posicion) - total / 2 + dy
        y = min(max(y, lado * 0.05), lado * 0.95 - total)
        let cx = lado / 2
        let c = UIGraphicsGetCurrentContext()
        // Fondo detrás de la letra: recuadro translúcido redondeado o banda de lado a lado
        if e.fondoLetra != .ninguno, let c {
            let margen: CGFloat = 36
            let x0: CGFloat
            switch e.alineacion {
            case .izquierda: x0 = cx - ancho / 2
            case .derecha: x0 = cx + ancho / 2 - anchoTexto
            case .centro: x0 = cx - anchoTexto / 2
            }
            let caja = e.fondoLetra == .banda
                ? CGRect(x: 0, y: y - margen, width: lado, height: total + margen * 2)
                : CGRect(x: x0 - margen, y: y - margen * 0.7, width: anchoTexto + margen * 2, height: total + margen * 1.4)
            c.saveGState()
            c.setFillColor(UIColor(white: 0, alpha: 0.38 * alfa).cgColor)
            c.addPath(UIBezierPath(roundedRect: caja, cornerRadius: e.fondoLetra == .banda ? 0 : 40).cgPath)
            c.fillPath()
            c.restoreGState()
        }
        c?.saveGState()
        if e.desordenada {
            // Movido e inclinado un poco (−4° a 4°), nunca al revés
            let r = semilla(letra.actual)
            let dx = (r - 0.5) * columna * 0.16
            let giro = (semilla(letra.actual + "·") - 0.5) * 8 * .pi / 180
            c?.translateBy(x: cx + dx, y: y + h / 2)
            c?.rotate(by: giro)
            c?.translateBy(x: -cx, y: -(y + h / 2))
        }
        (texto as NSString).draw(with: CGRect(x: cx - ancho / 2, y: y, width: ancho, height: h + 10),
                                 options: .usesLineFragmentOrigin, attributes: a, context: nil)
        c?.restoreGState()
        if let sigTexto {
            (sigTexto as NSString).draw(with: CGRect(x: cx - ancho / 2, y: y + h + separacion, width: ancho, height: sigH + 6),
                                        options: .usesLineFragmentOrigin, attributes: sigA, context: nil)
        }
    }

    /// Un cuadro de la transición de `de` a `a` (p de 0 a 1).
    static func cuadro(_ base: Base, de: Letra?, a: Letra?, p: CGFloat) -> UIImage {
        let marco = CGRect(origin: .zero, size: tamano)
        let e = p * p * (3 - 2 * p) // suavizado (entra y sale despacio)
        return UIGraphicsImageRenderer(size: tamano, format: formato).image { _ in
            base.fondo.draw(in: marco)
            let salto = lado * 0.03
            if let de { dibujar(de, alfa: 1 - e, desplazamiento: -salto * e, base: base) }
            if let a { dibujar(a, alfa: e, desplazamiento: salto * (1 - e), base: base) }
        }
    }
}
