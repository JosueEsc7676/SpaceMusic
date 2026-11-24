import AVFoundation
import MediaPlayer
import UIKit

/// Portada a pantalla completa en la pantalla bloqueada (iOS 26, como Apple Music).
/// iOS solo la muestra en grande si la app le da un video vertical 3:4 (`MPNowPlayingInfoProperty3x4AnimatedArtwork`):
/// aquí se crea, a partir de la portada, un video de 10 s que se acerca y se aleja muy despacio y se repite sin cortes.
/// Se guarda en Caches para no volver a crearlo. Con modo de bajo consumo o «Reducir movimiento», iOS muestra la imagen fija.
@available(iOS 26, *)
enum ArteAnimado {
    static let ancho = 1080, alto = 1440
    private static let cuadros = 300, fps: Int32 = 30

    nonisolated(unsafe) private static var obras: [Int: MPMediaItemAnimatedArtwork] = [:]
    nonisolated(unsafe) private static var previas: [Int: UIImage] = [:]

    /// ¿Este iPhone muestra portadas animadas verticales?
    static var disponible: Bool {
        MPNowPlayingInfoCenter.supportedAnimatedArtworkKeys.contains(MPNowPlayingInfoProperty3x4AnimatedArtwork)
    }

    /// La obra para «Now Playing» (se reutiliza mientras sea la misma canción y portada).
    static func obra(id: Int, portada: UIImage) -> MPMediaItemAnimatedArtwork {
        if let o = obras[id] { return o }
        if obras.count > 6 { obras.removeAll(); previas.removeAll() }
        let previa = imagenPrevia(id: id, portada: portada)
        let o = MPMediaItemAnimatedArtwork(artworkID: "spacemusic-\(id)-v1",
            previewImageRequestHandler: { _ in previa },
            videoAssetFileURLRequestHandler: { _ in await video(id: id, portada: previa) })
        obras[id] = o
        return o
    }

    /// Primer cuadro del video: la portada recortada a 3:4 y cubriendo todo.
    static func imagenPrevia(id: Int, portada: UIImage) -> UIImage {
        if let p = previas[id] { return p }
        let formato = UIGraphicsImageRendererFormat()
        formato.scale = 1
        let img = UIGraphicsImageRenderer(size: CGSize(width: ancho, height: alto), format: formato).image { _ in
            portada.draw(in: rectCubrir(portada.size, en: CGSize(width: ancho, height: alto), zoom: 1, dx: 0, dy: 0))
        }
        previas[id] = img
        return img
    }

    private static func rectCubrir(_ tam: CGSize, en caja: CGSize, zoom: CGFloat, dx: CGFloat, dy: CGFloat) -> CGRect {
        let esc = max(caja.width / max(tam.width, 1), caja.height / max(tam.height, 1)) * zoom
        let w = tam.width * esc, h = tam.height * esc
        return CGRect(x: (caja.width - w) / 2 + dx, y: (caja.height - h) / 2 + dy, width: w, height: h)
    }

    private static func carpeta() -> URL {
        let c = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("arte-animado", isDirectory: true)
        try? FileManager.default.createDirectory(at: c, withIntermediateDirectories: true)
        return c
    }

    /// Video local (lo pide iOS solo cuando lo va a mostrar).
    static func video(id: Int, portada: UIImage) async -> URL? {
        let url = carpeta().appendingPathComponent("\(id)-v1.mp4")
        if FileManager.default.fileExists(atPath: url.path) { return url }
        guard let cg = portada.cgImage else { return nil }
        let temporal = carpeta().appendingPathComponent("\(id)-\(UUID().uuidString).mp4")
        let ok = await Task.detached(priority: .utility) { await crear(cg, en: temporal) }.value
        guard ok else { try? FileManager.default.removeItem(at: temporal); return nil }
        try? FileManager.default.removeItem(at: url)
        do { try FileManager.default.moveItem(at: temporal, to: url) } catch { return nil }
        limpiar()
        return url
    }

    /// Se guardan como mucho 40 videos (los más viejos se borran).
    private static func limpiar() {
        let fm = FileManager.default
        guard let todos = try? fm.contentsOfDirectory(at: carpeta(), includingPropertiesForKeys: [.contentModificationDateKey]), todos.count > 40 else { return }
        let viejos = todos.sorted {
            ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
                < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
        }
        for u in viejos.prefix(todos.count - 40) { try? fm.removeItem(at: u) }
    }

    private static func crear(_ portada: CGImage, en url: URL) async -> Bool {
        guard let escritor = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return false }
        let entrada = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: ancho,
            AVVideoHeightKey: alto,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 5_000_000, AVVideoMaxKeyFrameIntervalKey: 60],
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
        let tam = CGSize(width: portada.width, height: portada.height)
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
                // Se acerca hasta un 7 % y vuelve; un vaivén suave de lado: el último cuadro empalma con el primero
                let t = Double(i) / Double(cuadros)
                let zoom = 1 + 0.07 * (1 - cos(2 * .pi * t)) / 2
                let dx = CGFloat(sin(2 * .pi * t)) * 18
                let r = rectCubrir(tam, en: caja, zoom: zoom, dx: dx, dy: 0)
                // CGContext tiene el origen abajo: se dibuja igual porque la portada se centra
                ctx.draw(portada, in: r)
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            if !adaptador.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps)) { return false }
        }
        entrada.markAsFinished()
        await escritor.finishWriting()
        return escritor.status == .completed
    }
}
