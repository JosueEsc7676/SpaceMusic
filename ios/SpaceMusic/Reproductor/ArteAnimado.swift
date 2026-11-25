import AVFoundation
import MediaPlayer
import UIKit

/// Un verso listo para hacerse video: la portada preparada, el texto, cuándo empieza cada palabra y cuánto dura.
struct VersoAnimado: @unchecked Sendable {
    let clave: String
    let base: ArteBloqueo.Base
    let actual: String
    let siguiente: String?
    /// Segundos (desde que se muestra el video) en que empieza cada palabra.
    let palabras: [Double]
    let duracion: Double
    let indice: Int
}

/// Portada a pantalla completa en la pantalla bloqueada (iOS 26, como Apple Music).
/// iOS solo la pone en grande si la app le da un video vertical 3:4 (`MPNowPlayingInfoProperty3x4AnimatedArtwork`).
/// - Con letra: UN video por verso. Dentro del video el verso entra con su animación y cada palabra se enciende de a
///   poco justo cuando se canta (15 cambios suaves por palabra en vez de cambiar la imagen de la pantalla bloqueada,
///   que era lo que la hacía parpadear y se veía lento). El video del verso siguiente se prepara mientras suena este.
/// - Entre versos: la portada con su estilo, quieta. «Original»: la portada se acerca y se aleja muy despacio.
/// Con modo de bajo consumo o «Reducir movimiento», iOS muestra la imagen previa en vez del video.
@available(iOS 26, *)
enum ArteAnimado {
    static let ancho = 1080, alto = 1440

    nonisolated(unsafe) private static var obras: [String: MPMediaItemAnimatedArtwork] = [:]
    nonisolated(unsafe) private static var orden: [String] = []
    nonisolated(unsafe) private static var enCurso: [String: Task<URL?, Never>] = [:]
    nonisolated(unsafe) private static var previas: [String: UIImage] = [:]
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
                    return ([(0, cg)], 4)
                }
            })
        guardar(id, o)
        return o
    }

    private static func cuadrosMovimiento(_ cg: CGImage) -> [(Double, CGImage)] {
        // Se acerca hasta un 7 % y vuelve, con un vaivén suave de lado: el final empalma con el principio
        let caja = CGSize(width: ancho, height: alto)
        var r: [(Double, CGImage)] = []
        for i in 0..<120 {
            let t = Double(i) / 120
            let zoom = 1 + 0.07 * (1 - cos(2 * .pi * t)) / 2
            let dx = CGFloat(sin(2 * .pi * t)) * 18
            let img = UIGraphicsImageRenderer(size: caja, format: formatoVideo).image { _ in
                UIImage(cgImage: cg).draw(in: rectCubrir(CGSize(width: cg.width, height: cg.height), en: caja, zoom: zoom, dx: dx))
            }
            if let c = img.cgImage { r.append((t * 10, c)) }
        }
        return r
    }

    // MARK: Video de un verso

    private static func idVerso(_ v: VersoAnimado) -> String { "sm-v-\(huella(v.clave))" }

    /// Palabras encendidas a los t segundos (con decimales: la que suena se enciende en 0,18 s).
    private static func encendidas(_ v: VersoAnimado, _ t: Double) -> Double {
        v.palabras.reduce(0) { $0 + min(max((t - $1) / 0.18, 0), 1) }
    }

    private static func previaVerso(_ v: VersoAnimado) -> UIImage {
        let id = idVerso(v)
        candado.lock()
        if let p = previas[id] { candado.unlock(); return p }
        candado.unlock()
        let primera = ArteBloqueo.cuadroVerso(v.base, letra: (v.actual, v.siguiente), entrada: v.base.estilo.entrada == .ninguna ? 1 : 0,
                                              encendidas: v.base.estilo.iluminar ? encendidas(v, 0) : nil, indice: v.indice)
        let p = recorte(primera)
        candado.lock()
        previas[id] = p
        if previas.count > 16 { previas.removeAll() }
        candado.unlock()
        return p
    }

    /// Los cuadros del verso: la entrada (0,4 s) y, con «iluminar», 4 cuadros por palabra para encenderla suave.
    private static func cuadrosVerso(_ v: VersoAnimado) -> [(Double, CGImage)] {
        let e = v.base.estilo
        var tiempos: Set<Double> = [0]
        if e.entrada != .ninguna { for i in 1...8 { tiempos.insert(Double(i) * 0.05) } }
        if e.iluminar {
            for p in v.palabras where p < v.duracion {
                for d in [0.0, 0.06, 0.12, 0.18] { tiempos.insert(max(0, ((p + d) * 100).rounded() / 100)) }
            }
        }
        var r: [(Double, CGImage)] = []
        for t in tiempos.sorted() {
            let entrada = e.entrada == .ninguna ? 1 : CGFloat(min(1, t / 0.4))
            let img = ArteBloqueo.cuadroVerso(v.base, letra: (v.actual, v.siguiente), entrada: entrada,
                                              encendidas: e.iluminar ? encendidas(v, t) : nil, indice: v.indice)
            if let cg = recorte(img).cgImage { r.append((t, cg)) }
        }
        return r
    }

    /// La obra del verso que suena (se reutiliza si ya se preparó).
    @MainActor static func obraVerso(_ v: VersoAnimado) -> MPMediaItemAnimatedArtwork {
        let id = idVerso(v)
        if let o = obras[id] { return o }
        let previa = previaVerso(v)
        let o = MPMediaItemAnimatedArtwork(artworkID: id,
            previewImageRequestHandler: { _ in previa },
            videoAssetFileURLRequestHandler: { _ in await video(id: id) { (cuadrosVerso(v), v.duracion) } })
        guardar(id, o)
        return o
    }

    /// Prepara de antemano el video del verso que viene.
    static func prepararVerso(_ v: VersoAnimado) {
        let id = idVerso(v)
        Task.detached(priority: .utility) {
            _ = previaVerso(v)
            _ = await video(id: id) { (cuadrosVerso(v), v.duracion) }
        }
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
    private static func video(id: String, cuadros: @escaping @Sendable () -> ([(Double, CGImage)], Double)?) async -> URL? {
        let url = carpeta().appendingPathComponent("\(id).mp4")
        if FileManager.default.fileExists(atPath: url.path) { return url }
        candado.lock()
        if let t = enCurso[id] { candado.unlock(); return await t.value }
        let tarea = Task.detached(priority: .userInitiated) { () -> URL? in
            guard let hecho = cuadros(), !hecho.0.isEmpty else { return nil }
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
    private static func escribir(_ cuadros: [(Double, CGImage)], duracion: Double, en url: URL) async -> Bool {
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
        // El último cuadro se repite al final: así el video dura lo que el verso (y un poco más)
        var lista = cuadros
        if let ultimo = cuadros.last, duracion > ultimo.0 + 0.05 { lista.append((duracion, ultimo.1)) }
        var anterior = -1.0
        for (t, cg) in lista {
            guard t > anterior else { continue }
            anterior = t
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
                ctx.draw(cg, in: caja)
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            if !adaptador.append(buffer, withPresentationTime: CMTime(seconds: t, preferredTimescale: 600)) { return false }
        }
        entrada.markAsFinished()
        await escritor.finishWriting()
        return escritor.status == .completed
    }
}
