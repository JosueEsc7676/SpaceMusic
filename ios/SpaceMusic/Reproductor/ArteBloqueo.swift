import CoreImage
import UIKit

/// Imagen de la pantalla bloqueada con la forma del iPhone (vertical, a pantalla completa):
/// - sin letra: la portada nítida ocupando toda la pantalla; si la portada es pequeña (menos de 900 px) no se estira
///   (se vería pixelada): va como tarjeta redondeada sobre la misma portada desenfocada;
/// - con letra: la portada se desenfoca (no desaparece) y la letra va en la franja central, entre el reloj y los
///   controles, que siempre se ve; la línea que suena grande y la siguiente atenuada;
/// - entre versos, transición fluida: la línea anterior sube y se desvanece mientras la nueva entra desde abajo.
enum ArteBloqueo {
    /// Una línea de la letra y la que sigue.
    typealias Letra = (actual: String, siguiente: String?)

    /// Las dos versiones de la portada ya preparadas para la canción (se hacen una vez por canción).
    final class Base: @unchecked Sendable {
        let nitida: UIImage
        let borrosa: UIImage
        init(nitida: UIImage, borrosa: UIImage) { self.nitida = nitida; self.borrosa = borrosa }
    }

    /// Tamaño con la proporción de la pantalla de este iPhone (1000 px de ancho). Lo fija `ajustarPantalla()`.
    nonisolated(unsafe) static var tamano = CGSize(width: 1000, height: 2165)

    /// Toma la proporción de la pantalla (se llama una vez desde el hilo principal).
    @MainActor static func ajustarPantalla() {
        let b = UIScreen.main.bounds.size
        let proporcion = max(b.width, b.height) / max(1, min(b.width, b.height))
        tamano = CGSize(width: 1000, height: (1000 * min(max(proporcion, 1.6), 2.3)).rounded())
    }

    private static let contexto = CIContext(options: [.useSoftwareRenderer: false])

    private static var formato: UIGraphicsImageRendererFormat {
        let f = UIGraphicsImageRendererFormat.default()
        f.scale = 1
        f.opaque = true
        return f
    }

    /// Rectángulo que cubre `destino` manteniendo la proporción de `tam` (como «aspect fill»).
    private static func cubrir(_ tam: CGSize, en destino: CGRect) -> CGRect {
        guard tam.width > 0, tam.height > 0 else { return destino }
        let escala = max(destino.width / tam.width, destino.height / tam.height)
        let w = tam.width * escala, h = tam.height * escala
        return CGRect(x: destino.midX - w / 2, y: destino.midY - h / 2, width: w, height: h)
    }

    /// Prepara la portada nítida y la desenfocada (fuera del hilo principal).
    static func base(_ portada: UIImage?) -> Base {
        let t = tamano
        let marco = CGRect(origin: .zero, size: t)
        guard let portada, portada.size.width > 0 else {
            let negro = UIGraphicsImageRenderer(size: t, format: formato).image { ctx in UIColor.black.setFill(); ctx.fill(marco) }
            return Base(nitida: negro, borrosa: negro)
        }
        // Desenfoque: la portada reducida a 160 px y desenfocada con Core Image (rápido y suave)
        let chica = UIGraphicsImageRenderer(size: CGSize(width: 160, height: 160 * portada.size.height / portada.size.width),
                                            format: formato).image { _ in
            portada.draw(in: CGRect(x: 0, y: 0, width: 160, height: 160 * portada.size.height / portada.size.width))
        }
        var desenfocada = chica
        if let ci = CIImage(image: chica) {
            let salida = ci.clampedToExtent().applyingGaussianBlur(sigma: 9).cropped(to: ci.extent)
            if let cg = contexto.createCGImage(salida, from: ci.extent) { desenfocada = UIImage(cgImage: cg) }
        }
        let borrosa = UIGraphicsImageRenderer(size: t, format: formato).image { ctx in
            UIColor.black.setFill()
            ctx.fill(marco)
            desenfocada.draw(in: cubrir(desenfocada.size, en: marco))
            // Más oscuro en la franja de la letra para que siempre se lea, sea cual sea la portada
            let colores = [UIColor(white: 0, alpha: 0.30).cgColor, UIColor(white: 0, alpha: 0.55).cgColor, UIColor(white: 0, alpha: 0.42).cgColor]
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colores as CFArray, locations: [0, 0.5, 1]) {
                ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: t.height), options: [])
            }
        }
        let lado = min(portada.size.width * portada.scale, portada.size.height * portada.scale)
        let nitida: UIImage
        if lado >= 900 {
            // Portada grande: nítida a pantalla completa
            nitida = UIGraphicsImageRenderer(size: t, format: formato).image { ctx in
                UIColor.black.setFill()
                ctx.fill(marco)
                portada.draw(in: cubrir(portada.size, en: marco))
            }
        } else {
            // Portada pequeña: tarjeta centrada sobre la misma portada desenfocada (no se ve pixelada ni mal colocada)
            nitida = UIGraphicsImageRenderer(size: t, format: formato).image { ctx in
                borrosa.draw(in: marco)
                let ancho = t.width * 0.74
                let tarjeta = CGRect(x: (t.width - ancho) / 2, y: t.height * 0.47 - ancho / 2, width: ancho, height: ancho)
                let c = ctx.cgContext
                c.saveGState()
                c.setShadow(offset: CGSize(width: 0, height: 18), blur: 50, color: UIColor(white: 0, alpha: 0.55).cgColor)
                UIColor.black.setFill()
                UIBezierPath(roundedRect: tarjeta, cornerRadius: 34).fill()
                c.restoreGState()
                c.saveGState()
                UIBezierPath(roundedRect: tarjeta, cornerRadius: 34).addClip()
                portada.draw(in: cubrir(portada.size, en: tarjeta))
                c.restoreGState()
            }
        }
        return Base(nitida: nitida, borrosa: borrosa)
    }

    private static func fuente(_ tam: CGFloat, _ peso: UIFont.Weight) -> UIFont {
        let f = UIFont.systemFont(ofSize: tam, weight: peso)
        return f.fontDescriptor.withDesign(.rounded).map { UIFont(descriptor: $0, size: tam) } ?? f
    }

    /// Dibuja la letra en la franja central (entre el reloj y los controles de la pantalla bloqueada).
    private static func dibujar(_ l: Letra, alfa: CGFloat, desplazamiento dy: CGFloat) {
        guard alfa > 0.01 else { return }
        let t = tamano
        let margen = t.width * 0.085
        let ancho = t.width - margen * 2
        let arriba = t.height * 0.355, abajo = t.height * 0.625
        let alto = abajo - arriba
        let estilo = NSMutableParagraphStyle()
        estilo.alignment = .left
        estilo.lineBreakMode = .byWordWrapping
        estilo.lineSpacing = 6
        func medir(_ s: String, _ a: [NSAttributedString.Key: Any]) -> CGFloat {
            ceil((s as NSString).boundingRect(with: CGSize(width: ancho, height: .greatestFiniteMagnitude),
                                              options: .usesLineFragmentOrigin, attributes: a, context: nil).height)
        }
        var tam: CGFloat = 96
        var a: [NSAttributedString.Key: Any] = [:]
        var h: CGFloat = 0
        repeat {
            a = [.font: fuente(tam, .heavy), .foregroundColor: UIColor(white: 1, alpha: alfa), .paragraphStyle: estilo]
            h = medir(l.actual, a)
            tam -= 4
        } while h > alto * 0.66 && tam > 40
        let b: [NSAttributedString.Key: Any] = [.font: fuente(max(34, tam * 0.5), .bold),
                                                .foregroundColor: UIColor(white: 1, alpha: 0.48 * alfa), .paragraphStyle: estilo]
        let hSig = l.siguiente.map { min(medir($0, b), alto * 0.3) } ?? 0
        let espacio: CGFloat = l.siguiente == nil ? 0 : 34
        var y = arriba + max(0, (alto - h - espacio - hSig) / 2) + dy
        (l.actual as NSString).draw(with: CGRect(x: margen, y: y, width: ancho, height: h),
                                    options: .usesLineFragmentOrigin, attributes: a, context: nil)
        y += h + espacio
        if let s = l.siguiente {
            (s as NSString).draw(with: CGRect(x: margen, y: y, width: ancho, height: hSig),
                                 options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: b, context: nil)
        }
    }

    /// Un cuadro de la transición de `de` a `a` (p de 0 a 1). Sin letra en un lado, la portada pasa de nítida a borrosa.
    static func cuadro(_ base: Base, de: Letra?, a: Letra?, p: CGFloat) -> UIImage {
        let t = tamano
        let marco = CGRect(origin: .zero, size: t)
        let e = p * p * (3 - 2 * p) // suavizado (entra y sale despacio)
        return UIGraphicsImageRenderer(size: t, format: formato).image { _ in
            base.borrosa.draw(in: marco)
            // Cuánto se ve la portada nítida: toda sin letra; se desenfoca al aparecer la letra y vuelve al irse
            let nitidez: CGFloat = (de == nil && a == nil) ? 1 : de == nil ? 1 - e : a == nil ? e : 0
            if nitidez > 0.01 { base.nitida.draw(in: marco, blendMode: .normal, alpha: nitidez) }
            let salto = t.height * 0.035
            if let de { dibujar(de, alfa: 1 - e, desplazamiento: -salto * e) }
            if let a { dibujar(a, alfa: e, desplazamiento: salto * (1 - e)) }
        }
    }
}
