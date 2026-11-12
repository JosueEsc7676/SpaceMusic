import Accelerate
import AVFoundation
import MediaToolbox
import Observation

/// Ecualizador de 5 bandas y medidor de nivel para el visualizador, sobre el mismo AVQueuePlayer.
///
/// Igual que la web (`fx` en player.js): bandas de 60, 230, 910, 3600 y 14000 Hz; la primera es un estante de graves,
/// la última de agudos y las del medio picos con Q = 1. Se aplica con un `MTAudioProcessingTap` en cada canción
/// (filtros biquad de Accelerate), así no hace falta cambiar el motor de reproducción.
/// Si el ecualizador y el visualizador están apagados no se conecta nada (cero coste).
@MainActor
@Observable
final class ProcesadorAudio {
    static let compartido = ProcesadorAudio()

    static let bandas: [Double] = [60, 230, 910, 3600, 14000]
    static let presets: [(nombre: String, ganancias: [Double])] = [
        ("Plano", [0, 0, 0, 0, 0]), ("Graves", [6, 4, 0, 0, 1]), ("Agudos", [0, 0, 0, 3, 6]), ("Voz", [-2, 0, 4, 3, 0]),
        ("Rock", [4, 2, -1, 2, 4]), ("Electrónica", [5, 3, 0, 2, 4]), ("Acústico", [3, 1, 1, 2, 2]), ("Noche", [2, 1, 0, -2, -4]),
    ]

    var ecualizador: Bool {
        didSet { UserDefaults.standard.set(ecualizador, forKey: "eqActivo"); publicar(); Reproductor.compartido.reconectarProcesador() }
    }
    var ganancias: [Double] {
        didSet { UserDefaults.standard.set(ganancias, forKey: "eqGanancias"); publicar() }
    }
    var visualizador: Bool {
        didSet { UserDefaults.standard.set(visualizador, forKey: "visualizador"); Reproductor.compartido.reconectarProcesador() }
    }

    private init() {
        ecualizador = UserDefaults.standard.bool(forKey: "eqActivo")
        let g = UserDefaults.standard.array(forKey: "eqGanancias") as? [Double] ?? []
        ganancias = g.count == 5 ? g : [0, 0, 0, 0, 0]
        visualizador = UserDefaults.standard.object(forKey: "visualizador") as? Bool ?? true
        publicar()
    }

    var necesitaTap: Bool { ecualizador || visualizador }

    func aplicarPreset(_ g: [Double]) { ganancias = g }

    /// Nivel actual de la música (0…1), para el visualizador. Lectura directa: no provoca redibujos.
    nonisolated var nivel: Float { ParametrosTap.compartidos.nivel }

    private func publicar() {
        ParametrosTap.compartidos.actualizar(activo: ecualizador, ganancias: ganancias.map(Float.init))
    }

    /// Conecta el procesador a una canción (o lo quita si ya no hace falta).
    func conectar(_ item: AVPlayerItem) {
        guard necesitaTap else { item.audioMix = nil; return }
        Task {
            guard let pista = try? await item.asset.loadTracks(withMediaType: .audio).first,
                  let mezcla = ParametrosTap.crearMezcla(pista) else { return }
            item.audioMix = mezcla
        }
    }
}

/// Ajustes compartidos con el hilo de audio (protegidos con un candado).
final class ParametrosTap: @unchecked Sendable {
    static let compartidos = ParametrosTap()
    private let candado = NSLock()
    private var _activo = false
    private var _ganancias: [Float] = [0, 0, 0, 0, 0]
    private var _version = 0
    private var _nivel: Float = 0

    func actualizar(activo: Bool, ganancias: [Float]) {
        candado.lock(); _activo = activo; _ganancias = ganancias; _version += 1; candado.unlock()
    }
    func leer() -> (activo: Bool, ganancias: [Float], version: Int) {
        candado.lock(); defer { candado.unlock() }
        return (_activo, _ganancias, _version)
    }
    var nivel: Float {
        get { candado.lock(); defer { candado.unlock() }; return _nivel }
        set { candado.lock(); _nivel = newValue; candado.unlock() }
    }

    static func crearMezcla(_ pista: AVAssetTrack) -> AVAudioMix? {
        let contexto = Unmanaged.passRetained(ContextoTap()).toOpaque()
        var llamadas = MTAudioProcessingTapCallbacks(
            version: Int32(kMTAudioProcessingTapCallbacksVersion_0),
            clientInfo: contexto,
            init: tapIniciar, finalize: tapTerminar, prepare: tapPreparar, unprepare: nil, process: tapProcesar)
        var tap: MTAudioProcessingTap?
        guard MTAudioProcessingTapCreate(kCFAllocatorDefault, &llamadas, MTAudioProcessingTapCreationFlags(kMTAudioProcessingTapCreationFlag_PostEffects), &tap) == noErr,
              let tap else {
            Unmanaged<ContextoTap>.fromOpaque(contexto).release()
            return nil
        }
        let parametros = AVMutableAudioMixInputParameters(track: pista)
        parametros.audioTapProcessor = tap
        let mezcla = AVMutableAudioMix()
        mezcla.inputParameters = [parametros]
        return mezcla
    }
}

/// Estado de un tap: filtros por canal y frecuencia de muestreo.
final class ContextoTap {
    var muestreo: Double = 44100
    var canales = 2
    var configuracion: vDSP_biquad_Setup?
    var retardos: [[Float]] = []
    var versionAplicada = -1
    var compensacion: Float = 1

    deinit { if let c = configuracion { vDSP_biquad_DestroySetup(c) } }

    func preparar(muestreo: Double, canales: Int) {
        self.muestreo = muestreo > 0 ? muestreo : 44100
        self.canales = max(1, canales)
        versionAplicada = -1
    }

    /// Coeficientes RBJ (los mismos tipos de filtro que BiquadFilterNode de la web).
    private func reconstruir(_ ganancias: [Float]) {
        if let c = configuracion { vDSP_biquad_DestroySetup(c) }
        var coef: [Double] = []
        for (i, f) in ProcesadorAudioBandas.valores.enumerated() {
            let g = Double(i < ganancias.count ? ganancias[i] : 0)
            let A = pow(10, g / 40)
            let w0 = 2 * Double.pi * min(f, muestreo * 0.45) / muestreo
            let cw = cos(w0), sw = sin(w0)
            var b0, b1, b2, a0, a1, a2: Double
            if i == 0 || i == ProcesadorAudioBandas.valores.count - 1 {
                let alfa = sw / 2 * sqrt((A + 1 / A) * (1 / 1.0 - 1) + 2)
                let r = 2 * sqrt(A) * alfa
                if i == 0 {
                    b0 = A * ((A + 1) - (A - 1) * cw + r); b1 = 2 * A * ((A - 1) - (A + 1) * cw); b2 = A * ((A + 1) - (A - 1) * cw - r)
                    a0 = (A + 1) + (A - 1) * cw + r; a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - r
                } else {
                    b0 = A * ((A + 1) + (A - 1) * cw + r); b1 = -2 * A * ((A - 1) + (A + 1) * cw); b2 = A * ((A + 1) + (A - 1) * cw - r)
                    a0 = (A + 1) - (A - 1) * cw + r; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - r
                }
            } else {
                let alfa = sw / 2  // Q = 1
                b0 = 1 + alfa * A; b1 = -2 * cw; b2 = 1 - alfa * A
                a0 = 1 + alfa / A; a1 = -2 * cw; a2 = 1 - alfa / A
            }
            coef += [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0]
        }
        let secciones = vDSP_Length(ProcesadorAudioBandas.valores.count)
        configuracion = vDSP_biquad_CreateSetup(coef, secciones)
        retardos = Array(repeating: Array(repeating: 0, count: 2 * Int(secciones) + 2), count: canales)
        // Margen para que las subidas no saturen.
        let maximo = ganancias.max() ?? 0
        compensacion = maximo > 0 ? Float(pow(10, -Double(maximo) / 20)) : 1
    }

    func procesar(_ lista: UnsafeMutableAudioBufferListPointer, cuadros: Int) {
        let ajustes = ParametrosTap.compartidos.leer()
        if ajustes.activo, ajustes.version != versionAplicada {
            reconstruir(ajustes.ganancias)
            versionAplicada = ajustes.version
        }
        var suma: Float = 0
        for (c, buffer) in lista.enumerated() {
            guard let datos = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
            let n = min(cuadros, Int(buffer.mDataByteSize) / MemoryLayout<Float>.size)
            guard n > 0 else { continue }
            if ajustes.activo, let config = configuracion, c < retardos.count {
                retardos[c].withUnsafeMutableBufferPointer { r in
                    vDSP_biquad(config, r.baseAddress!, datos, 1, datos, 1, vDSP_Length(n))
                }
                if compensacion != 1 {
                    var k = compensacion
                    vDSP_vsmul(datos, 1, &k, datos, 1, vDSP_Length(n))
                }
            }
            var rms: Float = 0
            vDSP_rmsqv(datos, 1, &rms, vDSP_Length(n))
            suma += rms
        }
        let nivel = min(1, suma / Float(max(1, lista.count)) * 2.2)
        // Suavizado: sube rápido y baja despacio, como un vúmetro.
        let previo = ParametrosTap.compartidos.nivel
        ParametrosTap.compartidos.nivel = nivel > previo ? nivel : previo * 0.85 + nivel * 0.15
    }
}

/// Copia de las bandas accesible desde el hilo de audio.
enum ProcesadorAudioBandas {
    static let valores: [Double] = [60, 230, 910, 3600, 14000]
}

// MARK: - Funciones C del tap

private let tapIniciar: MTAudioProcessingTapInitCallback = { _, clientInfo, almacen in
    almacen.pointee = clientInfo
}

private let tapTerminar: MTAudioProcessingTapFinalizeCallback = { tap in
    Unmanaged<ContextoTap>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
}

private let tapPreparar: MTAudioProcessingTapPrepareCallback = { tap, _, formato in
    let contexto = Unmanaged<ContextoTap>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    contexto.preparar(muestreo: formato.pointee.mSampleRate, canales: Int(formato.pointee.mChannelsPerFrame))
}

private let tapProcesar: MTAudioProcessingTapProcessCallback = { tap, cuadros, _, lista, cuadrosSalida, banderasSalida in
    guard MTAudioProcessingTapGetSourceAudio(tap, cuadros, lista, banderasSalida, nil, cuadrosSalida) == noErr else { return }
    let contexto = Unmanaged<ContextoTap>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    contexto.procesar(UnsafeMutableAudioBufferListPointer(lista), cuadros: Int(cuadrosSalida.pointee))
}
