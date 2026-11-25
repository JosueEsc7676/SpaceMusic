import AVFoundation
import MediaPlayer
import UIKit

/// Una portada de pantalla completa por canción/estilo. Nunca depende de los versos.
@available(iOS 26, *)
enum ArteAnimado {
    static let ancho = 1080, alto = 1440

    nonisolated(unsafe) private static var obras: [String: MPMediaItemAnimatedArtwork] = [:]
    nonisolated(unsafe) private static var orden: [String] = []
    nonisolated(unsafe) private static var enCurso: [String: Task<URL?, Never>] = [:]
    private static let candado = NSLock()

    /// ¿Este iPhone muestra portadas animadas verticales?
    static var disponible: Bool {
        MPNowPlayingInfoCenter.supportedAnimatedArtworkKeys.contains(MPNowPlayingInfoProperty3x4AnimatedArtwork)
    }

    private static func guardar(_ id: String, _ o: MPMediaItemAnimatedArtwork) {
        obras[id] = o
        orden.append(id)
        if orden.count > 12 { obras[orden.removeFirst()] = nil }
    }

    // MARK: Imagen fija (entre versos) o portada que se mueve («Original»)

    @MainActor static func obra(clave: String, imagen: UIImage, movimiento: Bool) -> MPMediaItemAnimatedArtwork {
        let id = "sm-\(huella(clave))-\(movimiento ? "m" : "q")"
        if let o = obras[id] { return o }
        let previa = recorte(imagen)
        let o = MPMediaItemAnimatedArtwork(artworkID: id,
            previewImageRequestHandler: { _ in previa },
            videoAssetFileURLRequestHandler: { _ in
                await video(id: id) {
                    guard let cg = previa.cgImage else { return nil }
                    if movimiento { return (cuadrosMovimiento(cg), 10) }
                    return (AnySequence([(0.0, cg)]), 4)
                }
            })
        guardar(id, o)
        return o
    }

    private static func cuadrosMovimiento(_ cg: CGImage) -> AnySequence<(Double, CGImage)> {
        let caja = CGSize(width: ancho, height: alto)
        return AnySequence((0..<120).lazy.compactMap { i -> (Double, CGImage)? in
            autoreleasepool {
                let t = Double(i) / 120
                let zoom = 1 + 0.07 * (1 - cos(2 * .pi * t)) / 2
                let dx = CGFloat(sin(2 * .pi * t)) * 18
                let img = UIGraphicsImageRenderer(size: caja, format: formatoVideo).image { _ in
                    UIImage(cgImage: cg).draw(in: rectCubrir(CGSize(width: cg.width, height: cg.height), en: caja, zoom: zoom, dx: dx))
                }
                guard let c = img.cgImage else { return nil }
                return (t * 10, c)
            }
        })
    }

    // MARK: Utilidades

    private static var formatoVideo: UIGraphicsImageRendererFormat {
        let f = UIGraphicsImageRendererFormat()
        f.scale = 1
        f.opaque = true
        return f
    }

    /// Imagen cuadrada → 3:4 centrada (la letra ya va en la columna central, así que nunca se corta).
    static func recorte(_ img: UIImage) -> UIImage {
        let caja = CGSize(width: ancho, height: alto)
        return UIGraphicsImageRenderer(size: caja, format: formatoVideo).image { _ in
            img.draw(in: rectCubrir(img.size, en: caja, zoom: 1, dx: 0))
        }
    }

    private static func rectCubrir(_ tam: CGSize, en caja: CGSize, zoom: CGFloat, dx: CGFloat) -> CGRect {
        let esc = max(caja.width / max(tam.width, 1), caja.height / max(tam.height, 1)) * zoom
        let w = tam.width * esc, h = tam.height * esc
        return CGRect(x: (caja.width - w) / 2 + dx, y: (caja.height - h) / 2, width: w, height: h)
    }

    /// Huella estable de un texto (FNV-1a), para nombrar archivos.
    private static func huella(_ t: String) -> String {
        var h: UInt64 = 1469598103934665603
        for b in t.utf8 { h = (h ^ UInt64(b)) &* 1099511628211 }
        return String(h, radix: 36)
    }

    private static func carpeta() -> URL {
        let c = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("arte-animado", isDirectory: true)
        try? FileManager.default.createDirectory(at: c, withIntermediateDirectories: true)
        return c
    }

    /// Video local (iOS lo pide solo cuando lo va a mostrar). Si ya se está creando, se espera ese mismo.
    private static func video(id: String, cuadros: @escaping @Sendable () -> (AnySequence<(Double, CGImage)>, Double)?) async -> URL? {
        let url = carpeta().appendingPathComponent("\(id).mp4")
        if FileManager.default.fileExists(atPath: url.path) { return url }
        candado.lock()
        if let t = enCurso[id] { candado.unlock(); return await t.value }
        let tarea = Task.detached(priority: .userInitiated) { () -> URL? in
            guard let hecho = cuadros() else { return nil }
            let temporal = carpeta().appendingPathComponent("\(id)-\(UUID().uuidString).mp4")
            guard await escribir(hecho.0, duracion: hecho.1, en: temporal) else { try? FileManager.default.removeItem(at: temporal); return nil }
            try? FileManager.default.removeItem(at: url)
            do { try FileManager.default.moveItem(at: temporal, to: url) } catch { return nil }
            limpiar()
            return url
        }
        enCurso[id] = tarea
        candado.unlock()
        let r = await tarea.value
        candado.lock(); enCurso[id] = nil; candado.unlock()
        return r
    }

    /// Se guardan como mucho 120 videos (los más viejos se borran).
    private static func limpiar() {
        let fm = FileManager.default
        guard let todos = try? fm.contentsOfDirectory(at: carpeta(), includingPropertiesForKeys: [.contentModificationDateKey]), todos.count > 120 else { return }
        let viejos = todos.sorted {
            ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
                < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
        }
        for u in viejos.prefix(todos.count - 120) { try? fm.removeItem(at: u) }
    }

    /// Cuadros con su tiempo (s): cada uno se queda hasta el siguiente; el último hasta `duracion`.
    private static func escribir(_ cuadros: AnySequence<(Double, CGImage)>, duracion: Double, en url: URL) async -> Bool {
        guard let escritor = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return false }
        let entrada = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: ancho,
            AVVideoHeightKey: alto,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 5_000_000, AVVideoMaxKeyFrameIntervalKey: 30],
        ])
        entrada.expectsMediaDataInRealTime = false
        let adaptador = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: entrada, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: ancho,
            kCVPixelBufferHeightKey as String: alto,
        ])
        guard escritor.canAdd(entrada) else { return false }
        escritor.add(entrada)
        guard escritor.startWriting() else { return false }
        escritor.startSession(atSourceTime: .zero)
        let caja = CGRect(x: 0, y: 0, width: ancho, height: alto)
        // Secuencia perezosa: no acumular todos los cuadros de alta resolución de un verso.
        var iterador = cuadros.makeIterator()
        var ultimo: (Double, CGImage)?
        var anterior = -1.0
        let limite = ProcessInfo.processInfo.systemUptime + 45
        defer { if escritor.status == .writing { escritor.cancelWriting() } }
        while true {
            let proximo = iterador.next() ?? ultimo.flatMap { duracion > $0.0 + 0.05 ? (duracion, $0.1) : nil }
            guard let (t, cg) = proximo else { break }
            if ultimo == nil && t != 0 { return false }
            ultimo = (t, cg)
            guard t > anterior else { continue }
            anterior = t
            while !entrada.isReadyForMoreMediaData {
                guard escritor.status == .writing, !Task.isCancelled,
                      ProcessInfo.processInfo.systemUptime < limite else { return false }
                try? await Task.sleep(for: .milliseconds(4))
            }
            guard !Task.isCancelled, escritor.status == .writing else { return false }
            guard let pool = adaptador.pixelBufferPool else { return false }
            var pb: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pb)
            guard let buffer = pb else { return false }
            CVPixelBufferLockBaseAddress(buffer, [])
            if let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: ancho, height: alto, bitsPerComponent: 8,
                                   bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
                                   bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
                ctx.interpolationQuality = .high
                ctx.draw(cg, in: caja)
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            if !adaptador.append(buffer, withPresentationTime: CMTime(seconds: t, preferredTimescale: 600)) { return false }
        }
        guard ultimo != nil else { return false }
        entrada.markAsFinished()
        await escritor.finishWriting()
        return escritor.status == .completed
    }
}
