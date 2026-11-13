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
    private(set) var cargando = false {
        didSet { if cargando != oldValue { programarEspera() } }
    }
    /// Indicador de espera visible: solo si la carga dura más de 0,6 s (evita parpadeos y ruedas eternas en cada cambio).
    private(set) var esperaVisible = false
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
    /// Tiempo exacto del audio para la letra palabra a palabra. Es una lectura directa: no provoca redibujos.
    var tiempoExacto: Double {
        let t = jugador.currentTime().seconds
        return t.isFinite ? t : tiempo
    }
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
    @ObservationIgnored private var intencionSonar = false
    @ObservationIgnored private var tareaEspera: Task<Void, Never>?
    /// La siguiente canción se precarga cuando la actual ya está lista, para no competir por la red al arrancar.
    @ObservationIgnored private var siguientePendiente = false
    /// Vigila que la canción empiece: si no carga a tiempo se reintenta una vez y luego se explica el fallo.
    @ObservationIgnored private var vigilante: Task<Void, Never>?
    @ObservationIgnored private var reintentada: Int?
    @ObservationIgnored private var guardadaAlEscuchar = false

    private init() {
        radioAutomatica = UserDefaults.standard.object(forKey: "radioAutomatica") as? Bool ?? true
        jugador.actionAtItemEnd = .advance
        jugador.automaticallyWaitsToMinimizeStalling = true
        configurarAudio()
        configurarControlesRemotos()
        observar()
    }

    private struct EstadoGuardado: Codable {
        let cola: [Cancion]
        let original: [Cancion]
        let indice: Int
        let tiempo: Double
        let contexto: String
        let aleatorio: Bool
        let repetir: Int
    }
    @ObservationIgnored private var subidaVolumen: Task<Void, Never>?
    var finTemporizador: Date?
    var dormirAlTerminar = false
    @ObservationIgnored private var temporizador: Task<Void, Never>?
    @ObservationIgnored private var ultimoGuardado = Date.distantPast
    @ObservationIgnored private var restaurando = false
    @ObservationIgnored private var reanudarTrasInterrupcion = false

    func programarSueno(minutos: Int?) {
        temporizador?.cancel()
        finTemporizador = nil
        jugador.volume = 1
        dormirAlTerminar = false
        guard let minutos else { return }
        finTemporizador = Date().addingTimeInterval(Double(minutos) * 60)
        temporizador = Task {
            let duracion = Double(minutos * 60)
            let inicio = Date()
            while !Task.isCancelled {
                let restante = duracion - Date().timeIntervalSince(inicio)
                if restante <= 0 { break }
                jugador.volume = Float(min(1, restante / 30))
                try? await Task.sleep(for: .milliseconds(250))
            }
            guard !Task.isCancelled else { return }
            pausar(); finTemporizador = nil; jugador.volume = 1
        }
    }

    func subirVolumenDurante(_ segundos: Int) {
        subidaVolumen?.cancel()
        jugador.volume = 0.05
        subidaVolumen = Task {
            let inicio = Date()
            while !Task.isCancelled {
                let fraccion = min(1, Date().timeIntervalSince(inicio) / Double(max(1, segundos)))
                jugador.volume = Float(0.05 + 0.95 * fraccion)
                if fraccion >= 1 { return }
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
    }

    func guardarEstado() {
        guard !restaurando, !cola.isEmpty else { return }
        let estado = EstadoGuardado(cola: cola, original: ordenOriginal, indice: indice, tiempo: tiempo,
                                    contexto: contexto, aleatorio: aleatorio, repetir: repetir.rawValue)
        if let datos = try? JSONEncoder().encode(estado) { CopiaLocal.guardar(datos, en: CopiaLocal.archivo("reproductor")) }
    }

    func restaurar() {
        guard cola.isEmpty, let url = CopiaLocal.archivo("reproductor"),
              let datos = try? Data(contentsOf: url), let estado = try? JSONDecoder().decode(EstadoGuardado.self, from: datos),
              estado.cola.indices.contains(estado.indice), estado.tiempo.isFinite else { return }
        restaurando = true
        cola = estado.cola; ordenOriginal = estado.original; indice = estado.indice
        contexto = estado.contexto; aleatorio = estado.aleatorio
        repetir = ModoRepetir(rawValue: estado.repetir) ?? .no
        cargarActual(reproducir: false)
        buscar(estado.tiempo)
        restaurando = false
    }

    // MARK: - Reproducir

    /// Empieza una lista desde una canción. `contexto` se registra en el servidor (album, playlist, buscar…).
    func reproducir(_ canciones: [Cancion], desde i: Int = 0, contexto: String = "otro", mezclar: Bool = false) {
        guard !canciones.isEmpty else { return }
        registrarEscucha()
        self.contexto = contexto
        versionCola = UUID()
        let vigentes = canciones.map { CancionesEditadas.compartidas.actual($0) }
        ordenOriginal = vigentes
        var lista = vigentes
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
        if radioAutomatica || contexto == "radio" { Task { await pedirRadio(cantidad: 30) } }
    }

    func alternar() {
        guard actual != nil else { return }
        if jugador.timeControlStatus != .paused { pausar() } else { continuar() }
    }

    /// Refleja las ediciones en la cola y en la pantalla bloqueada.
    func actualizar(_ cancion: Cancion) {
        CancionesEditadas.compartidas.guardar(cancion)
        for i in cola.indices where cola[i].id == cancion.id { cola[i] = cancion }
        for i in ordenOriginal.indices where ordenOriginal[i].id == cancion.id { ordenOriginal[i] = cancion }
        NotificationCenter.default.post(name: .cancionActualizada, object: cancion)
        if actual?.id == cancion.id {
            actualizarNowPlaying()
            cargarPortadaNowPlaying(cancion)
        }
    }

    func pausar() {
        subidaVolumen?.cancel()
        intencionSonar = false
        jugador.pause()
        guardarEstado()
        avisarActividad()
    }

    func continuar() {
        intencionSonar = true
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
            let version = versionCola
            let posicion = indice
            Task {
                await pedirRadio(cantidad: 20)
                guard version == versionCola, posicion == indice else { return }
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

    var listaParaBuscar: Bool { jugador.currentItem?.status == .readyToPlay }

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
        intencionSonar = false
        registrarEscucha()
        programarSueno(minutos: nil)
        if let url = CopiaLocal.archivo("reproductor") { try? FileManager.default.removeItem(at: url) }
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
        let item = AVPlayerItem(asset: AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false]))
        item.preferredForwardBufferDuration = 5
        return item
    }

    private func cargarActual(reproducir: Bool) {
        intencionSonar = reproducir
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
        vigilar(item, cancion: c)
        // Antes se precargaba la siguiente aquí mismo y su descarga competía con la canción que se pedía.
        siguientePendiente = true
        cambioDeCancion()
    }

    /// Si la canción no avanza, reintenta una vez (renovando la sesión por si el permiso del audio venció) y,
    /// si vuelve a fallar, salta a la siguiente con el motivo. Mientras sigan llegando datos se espera:
    /// una conexión lenta no es un fallo (antes se saltaban canciones que solo tardaban en cargar).
    private func vigilar(_ item: AVPlayerItem, cancion c: Cancion) {
        vigilante?.cancel()
        vigilante = Task { @MainActor [weak self] in
            var cargado = 0.0, sinAvance = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                guard let self, !Task.isCancelled, item === self.itemActual, item.status != .readyToPlay, self.intencionSonar else { return }
                let ahora = item.loadedTimeRanges.map { $0.timeRangeValue.end.seconds }.max() ?? 0
                if ahora > cargado + 0.1 { cargado = ahora; sinAvance = 0 } else { sinAvance += 1 }
                if sinAvance >= 5 { break }   // 20 s sin recibir nada
            }
            guard let self, !Task.isCancelled, item === self.itemActual, item.status != .readyToPlay, self.intencionSonar else { return }
            if self.reintentada != c.id {
                self.reintentada = c.id
                await Biblioteca.compartida.renovarSesionSiHaceFalta()
                guard item === self.itemActual else { return }
                self.cargarActual(reproducir: true)
            } else {
                self.falloItem(item, motivo: "el servidor no respondió a tiempo")
            }
        }
    }

    /// Explicación legible del error de AVFoundation (permiso, archivo no encontrado, formato…).
    private func motivo(_ item: AVPlayerItem) -> String? {
        let ns = (item.error as NSError?)
        let evento = item.errorLog()?.events.last
        if let codigo = evento?.errorStatusCode, codigo != 0 {
            switch codigo {
            case 401, 403: return "el servidor rechazó el permiso (\(codigo)); vuelve a iniciar sesión"
            case 404: return "el archivo ya no está en el servidor (404)"
            case 500...599: return "error del servidor (\(codigo))"
            default: return "respuesta \(codigo) del servidor"
            }
        }
        if let ns, ns.domain == NSURLErrorDomain { return "sin conexión con el servidor" }
        if let ns, ns.code == -11828 || ns.code == -11829 { return "este formato no se puede reproducir en iPhone" }
        return ns?.localizedFailureReason ?? ns?.localizedDescription
    }

    /// Ecualizador o visualizador encendidos/apagados: se conecta (o quita) el procesador en las canciones cargadas.
    func reconectarProcesador() {
        for item in jugador.items() { ProcesadorAudio.compartido.conectar(item) }
    }

    private func programarEspera() {
        tareaEspera?.cancel()
        guard cargando else { esperaVisible = false; return }
        tareaEspera = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(600))
            guard let self, !Task.isCancelled, self.cargando else { return }
            self.esperaVisible = true
        }
    }

    /// La canción actual ya está completa en memoria (no compite con la siguiente por la conexión).
    private var actualCompleta: Bool {
        guard let item = jugador.currentItem else { return false }
        let total = item.duration.seconds.isFinite ? item.duration.seconds : duracion
        let fin = item.loadedTimeRanges.map { $0.timeRangeValue.end.seconds }.max() ?? 0
        return total > 0 && fin >= total - 1
    }

    /// Una sola descarga a la vez: la siguiente canción (y guardar la actual para sin conexión) solo empiezan
    /// cuando la actual ya bajó completa, o si le quedan menos de 40 s.
    private func quizaPrecargar() {
        guard siguientePendiente, jugador.currentItem != nil else { return }
        let restante = duracion > 0 ? duracion - tiempo : .infinity
        if restante < 40 || (actualCompleta && !Descargas.compartido.guardandoAlEscuchar) { prepararSiguiente() }
    }

    /// Deja lista la siguiente canción dentro del reproductor.
    private func prepararSiguiente() {
        guard contexto != "juntos-oyente" else { return }
        let restante = duracion > 0 ? duracion - tiempo : .infinity
        if jugador.currentItem != nil, itemActual != nil, !actualCompleta, restante >= 40, cola.count - indice > 1 {
            // Aún se está bajando la actual: se deja pendiente para no repartir la conexión entre dos canciones.
            for it in jugador.items() where it !== jugador.currentItem { jugador.remove(it) }
            itemSiguiente = nil
            indiceSiguiente = nil
            siguientePendiente = true
            if radioAutomatica, repetir == .no, cola.count - indice <= 3, actual != nil {
                Task { await pedirRadio(cantidad: 20) }
            }
            return
        }
        siguientePendiente = false
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
        let dormir = dormirAlTerminar
        if dormir { jugador.pause(); intencionSonar = false; dormirAlTerminar = false }
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
        guardarEstado()
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
        if jugador.currentItem == nil, intencionSonar, indice + 1 < cola.count {
            indice += 1
            cargarActual(reproducir: true)
            return
        }
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
        obsEstado = item.observe(\.status, options: [.initial, .new]) { [weak self] it, _ in
            let estado = it.status
            let dur = it.duration.seconds
            Task { @MainActor in
                guard let self, it === self.itemActual else { return }
                if estado == .failed { self.falloItem(it) }
                if estado == .readyToPlay { self.vigilante?.cancel(); self.reintentada = nil }
                if estado == .readyToPlay {
                    self.saltosSeguidos = 0
                    if dur.isFinite, dur > 0 { self.duracion = dur }
                    self.actualizarNowPlaying()
                    ProcesadorAudio.compartido.conectar(it)
                    self.quizaPrecargar()
                }
            }
        }
    }

    private func falloItem(_ item: AVPlayerItem?, motivo explicado: String? = nil) {
        guard let item, item === itemActual else { return }
        vigilante?.cancel()
        let causa = explicado ?? motivo(item)
        error = "No se pudo reproducir “\(actual?.nombre ?? "")”" + (causa.map { ": \($0)" } ?? "")
        saltosSeguidos += 1
        // Salta a la siguiente, sin entrar en un bucle si fallan todas
        if saltosSeguidos < 4, calcularSiguiente(manual: true) != nil {
            indice = calcularSiguiente(manual: true)!
            cargarActual(reproducir: true)
        } else {
            pausar()
            cargando = false
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
        // Como la web: tras 20 s de escucha se guarda la canción para tenerla sin conexión, pero solo cuando
        // ya bajó completa, para no competir con lo que suena ni con la siguiente.
        if escuchado >= 20, !guardadaAlEscuchar, actualCompleta, let c = actual {
            guardadaAlEscuchar = true
            Descargas.compartido.guardarAlEscucharla(c)
        }
        quizaPrecargar()
        if Date().timeIntervalSince(ultimoGuardado) >= 5 { guardarEstado(); ultimoGuardado = Date() }
    }

    private func reiniciarConteo() {
        guardadaAlEscuchar = false
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
        if tipo == .began {
            reanudarTrasInterrupcion = sonando || cargando
            pausar()
        }
        if tipo == .ended, reanudarTrasInterrupcion, let o = info?[AVAudioSessionInterruptionOptionKey] as? UInt,
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
        cc.likeCommand.isEnabled = true
        cc.likeCommand.localizedTitle = "Me gusta"
        cc.likeCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let c = self?.actual else { return }
                Biblioteca.compartida.alternarFavorito(c)
            }
            return .success
        }
        cc.dislikeCommand.isEnabled = false
    }

    private func actualizarNowPlaying() {
        guard let c = actual else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        MPRemoteCommandCenter.shared().likeCommand.isActive = Biblioteca.compartida.esFavorita(c.id)
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
