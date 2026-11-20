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

    /// Termina y devuelve el audio y su duración (nada si duró menos de un segundo).
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

/// Mensaje de voz: reproducir/pausar y avance.
struct AudioMensaje: View {
    let url: URL?
    let duracion: Int?
    let mio: Bool
    @State private var jugador: AVPlayer?
    @State private var sonando = false
    @State private var avance: Double = 0
    @State private var observador: Any?

    var body: some View {
        HStack(spacing: 10) {
            Button { alternar() } label: {
                Image(systemName: sonando ? "pause.fill" : "play.fill").font(.title3)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(mio ? Color.black.opacity(0.15) : Color.white.opacity(0.12)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(sonando ? "Pausar mensaje de voz" : "Reproducir mensaje de voz")
            ProgressView(value: avance).tint(mio ? .black : .white).frame(width: 110)
            Text(Formato.duracion(duracion ?? 0)).font(.caption.monospacedDigit())
        }
        .padding(.horizontal, 12).padding(.vertical, 9)
        .onDisappear { jugador?.pause(); sonando = false }
    }

    private func alternar() {
        if jugador == nil, let url {
            let j = AVPlayer(url: url)
            jugador = j
            observador = j.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.2, preferredTimescale: 600), queue: .main) { t in
                let total = j.currentItem?.duration.seconds ?? Double(duracion ?? 0)
                MainActor.assumeIsolated {
                    if total.isFinite, total > 0 { avance = min(1, t.seconds / total) }
                    if avance >= 0.999 { sonando = false; j.seek(to: .zero); avance = 0 }
                }
            }
        }
        guard let j = jugador else { return }
        if sonando { j.pause(); sonando = false }
        else {
            if Reproductor.compartido.sonando { Reproductor.compartido.pausarLocal() }
            j.play(); sonando = true
        }
    }
}
