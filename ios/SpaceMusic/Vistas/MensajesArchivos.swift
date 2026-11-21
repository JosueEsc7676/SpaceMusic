import AVFoundation
import PhotosUI
import SwiftUI

extension Mensaje {
    /// Dirección del archivo con el permiso de la sesión (el servidor lo acepta en la URL para fotos y audios).
    var urlArchivo: URL? {
        guard let a = adjuntoUrl, let t = Sesion.compartida.token else { return nil }
        return URL(string: Sesion.compartida.servidor + a + "?t=" + (t.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? t))
    }
    /// El texto automático de las fotos y los audios no se repite debajo.
    var textoVisible: Bool {
        !(tipo != nil && (texto == "📷 Foto" || texto == "🎤 Mensaje de voz")) && !(cancion != nil && texto == "🎵")
    }
}

/// Graba un mensaje de voz (AAC en .m4a). Mientras graba se pausa la música de este iPhone.
@MainActor @Observable final class GrabadoraVoz {
    private(set) var grabando = false
    private(set) var segundos: Double = 0
    @ObservationIgnored private var grabador: AVAudioRecorder?
    @ObservationIgnored private var inicio = Date()
    @ObservationIgnored private var reloj: Task<Void, Never>?
    private var archivo: URL { FileManager.default.temporaryDirectory.appendingPathComponent("voz-spacemusic.m4a") }
    static let maximo: Double = 300

    func empezar() async -> Bool {
        guard await AVAudioApplication.requestRecordPermission() else {
            Avisos.compartido.mostrar("Permite el micrófono en Ajustes para grabar mensajes de voz")
            return false
        }
        if Reproductor.compartido.sonando { Reproductor.compartido.pausarLocal() }
        let s = AVAudioSession.sharedInstance()
        do {
            try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try s.setActive(true)
            let ajustes: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100,
                                          AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue]
            let g = try AVAudioRecorder(url: archivo, settings: ajustes)
            guard g.record() else { restaurar(); return false }
            grabador = g
            grabando = true
            inicio = Date()
            segundos = 0
            reloj = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    guard let self, self.grabando else { return }
                    self.segundos = Date().timeIntervalSince(self.inicio)
                    if self.segundos >= GrabadoraVoz.maximo { self.grabador?.stop() }
                    try? await Task.sleep(for: .milliseconds(200))
                }
            }
            return true
        } catch {
            restaurar()
            Avisos.compartido.mostrar("No se pudo grabar: \(error.localizedDescription)")
            return false
        }
    }

    /// Termina y devuelve el audio y su duración (nada si duró menos de un segundo). Se escucha antes de enviarlo.
    func terminar() -> (datos: Data, duracion: Int)? {
        guard grabando else { return nil }
        grabador?.stop()
        reloj?.cancel()
        grabando = false
        restaurar()
        let duracion = Date().timeIntervalSince(inicio)
        defer { try? FileManager.default.removeItem(at: archivo) }
        guard duracion >= 0.8, let d = try? Data(contentsOf: archivo), !d.isEmpty else {
            Avisos.compartido.mostrar("Mensaje de voz muy corto")
            return nil
        }
        return (d, Int(duracion.rounded()))
    }

    func cancelar() {
        grabador?.stop()
        grabador?.deleteRecording()
        reloj?.cancel()
        grabando = false
        restaurar()
    }

    private func restaurar() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, policy: .longFormAudio)
    }
}

/// Foto de un mensaje: se abre a pantalla completa al tocarla.
struct FotoMensaje: View {
    let url: URL?
    @State private var abierta = false
    var body: some View {
        AsyncImage(url: url) { fase in
            switch fase {
            case .success(let img): img.resizable().scaledToFill()
            case .failure: Image(systemName: "photo").font(.largeTitle).foregroundStyle(Tema.secundario)
            default: ProgressView()
            }
        }
        .frame(width: 220, height: 260)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { abierta = true }
        .fullScreenCover(isPresented: $abierta) {
            ZStack(alignment: .topTrailing) {
                Color.black.ignoresSafeArea()
                AsyncImage(url: url) { img in img.resizable().scaledToFit() } placeholder: { ProgressView().tint(.white) }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                Button { abierta = false } label: {
                    Image(systemName: "xmark").font(.title3.weight(.semibold)).foregroundStyle(.white)
                        .frame(width: 44, height: 44).background(Circle().fill(.white.opacity(0.15)))
                }
                .padding()
                .accessibilityLabel("Cerrar foto")
            }
        }
        .accessibilityLabel("Foto")
    }
}

/// Reproduce las notas de voz (una a la vez). Baja el audio completo y lo reproduce desde la memoria:
/// así suenan también las propias y no depende de que el servidor permita pedir el audio por partes.
@MainActor @Observable final class VozMensajes: NSObject, AVAudioPlayerDelegate {
    static let compartida = VozMensajes()
    private(set) var actual: String?
    private(set) var sonando = false
    private(set) var cargando = false
    private(set) var tiempo: Double = 0
    private(set) var total: Double = 0
    @ObservationIgnored private var jugador: AVAudioPlayer?
    @ObservationIgnored private var reloj: Task<Void, Never>?
    @ObservationIgnored private var cache: [String: Data] = [:]

    /// `clave` identifica la nota (id del mensaje o «previa»); `datos` se usa para la vista previa sin subir.
    func alternar(clave: String, url: URL?, datos: Data? = nil) async {
        if actual == clave, let j = jugador {
            if j.isPlaying { j.pause(); sonando = false } else { empezar(j) }
            return
        }
        detener()
        actual = clave
        var audio = datos ?? cache[clave]
        if audio == nil, let url {
            cargando = true
            defer { cargando = false }
            if let res = try? await URLSession.shared.data(from: url), (res.1 as? HTTPURLResponse)?.statusCode == 200 {
                audio = res.0
                cache[clave] = res.0
            }
        }
        guard actual == clave else { return }
        guard let audio, let j = try? AVAudioPlayer(data: audio) else {
            actual = nil
            Avisos.compartido.mostrar("No se pudo reproducir el mensaje de voz")
            return
        }
        j.delegate = self
        j.prepareToPlay()
        jugador = j
        total = j.duration
        empezar(j)
    }

    func saltar(a fraccion: Double) {
        guard let j = jugador else { return }
        j.currentTime = max(0, min(1, fraccion)) * j.duration
        tiempo = j.currentTime
    }

    func detener() {
        jugador?.stop()
        jugador = nil
        reloj?.cancel()
        sonando = false
        tiempo = 0
        actual = nil
    }

    private func empezar(_ j: AVAudioPlayer) {
        if Reproductor.compartido.sonando { Reproductor.compartido.pausarLocal() }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        j.play()
        sonando = true
        reloj?.cancel()
        reloj = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self, let j = self.jugador else { return }
                self.tiempo = j.currentTime
                try? await Task.sleep(for: .milliseconds(150))
            }
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.sonando = false
            self.tiempo = 0
            self.reloj?.cancel()
        }
    }
}

/// Mensaje de voz: reproducir/pausar, barra de avance (se toca para saltar) y duración.
struct AudioMensaje: View {
    let clave: String
    let url: URL?
    var datos: Data? = nil
    let duracion: Int?
    let mio: Bool
    @State private var voz = VozMensajes.compartida

    private var esta: Bool { voz.actual == clave }
    private var avance: Double { esta && voz.total > 0 ? min(1, voz.tiempo / voz.total) : 0 }

    var body: some View {
        HStack(spacing: 10) {
            Button { Task { await voz.alternar(clave: clave, url: url, datos: datos) } } label: {
                Group {
                    if esta && voz.cargando { ProgressView().tint(mio ? .white : .black) }
                    else { Image(systemName: esta && voz.sonando ? "pause.fill" : "play.fill").font(.body.weight(.bold)) }
                }
                .foregroundStyle(mio ? .white : .black)
                .frame(width: 36, height: 36)
                .background(Circle().fill(mio ? Color.black : Color.white))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(esta && voz.sonando ? "Pausar mensaje de voz" : "Reproducir mensaje de voz")
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill((mio ? Color.black : Color.white).opacity(0.22)).frame(height: 4)
                    Capsule().fill(mio ? Color.black : Color.white).frame(width: g.size.width * avance, height: 4)
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onEnded { v in
                    if esta { voz.saltar(a: v.location.x / g.size.width) }
                })
            }
            .frame(width: 120, height: 26)
            Text(Formato.duracion(esta && voz.tiempo > 0 ? Int(voz.tiempo) : (duracion ?? 0)))
                .font(.caption.monospacedDigit()).opacity(0.75)
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .onDisappear { if esta { voz.detener() } }
    }
}
