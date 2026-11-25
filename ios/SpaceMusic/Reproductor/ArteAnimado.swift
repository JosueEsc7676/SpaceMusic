import AVFoundation
import MediaPlayer
import UIKit

/// Portada a pantalla completa en la pantalla bloqueada (iOS 26, como Apple Music).
/// iOS solo la pone en grande si la app le da un video vertical 3:4 (`MPNowPlayingInfoProperty3x4AnimatedArtwork`).
/// - Con letra: cada verso es la MISMA imagen de la pantalla bloqueada (portada con su desenfoque, sombra y la letra
///   con su estilo), recortada a 3:4, como un video corto y quieto. La imagen previa se entrega al instante y el video
///   del verso siguiente se prepara antes de que suene, así el cambio es fluido.
/// - «Original» (sin letra): la portada se acerca y se aleja muy despacio, en un video de 10 s que se repite.
/// Con modo de bajo consumo o «Reducir movimiento», iOS muestra la imagen previa (que ya lleva la letra).
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

    /// La obra para «Now Playing». `clave` identifica la imagen (canción + verso + estilo); `movimiento` = acercamiento lento.
    @MainActor static func obra(clave: String, imagen: UIImage, movimiento: Bool) -> MPMediaItemAnimatedArtwork {
        let id = "sm-\(huella(clave))-\(movimiento ? "m" : "q")"
        if let o = obras[id] { return o }
        let previa = recorte(imagen)
        let o = MPMediaItemAnimatedArtwork(artworkID: id,
            previewImageRequestHandler: { _ in previa },
            videoAssetFileURLRequestHandler: { _ in await video(id: id, previa: previa, movimiento: movimiento) })
        obras[id] = o
        orden.append(id)
        if orden.count > 12 { obras[orden.removeFirst()] = nil }
        return o
    }

    /// Prepara de antemano el video de un verso (para el que viene).
    static func preparar(clave: String, imagen: UIImage) {
        let id = "sm-\(huella(clave))-q"
        Task.detached(priority: .utility) { _ = await video(id: id, previa: recorte(imagen), movimiento: false) }
    }

    /// Imagen cuadrada → 3:4 centrada (la letra ya va en la columna central, así que nunca se corta).
    static func recorte(_ img: UIImage) -> UIImage {
        let formato = UIGraphicsImageRendererFormat()
        formato.scale = 1
        formato.opaque = true
        let caja = CGSize(width: ancho, height: alto)
        return UIGraphicsImageRenderer(size: caja, format: formato).image { _ in
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
    static func video(id: String, previa: UIImage, movimiento: Bool) async -> URL? {
        let url = carpeta().appendingPathComponent("\(id).mp4")
        if FileManager.default.fileExists(atPath: url.path) { return url }
        candado.lock()
        if let t = enCurso[id] { candado.unlock(); return await t.value }
        let tarea = Task.detached(priority: .userInitiated) { () -> URL? in
            guard let cg = previa.cgImage else { return nil }
            let temporal = carpeta().appendingPathComponent("\(id)-\(UUID().uuidString).mp4")
            let ok = await crear(cg, en: temporal, movimiento: movimiento)
            guard ok else { try? FileManager.default.removeItem(at: temporal); return nil }
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

    /// Se guardan como mucho 80 videos (los más viejos se borran).
    private static func limpiar() {
        let fm = FileManager.default
        guard let todos = try? fm.contentsOfDirectory(at: carpeta(), includingPropertiesForKeys: [.contentModificationDateKey]), todos.count > 80 else { return }
        let viejos = todos.sorted {
            ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
                < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
        }
        for u in viejos.prefix(todos.count - 80) { try? fm.removeItem(at: u) }
    }

    private static func crear(_ imagen: CGImage, en url: URL, movimiento: Bool) async -> Bool {
        guard let escritor = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return false }
        // Quieto: 2 s a 2 cuadros por segundo (se crea en un instante). Con movimiento: 10 s a 30 cuadros.
        let fps: Int32 = movimiento ? 30 : 2
        let cuadros = movimiento ? 300 : 4
        let entrada = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: ancho,
            AVVideoHeightKey: alto,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: movimiento ? 5_000_000 : 3_000_000, AVVideoMaxKeyFrameIntervalKey: 60],
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
        let tam = CGSize(width: imagen.width, height: imagen.height)
        let caja = CGSize(width: ancho, height: alto)
        for i in 0..<cuadros {
            while !entrada.isReadyForMoreMediaData { try? await Task.sleep(for: .milliseconds(4)) }
            guard let pool = adaptador.pixelBufferPool else { return false }
            var pb: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pb)
            guard let buffer = pb else { return false }
            CVPixelBufferLockBaseAddress(buffer, [])
            if let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: ancho, height: alto, bitsPerComponent: 8,
                                   bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
                                   bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
                ctx.interpolationQuality = .high
                ctx.setFillColor(UIColor.black.cgColor)
                ctx.fill(CGRect(origin: .zero, size: caja))
                // El último cuadro empalma con el primero (se repite sin cortes)
                let t = Double(i) / Double(cuadros)
                let zoom = movimiento ? 1 + 0.07 * (1 - cos(2 * .pi * t)) / 2 : 1
                let dx = movimiento ? CGFloat(sin(2 * .pi * t)) * 18 : 0
                ctx.draw(imagen, in: rectCubrir(tam, en: caja, zoom: zoom, dx: dx))
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            if !adaptador.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps)) { return false }
        }
        entrada.markAsFinished()
        await escritor.finishWriting()
        return escritor.status == .completed
    }
}
