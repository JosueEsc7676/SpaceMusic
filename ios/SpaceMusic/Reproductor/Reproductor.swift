import AVFoundation
import MediaPlayer
import Observation
import UIKit

enum ModoRepetir: Int, CaseIterable {
    case no, todo, una
}

/// Reproductor de música con cola, radio automática y controles en la pantalla bloqueada.
///
/// Usa AVQueuePlayer con la canción actual y la siguiente ya cargada, así el cambio
/// de canción es instantáneo y funciona con la pantalla apagada.
@MainActor
@Observable
final class Reproductor {
    static let compartido = Reproductor()

    private(set) var cola: [Cancion] = []
    private(set) var indice = 0
    private(set) var sonando = false
    private(set) var cargando = false
    private(set) var tiempo: Double = 0
    private(set) var duracion: Double = 0
    private(set) var aleatorio = false
    private(set) var repetir: ModoRepetir = .no
    /// Al terminar la cola sigue con canciones parecidas.
    var radioAutomatica = true {
        didSet { UserDefaults.standard.set(radioAutomatica, forKey: "radioAutomatica") }
    }
    private(set) var contexto = "otro"
    private(set) var error: String?

    var actual: Cancion? { cola.indices.contains(indice) ? cola[indice] : nil }
    var siguientes: [Cancion] { indice + 1 < cola.count ? Array(cola[(indice + 1)...]) : [] }

    @ObservationIgnored private let jugador = AVQueuePlayer()
    @ObservationIgnored private var itemActual: AVPlayerItem?
    @ObservationIgnored private var itemSiguiente: AVPlayerItem?
    @ObservationIgnored private var indiceSiguiente: Int?
    @ObservationIgnored private var obsItem: NSKeyValueObservation?
    @ObservationIgnored private var obsEstado: NSKeyValueObservation?
    @ObservationIgnored private var obsControl: NSKeyValueObservation?
    @ObservationIgnored private var obsTiempo: Any?
    @ObservationIgnored private var ordenOriginal: [Cancion] = []
    @ObservationIgnored private var pidiendoRadio: UUID?
    @ObservationIgnored private var versionCola = UUID()
    @ObservationIgnored private var escuchado: Double = 0
    @ObservationIgnored private var ultimoTick: Double = 0
    @ObservationIgnored private var registrada = false
    @ObservationIgnored private var anteriores: [Int] = []
    @ObservationIgnored private var portadaNowPlaying: (id: Int, arte: MPMediaItemArtwork)?
    @ObservationIgnored private var saltosSeguidos = 0

    private init() {
        radioAutomatica = UserDefaults.standard.object(forKey: "radioAutomatica") as? Bool ?? true
        jugador.actionAtItemEnd = .advance
        jugador.automaticallyWaitsToMinimizeStalling = true
        configurarAudio()
        configurarControlesRemotos()
        observar()
    }

    // MARK: - Reproducir

    /// Empieza una lista desde una canción. `contexto` se registra en el servidor (album, playlist, buscar…).
    func reproducir(_ canciones: [Cancion], desde i: Int = 0, contexto: String = "otro", mezclar: Bool = false) {
        guard !canciones.isEmpty else { return }
        registrarEscucha()
        self.contexto = contexto
        versionCola = UUID()
        ordenOriginal = canciones
        var lista = canciones
        var inicio = min(max(i, 0), canciones.count - 1)
        if mezclar || aleatorio {
            aleatorio = true
            let primera = mezclar && i == 0 ? lista.randomElement()! : lista[inicio]
            lista.removeAll { $0.id == primera.id }
            lista.shuffle()
            lista.insert(primera, at: 0)
            inicio = 0
        }
        cola = lista
        indice = inicio
        cargarActual(reproducir: true)
    }

    /// Una canción suelta: suena y después sigue la radio con canciones parecidas.
    func reproducirSuelta(_ c: Cancion, contexto: String = "otro") {
        reproducir([c], desde: 0, contexto: contexto)
        Task { await pedirRadio(cantidad: 30) }
    }

    func alternar() {
        guard actual != nil else { return }
        if jugador.timeControlStatus != .paused { pausar() } else { continuar() }
    }

    /// Refleja las ediciones en la cola y en la pantalla bloqueada.
    func actualizar(_ cancion: Cancion) {
        for i in cola.indices where cola[i].id == cancion.id { cola[i] = cancion }
        for i in ordenOriginal.indices where ordenOriginal[i].id == cancion.id { ordenOriginal[i] = cancion }
        if actual?.id == cancion.id {
            actualizarNowPlaying()
            cargarPortadaNowPlaying(cancion)
        }
    }

    func pausar() {
        jugador.pause()
        avisarActividad()
    }

    func continuar() {
        if jugador.currentItem == nil, actual != nil {
            cargarActual(reproducir: true)
            return
        }
        try? AVAudioSession.sharedInstance().setActive(true)
        jugador.play()
        avisarActividad()
    }

    func siguiente() {
        guard actual != nil else { return }
        if itemSiguiente != nil, repetir != .una, jugador.items().count > 1 {
            jugador.advanceToNextItem()
        } else if let j = calcularSiguiente(manual: true) {
            registrarEscucha()
            indice = j
            cargarActual(reproducir: true)
        } else if radioAutomatica {
            Task {
                await pedirRadio(cantidad: 20)
                if indice + 1 < cola.count {
                    registrarEscucha()
                    indice += 1
                    cargarActual(reproducir: true)
                }
            }
        }
    }

    func anterior() {
        guard actual != nil else { return }
        if tiempo > 3 || indice == 0 {
            buscar(0)
            return
        }
        registrarEscucha()
        indice -= 1
        cargarActual(reproducir: true)
    }

    func buscar(_ segundos: Double) {
        guard segundos.isFinite else { return }
        let destino = max(0, duracion > 0 ? min(segundos, duracion) : segundos)
        let t = CMTime(seconds: destino, preferredTimescale: 600)
        jugador.seek(to: t, toleranceBefore: .zero, toleranceAfter: .zero)
        tiempo = destino
        ultimoTick = destino
        actualizarNowPlaying()
    }

    func saltarA(_ i: Int) {
        guard cola.indices.contains(i) else { return }
        registrarEscucha()
        indice = i
        cargarActual(reproducir: true)
    }

    func detener() {
        registrarEscucha()
        jugador.pause()
        jugador.removeAllItems()
        itemActual = nil
        itemSiguiente = nil
        versionCola = UUID()
        cola = []
        indice = 0
        sonando = false
        tiempo = 0
        duracion = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: - Cola

    func agregarSiguiente(_ c: Cancion) {
        if cola.isEmpty { reproducir([c]); return }
        cola.removeAll { $0.id == c.id && $0.id != actual?.id }
        if let pos = cola.firstIndex(where: { $0.id == actual?.id }) { indice = pos }
        cola.insert(c, at: min(indice + 1, cola.count))
        prepararSiguiente()
    }

    func agregarAlFinal(_ canciones: [Cancion]) {
        if cola.isEmpty { reproducir(canciones); return }
        let ids = Set(cola.map(\.id))
        cola.append(contentsOf: canciones.filter { !ids.contains($0.id) })
        prepararSiguiente()
    }

    /// Quita una canción de "A continuación" (posición relativa a la cola completa).
    func quitar(en i: Int) {
        guard cola.indices.contains(i), i != indice else { return }
        cola.remove(at: i)
        if i < indice { indice -= 1 }
        prepararSiguiente()
    }

    func moverSiguientes(desde origen: IndexSet, hacia destino: Int) {
        var resto = siguientes
        resto.move(fromOffsets: origen, toOffset: destino)
        cola = Array(cola[...indice]) + resto
        prepararSiguiente()
    }

    func limpiarSiguientes() {
        guard actual != nil else { return }
        cola = Array(cola[...indice])
        prepararSiguiente()
    }

    func cambiarAleatorio() {
        aleatorio.toggle()
        guard let a = actual else { return }
        if aleatorio {
            var resto = cola
            resto.remove(at: indice)
            resto.shuffle()
            cola = [a] + resto
            indice = 0
        } else if !ordenOriginal.isEmpty {
            let extra = cola.filter { c in !ordenOriginal.contains(where: { $0.id == c.id }) }
            cola = ordenOriginal + extra
            indice = cola.firstIndex(where: { $0.id == a.id }) ?? 0
        }
        prepararSiguiente()
    }

    func cambiarRepetir() {
        let todos = ModoRepetir.allCases
        repetir = todos[(repetir.rawValue + 1) % todos.count]
        prepararSiguiente()
    }

    // MARK: - Carga de canciones

    private func elemento(para c: Cancion) -> AVPlayerItem? {
        let url: URL
        if let local = Descargas.compartido.archivo(de: c.id) {
            url = local
        } else if let remota = API.audioURL(c.id) {
            url = remota
        } else {
            return nil
        }
        let item = AVPlayerItem(asset: AVURLAsset(url: url))
        item.preferredForwardBufferDuration = 5
        return item
    }

    private func cargarActual(reproducir: Bool) {
        guard let c = actual, let item = elemento(para: c) else { return }
        error = nil
        jugador.removeAllItems()
        itemSiguiente = nil
        indiceSiguiente = nil
        itemActual = item
        jugador.insert(item, after: nil)
        observarEstado(de: item)
        reiniciarConteo()
        tiempo = 0
        duracion = Double(c.duracionSegundos)
        cargando = true
        if reproducir {
            try? AVAudioSession.sharedInstance().setActive(true)
            jugador.playImmediately(atRate: 1)
        }
        prepararSiguiente()
        cambioDeCancion()
    }

    /// Deja lista la siguiente canción dentro del reproductor.
    private func prepararSiguiente() {
        for it in jugador.items() where it !== jugador.currentItem { jugador.remove(it) }
        itemSiguiente = nil
        indiceSiguiente = nil
        if let pos = cola.firstIndex(where: { $0.id == actual?.id }), pos != indice { indice = pos }
        if radioAutomatica, repetir == .no, cola.count - indice <= 3, actual != nil {
            Task { await pedirRadio(cantidad: 20) }
        }
        guard jugador.currentItem != nil, let j = calcularSiguiente(manual: false),
              let item = elemento(para: cola[j]) else { return }
        jugador.insert(item, after: jugador.currentItem)
        itemSiguiente = item
        indiceSiguiente = j
    }

    private func calcularSiguiente(manual: Bool) -> Int? {
        if repetir == .una && !manual { return indice }
        if indice + 1 < cola.count { return indice + 1 }
        if repetir != .no && !cola.isEmpty { return 0 }
        return nil
    }

    /// El reproductor pasó solo a otra canción (o se terminó la cola).
    private func elementoCambio(_ nuevo: AVPlayerItem?) {
        // El aviso llega con un poco de retraso: si el reproductor ya cambió otra vez
        // (por ejemplo al elegir otra canción), se ignora.
        guard nuevo === jugador.currentItem else { return }
        guard let nuevo else {
            if itemActual != nil {
                // Se terminó todo
                registrarEscucha()
                sonando = false
                itemActual = nil
                actualizarNowPlaying()
            }
            return
        }
        guard nuevo === itemSiguiente, let j = indiceSiguiente else { return }
        registrarEscucha()
        indice = j
        itemActual = nuevo
        itemSiguiente = nil
        indiceSiguiente = nil
        observarEstado(de: nuevo)
        reiniciarConteo()
        tiempo = 0
        duracion = Double(actual?.duracionSegundos ?? 0)
        prepararSiguiente()
        cambioDeCancion()
    }

    private func cambioDeCancion() {
        guard let c = actual else { return }
        anteriores.removeAll { $0 == c.id }
        anteriores.insert(c.id, at: 0)
        if anteriores.count > 4 { anteriores.removeLast() }
        actualizarNowPlaying()
        cargarPortadaNowPlaying(c)
        avisarActividad()
        Letras.compartido.cargar(c)
    }

    /// Pide canciones parecidas al servidor y las agrega a la cola.
    func pedirRadio(cantidad: Int) async {
        let version = versionCola
        guard pidiendoRadio != version, let semilla = cola.last ?? actual else { return }
        pidiendoRadio = version
        defer { if pidiendoRadio == version { pidiendoRadio = nil } }
        let excluir = cola.suffix(150).map { String($0.id) }.joined(separator: ",")
        let previas = anteriores.filter { $0 != semilla.id }.prefix(3).map(String.init).joined(separator: ",")
        var ruta = "/api/radio/\(semilla.id)?cantidad=\(cantidad)"
        if !excluir.isEmpty { ruta += "&excluir=\(excluir)" }
        if !previas.isEmpty { ruta += "&anteriores=\(previas)" }
        guard let nuevas: [Cancion] = try? await API.get(ruta), !nuevas.isEmpty else { return }
        guard version == versionCola, actual != nil else { return }
        let ids = Set(cola.map(\.id))
        let filtradas = nuevas.filter { !ids.contains($0.id) }
        guard !filtradas.isEmpty else { return }
        cola.append(contentsOf: filtradas)
        if itemSiguiente == nil, jugador.currentItem != nil { prepararSiguiente() }
    }

    // MARK: - Observadores

    private func observar() {
        obsItem = jugador.observe(\.currentItem, options: [.new]) { [weak self] p, _ in
            let item = p.currentItem
            Task { @MainActor in self?.elementoCambio(item) }
        }
        obsControl = jugador.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let estado = p.timeControlStatus
            Task { @MainActor in
                guard let self else { return }
                self.sonando = estado == .playing
                self.cargando = estado == .waitingToPlayAtSpecifiedRate
                self.actualizarNowPlaying()
            }
        }
        obsTiempo = jugador.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
                                                    queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t.seconds) }
        }
        let centro = NotificationCenter.default
        centro.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            let info = n.userInfo
            MainActor.assumeIsolated { self?.interrupcion(info) }
        }
        centro.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
            // Al desconectar audífonos se pausa (como en todas las apps de música)
            let razon = (n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            if razon == .oldDeviceUnavailable { MainActor.assumeIsolated { self?.pausar() } }
        }
        centro.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: nil, queue: .main) { [weak self] n in
            let item = n.object as? AVPlayerItem
            MainActor.assumeIsolated { self?.falloItem(item) }
        }
    }

    private func observarEstado(de item: AVPlayerItem) {
        obsEstado = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            let estado = it.status
            let dur = it.duration.seconds
            Task { @MainActor in
                guard let self, it === self.itemActual else { return }
                if estado == .failed { self.falloItem(it) }
                if estado == .readyToPlay {
                    self.saltosSeguidos = 0
                    if dur.isFinite, dur > 0 { self.duracion = dur }
                    self.actualizarNowPlaying()
                }
            }
        }
    }

    private func falloItem(_ item: AVPlayerItem?) {
        guard let item, item === itemActual else { return }
        error = "No se pudo reproducir “\(actual?.nombre ?? "")”"
        saltosSeguidos += 1
        // Salta a la siguiente, sin entrar en un bucle si fallan todas
        if saltosSeguidos < 4, calcularSiguiente(manual: true) != nil {
            indice = calcularSiguiente(manual: true)!
            cargarActual(reproducir: true)
        } else {
            jugador.pause()
        }
    }

    private func tick(_ t: Double) {
        guard t.isFinite else { return }
        tiempo = t
        if let d = jugador.currentItem?.duration.seconds, d.isFinite, d > 0 { duracion = d }
        if sonando, t > ultimoTick, t - ultimoTick < 2.5 { escuchado += t - ultimoTick }
        ultimoTick = t
        let umbral = duracion > 0 ? min(30, duracion * 0.5) : 30
        if !registrada, escuchado >= umbral { registrarEscucha(forzar: true) }
    }

    private func reiniciarConteo() {
        escuchado = 0
        ultimoTick = 0
        registrada = false
    }

    /// Registra en el servidor que se escuchó la canción (después de 30 s o la mitad).
    private func registrarEscucha(forzar: Bool = false) {
        guard let c = actual, !registrada else { return }
        let umbral = duracion > 0 ? min(30, duracion * 0.5) : 30
        guard forzar || escuchado >= umbral else { return }
        registrada = true
        let cuerpo: [String: Any] = ["cancionId": c.id, "contexto": contexto, "segundos": Int(escuchado.rounded())]
        Task { try? await API.hacer("POST", "/api/reproducciones", cuerpo) }
    }

    /// Avisa a los amigos lo que estás escuchando.
    private func avisarActividad() {
        guard let c = actual else { return }
        let cuerpo: [String: Any] = ["cancionId": c.id, "sonando": jugador.rate > 0]
        Task { try? await API.hacer("POST", "/api/actividad", cuerpo) }
    }

    private func interrupcion(_ info: [AnyHashable: Any]?) {
        guard let raw = info?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let tipo = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if tipo == .ended, let o = info?[AVAudioSessionInterruptionOptionKey] as? UInt,
           AVAudioSession.InterruptionOptions(rawValue: o).contains(.shouldResume) {
            continuar()
        }
    }

    // MARK: - Pantalla bloqueada y Centro de control

    private func configurarAudio() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default, policy: .longFormAudio)
    }

    private func configurarControlesRemotos() {
        let cc = MPRemoteCommandCenter.shared()
        cc.playCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.continuar() }; return .success }
        cc.pauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.pausar() }; return .success }
        cc.togglePlayPauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.alternar() }; return .success }
        cc.nextTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.siguiente() }; return .success }
        cc.previousTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.anterior() }; return .success }
        cc.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            MainActor.assumeIsolated { self?.buscar(e.positionTime) }
            return .success
        }
        cc.likeCommand.isEnabled = false
        cc.dislikeCommand.isEnabled = false
    }

    private func actualizarNowPlaying() {
        guard let c = actual else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: c.nombre,
            MPMediaItemPropertyArtist: c.artista,
            MPMediaItemPropertyAlbumTitle: c.album,
            MPMediaItemPropertyPlaybackDuration: duracion,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: tiempo,
            MPNowPlayingInfoPropertyPlaybackRate: sonando ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let p = portadaNowPlaying, p.id == c.id { info[MPMediaItemPropertyArtwork] = p.arte }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func cargarPortadaNowPlaying(_ c: Cancion) {
        guard let ruta = c.portadaUrl else { return }
        Task {
            guard let img = await CacheImagenes.compartida.imagen(ruta), actual?.id == c.id else { return }
            let arte = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
            portadaNowPlaying = (c.id, arte)
            actualizarNowPlaying()
        }
    }
}
