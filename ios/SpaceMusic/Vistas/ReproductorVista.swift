import AVKit
import CoreImage
import MediaPlayer
import SwiftUI

/// Reproductor a pantalla completa con el estilo de Apple Music:
/// portada a todo el ancho (desde arriba del todo) que se funde con el color de la canción, título y menú,
/// barra de tiempo, tres controles grandes, volumen del sistema y abajo Letra · AirPlay · Cola.
/// Se cierra deslizando hacia abajo. Aleatorio, repetir y radio viven en la cola; el resto, en el menú "…".
struct ReproductorVista: View {
    enum Panel { case portada, letra, cola }

    @State private var r = Reproductor.compartido
    @State private var biblioteca = Biblioteca.compartida
    @State private var panel: Panel = .portada
    @State private var arrastrando: Double?
    @State private var colores = ColoresPortada.neutros
    @State private var desplazamiento: CGFloat = 0
    @State private var altoControles: CGFloat = 340

    var body: some View {
        GeometryReader { espacio in
            let altoPantalla = espacio.size.height + espacio.safeAreaInsets.top + espacio.safeAreaInsets.bottom
            let ocupadoAbajo = altoControles + espacio.safeAreaInsets.bottom
            ZStack(alignment: .top) {
                fondo(ancho: espacio.size.width, alto: altoPantalla, ocupadoAbajo: ocupadoAbajo, horizontal: espacio.size.width > espacio.size.height)
                if let c = r.actual {
                    if espacio.size.width > espacio.size.height {
                        horizontal(c, espacio.size)
                    } else {
                        vertical(c)
                    }
                } else {
                    EstadoVista(icono: "music.note", titulo: "No suena nada")
                }
                agarradera
            }
        }
        .background(colores.fondo.ignoresSafeArea())
        .offset(y: desplazamiento)
        .preferredColorScheme(.dark)
        .onAppear { desplazamiento = 0 }
        .onChange(of: r.actual?.id) { _, _ in arrastrando = nil }
        .onPreferenceChange(AltoControlesReproductor.self) { if $0 > 0 { altoControles = $0 } }
        .task(id: r.actual?.portadaUrl) { await calcularColores() }
        .accessibilityAction(.escape) { cerrar() }
        .hojasGlobales()
    }

    // MARK: - Cerrar deslizando

    private func cerrar() { Navegacion.compartida.reproductorAbierto = false }

    /// Arrastrar hacia abajo cierra; hacia los lados (solo sobre la portada) cambia de canción.
    private func gestoArrastre(cambiarCancion: Bool) -> some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .onChanged { g in
                if g.translation.height > 0, abs(g.translation.height) > abs(g.translation.width) {
                    desplazamiento = g.translation.height
                }
            }
            .onEnded { g in
                let vertical = abs(g.translation.height) > abs(g.translation.width)
                if vertical, desplazamiento > 140 || g.predictedEndTranslation.height > 420 {
                    cerrar()
                } else {
                    withAnimation(.spring(duration: 0.35)) { desplazamiento = 0 }
                    if cambiarCancion, !vertical {
                        if g.translation.width < -60 { r.siguiente() }
                        if g.translation.width > 60 { r.anterior() }
                    }
                }
            }
    }

    private var agarradera: some View {
        Capsule()
            .fill(.white.opacity(0.45))
            .frame(width: 38, height: 5)
            .frame(maxWidth: .infinity, minHeight: 30)
            .contentShape(Rectangle())
            .gesture(gestoArrastre(cambiarCancion: false))
            .accessibilityElement()
            .accessibilityLabel("Cerrar reproductor")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { cerrar() }
    }

    // MARK: - Distribución

    private func vertical(_ c: Cancion) -> some View {
        VStack(spacing: 0) {
            if panel == .portada {
                // Hueco transparente: la portada se ve detrás (ver `fondo`) y se funde con los controles.
                Color.clear
                    .frame(maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .gesture(gestoArrastre(cambiarCancion: true))
                    .accessibilityHidden(true)
                VStack(spacing: 0) {
                    infoCancion(c)
                    controles(c)
                }
                .padding(.horizontal, 26)
                .padding(.bottom, 8)
                .background { GeometryReader { g in Color.clear.preference(key: AltoControlesReproductor.self, value: g.size.height) } }
            } else {
                cabeceraCompacta(c)
                    .padding(.horizontal, 26)
                    .padding(.top, 30)
                    .contentShape(Rectangle())
                    .gesture(gestoArrastre(cambiarCancion: false))
                Group {
                    if panel == .letra { LetraCompleta() } else { ColaVista() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .mask(desvanecido)
                .transition(.opacity)
                controles(c)
                    .padding(.horizontal, 26)
                    .padding(.bottom, 8)
            }
        }
    }

    private func horizontal(_ c: Cancion, _ tamano: CGSize) -> some View {
        HStack(spacing: 24) {
            Group {
                switch panel {
                case .portada:
                    Portada(ruta: c.portadaUrl, tamano: max(0, min(tamano.height - 40, tamano.width * 0.42)), radio: 12)
                        .shadow(color: .black.opacity(0.45), radius: 20, y: 10)
                        .gesture(gestoArrastre(cambiarCancion: true))
                case .letra: LetraCompleta().mask(desvanecido)
                case .cola: ColaVista().mask(desvanecido)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            ScrollView(showsIndicators: false) {
                VStack(spacing: 16) {
                    infoCancion(c)
                    controles(c)
                }
                .padding(.vertical, 20)
            }
            .frame(width: max(260, tamano.width * 0.44))
        }
        .padding(.horizontal, 24)
    }

    /// Fondo de toda la pantalla (incluidas la barra de estado y la del inicio).
    /// En modo portada, la imagen ocupa todo el ancho desde arriba y llega hasta justo encima del título,
    /// donde se desvanece en el color de la canción (como Apple Music).
    private func fondo(ancho: CGFloat, alto: CGFloat, ocupadoAbajo: CGFloat, horizontal: Bool) -> some View {
        ZStack(alignment: .top) {
            colores.fondo
            if panel != .portada || horizontal {
                ImagenRemota(ruta: r.actual?.portadaUrl)
                    .frame(width: ancho, height: alto)
                    .blur(radius: 70)
                    .opacity(0.5)
                    .scaleEffect(1.4)
                LinearGradient(colors: [.black.opacity(0.1), .black.opacity(0.5)], startPoint: .top, endPoint: .bottom)
            } else {
                let altoArte = max(ancho, alto - ocupadoAbajo + 80)
                ImagenRemota(ruta: r.actual?.portadaUrl, pixeles: 900)
                    .frame(width: ancho, height: altoArte)
                    .clipped()
                    .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.7),
                                                 .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
                    .accessibilityHidden(true)
            }
        }
        .frame(width: ancho, height: alto, alignment: .top)
        .clipped()
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.35), value: panel)
    }

    private var desvanecido: some View {
        LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.05),
                               .init(color: .black, location: 0.93), .init(color: .clear, location: 1)],
                       startPoint: .top, endPoint: .bottom)
    }

    // MARK: - Partes

    private func infoCancion(_ c: Cancion) -> some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(c.nombre)
                    .font(.title2.weight(.bold))
                    .lineLimit(1)
                Button { abrir(.artista(c.artista)) } label: {
                    Text(c.artista).font(.title3).foregroundStyle(colores.acento).lineLimit(1)
                }
                .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            botonFavorito(c)
            menuMas(c)
        }
        .padding(.bottom, 18)
    }

    private func cabeceraCompacta(_ c: Cancion) -> some View {
        HStack(spacing: 14) {
            Portada(ruta: c.portadaUrl, tamano: 64, radio: 8)
                .shadow(color: .black.opacity(0.35), radius: 8, y: 4)
                .onTapGesture { withAnimation(.spring(duration: 0.4)) { panel = .portada } }
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel("Volver a la portada")
            VStack(alignment: .leading, spacing: 2) {
                Text(c.nombre).font(.headline).lineLimit(1)
                Text(c.artista).font(.subheadline).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            botonFavorito(c)
            menuMas(c)
        }
        .padding(.bottom, 6)
    }

    private func botonFavorito(_ c: Cancion) -> some View {
        let fav = biblioteca.esFavorita(c.id)
        return Button { biblioteca.alternarFavorito(c) } label: {
            Image(systemName: fav ? "heart.fill" : "heart")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(fav ? Tema.rosa : .white)
                .symbolEffect(.bounce, value: fav)
                .frame(width: 32, height: 32)
                .background(Circle().fill(.white.opacity(0.14)))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(fav ? "Quitar de Me gusta" : "Me gusta")
    }

    /// Menú "…": las opciones de canción de toda la app más las del reproductor.
    private func menuMas(_ c: Cancion) -> some View {
        Menu {
            OpcionesCancion(cancion: c)
            Divider()
            Menu {
                ForEach([5, 10, 15, 30, 45, 60], id: \.self) { minutos in
                    Button("\(minutos) minutos") { r.programarSueno(minutos: minutos) }
                }
                Button("Al terminar esta canción") { r.programarSueno(minutos: nil); r.dormirAlTerminar = true }
                if r.finTemporizador != nil || r.dormirAlTerminar {
                    Button("Desactivar", role: .destructive) { r.programarSueno(minutos: nil) }
                }
            } label: {
                Label(textoTemporizador, systemImage: "moon.zzz")
            }
            if !c.album.isEmpty {
                Section(textoContexto) { Text(c.album) }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(Circle().fill(.white.opacity(0.14)))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("Más opciones")
    }

    private var textoTemporizador: String {
        if r.dormirAlTerminar { return "Temporizador: al terminar la canción" }
        if let fin = r.finTemporizador {
            let minutos = max(1, Int(ceil(fin.timeIntervalSinceNow / 60)))
            return "Temporizador: \(minutos) min"
        }
        return "Temporizador para dormir"
    }

    private func controles(_ c: Cancion) -> some View {
        VStack(spacing: 0) {
            barraTiempo
            HStack(spacing: 0) {
                Spacer(minLength: 0)
                Button { r.anterior() } label: {
                    Image(systemName: "backward.fill").font(.system(size: 32)).frame(width: 72, height: 64).contentShape(Rectangle())
                }
                .accessibilityLabel("Anterior")
                Spacer(minLength: 0)
                Button { r.alternar() } label: {
                    ZStack {
                        if r.esperaVisible && !r.sonando {
                            ProgressView().controlSize(.large).tint(.white)
                        } else {
                            Image(systemName: r.sonando ? "pause.fill" : "play.fill")
                                .font(.system(size: 46))
                                .contentTransition(.symbolEffect(.replace))
                        }
                    }
                    .frame(width: 80, height: 72)
                    .contentShape(Rectangle())
                }
                .accessibilityLabel(r.sonando ? "Pausar" : "Reproducir")
                Spacer(minLength: 0)
                Button { r.siguiente() } label: {
                    Image(systemName: "forward.fill").font(.system(size: 32)).frame(width: 72, height: 64).contentShape(Rectangle())
                }
                .accessibilityLabel("Siguiente")
                Spacer(minLength: 0)
            }
            .foregroundStyle(.white)
            .buttonStyle(.plain)
            .padding(.vertical, 10)

            if let e = r.error {
                Text(e).font(.caption).foregroundStyle(Tema.peligro).multilineTextAlignment(.center).padding(.bottom, 6)
            }

            HStack(spacing: 10) {
                Image(systemName: "speaker.fill").font(.caption).foregroundStyle(.white.opacity(0.6))
                VolumenSistema().frame(height: 34)
                Image(systemName: "speaker.wave.3.fill").font(.caption).foregroundStyle(.white.opacity(0.6))
            }
            .accessibilityElement(children: .contain)

            HStack(spacing: 0) {
                botonPanel(.letra, icono: "quote.bubble", nombre: "Letra")
                SelectorSalida().frame(width: 44, height: 44).frame(maxWidth: .infinity)
                    .accessibilityLabel("AirPlay y Bluetooth")
                botonPanel(.cola, icono: "list.bullet", nombre: "A continuación")
                    .overlay(alignment: .topTrailing) {
                        if r.aleatorio || r.repetir != .no {
                            Image(systemName: r.repetir == .una ? "repeat.1" : r.repetir == .todo ? "repeat" : "shuffle")
                                .font(.system(size: 10, weight: .bold))
                                .frame(width: 20, height: 20)
                                .background(Circle().fill(.white.opacity(0.22)))
                                .offset(x: -18, y: -2)
                                .allowsHitTesting(false)
                        }
                    }
            }
            .padding(.top, 10)
        }
    }

    private func botonPanel(_ p: Panel, icono: String, nombre: String) -> some View {
        let activo = panel == p
        return Button {
            withAnimation(.spring(duration: 0.4)) { panel = activo ? .portada : p }
        } label: {
            Image(systemName: icono)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(activo ? colores.fondo : .white.opacity(0.85))
                .frame(width: 44, height: 44)
                .background(Circle().fill(activo ? Color.white.opacity(0.9) : .clear))
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(nombre)
        .accessibilityAddTraits(activo ? .isSelected : [])
    }

    private var barraTiempo: some View {
        let total = max(r.duracion, 1)
        let valor = min(max(arrastrando ?? r.tiempo, 0), total)
        let activa = arrastrando != nil
        return VStack(spacing: 6) {
            GeometryReader { g in
                let ancho = max(g.size.width, 1)
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.22))
                    Capsule().fill(.white.opacity(activa ? 1 : 0.8))
                        .frame(width: ancho * valor / total)
                }
                .frame(height: activa ? 12 : 7)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { gesto in arrastrando = min(max(gesto.location.x / ancho, 0), 1) * total }
                    .onEnded { _ in
                        if let a = arrastrando { r.buscar(a) }
                        arrastrando = nil
                    })
            }
            .frame(height: 26)
            .animation(.spring(duration: 0.25), value: activa)
            HStack {
                Text(Formato.duracion(Int(valor))).monospacedDigit()
                Spacer()
                Text("-" + Formato.duracion(Int(max(0, total - valor)))).monospacedDigit()
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.white.opacity(activa ? 0.9 : 0.55))
        }
        .accessibilityElement()
        .accessibilityLabel("Posición")
        .accessibilityValue("\(Formato.duracion(Int(r.tiempo))) de \(Formato.duracion(Int(r.duracion)))")
        .accessibilityAdjustableAction { direccion in
            switch direccion {
            case .increment: r.buscar(min(r.tiempo + 10, r.duracion))
            case .decrement: r.buscar(max(r.tiempo - 10, 0))
            @unknown default: break
            }
        }
    }

    private var textoContexto: String {
        switch r.contexto {
        case "album": return "Desde el álbum"
        case "playlist": return "Desde la playlist"
        case "artista": return "Desde el artista"
        case "radio": return "Radio"
        case "mix": return "Mix"
        case "favoritos": return "Desde Me gusta"
        case "buscar": return "Desde la búsqueda"
        case "historial": return "Desde el historial"
        case "genero": return "Desde el género"
        case "descargas": return "Desde las descargas"
        default: return "Reproduciendo"
        }
    }

    private func abrir(_ d: Destino) {
        cerrar()
        Navegacion.compartida.ir(d)
    }

    private func calcularColores() async {
        guard let ruta = r.actual?.portadaUrl, !ruta.isEmpty else { colores = .neutros; return }
        let reducida = CacheImagenes.rutaReducida(ruta, pixeles: 640)
        guard let imagen = await CacheImagenes.compartida.imagen(reducida), !Task.isCancelled else { return }
        let nuevos = await Task.detached(priority: .userInitiated) { ColoresPortada.calcular(imagen) }.value
        guard !Task.isCancelled else { return }
        withAnimation(.easeInOut(duration: 0.6)) { colores = nuevos }
    }
}

/// Alto de título + controles, para que la portada llegue justo hasta ellos.
private struct AltoControlesReproductor: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Colores sacados de la portada (como hace Apple Music): un fondo oscuro legible y un acento claro.
struct ColoresPortada: Equatable {
    var fondo: Color
    var acento: Color

    static let neutros = ColoresPortada(fondo: Color(white: 0.11), acento: .white.opacity(0.7))
    private static let contexto = CIContext(options: [.workingColorSpace: NSNull()])

    static func calcular(_ imagen: UIImage) -> ColoresPortada {
        guard let cg = imagen.cgImage else { return neutros }
        let ci = CIImage(cgImage: cg)
        guard let filtro = CIFilter(name: "CIAreaAverage", parameters: [kCIInputImageKey: ci, kCIInputExtentKey: CIVector(cgRect: ci.extent)]),
              let salida = filtro.outputImage else { return neutros }
        var pixel = [UInt8](repeating: 0, count: 4)
        contexto.render(salida, toBitmap: &pixel, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBA8, colorSpace: nil)
        let promedio = UIColor(red: CGFloat(pixel[0]) / 255, green: CGFloat(pixel[1]) / 255, blue: CGFloat(pixel[2]) / 255, alpha: 1)
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        promedio.getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        // Oscuro para que el texto blanco siempre se lea; el acento conserva el tono de la portada.
        let fondo = UIColor(hue: h, saturation: min(s * 1.15, 0.85), brightness: min(max(b * 0.75, 0.16), 0.38), alpha: 1)
        let acento = s < 0.12 ? UIColor(white: 0.78, alpha: 1) : UIColor(hue: h, saturation: min(max(s, 0.3), 0.55), brightness: 0.97, alpha: 1)
        return ColoresPortada(fondo: Color(uiColor: fondo), acento: Color(uiColor: acento))
    }
}

/// Volumen del sistema (el mismo de los botones del iPhone).
struct VolumenSistema: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let v = MPVolumeView(frame: .zero)
        v.showsRouteButton = false
        v.tintColor = .white
        let punto = UIGraphicsImageRenderer(size: CGSize(width: 14, height: 14)).image { ctx in
            UIColor.white.setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: 14, height: 14))
        }
        v.setVolumeThumbImage(punto, for: .normal)
        return v
    }
    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

/// Botón de AirPlay y Bluetooth.
struct SelectorSalida: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = UIColor.white.withAlphaComponent(0.85)
        v.activeTintColor = UIColor(Tema.celeste)
        v.prioritizesVideoDevices = false
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

// MARK: - Letra

/// Letra sincronizada como Apple Music:
/// - la línea activa se calcula 10 veces por segundo con el tiempo exacto del audio (no cada medio segundo);
/// - se ilumina palabra por palabra (con los tiempos de la letra si los trae; si no, repartiendo la línea según su largo);
/// - las demás líneas quedan atenuadas y más desenfocadas cuanto más lejos están;
/// - la activa se coloca en el primer cuarto de la pantalla con un desplazamiento suave;
/// - al deslizar con el dedo todo se enfoca y la letra deja de seguir la canción unos segundos;
/// - tocar una línea salta a ese momento; las pausas largas muestran tres puntos que se van llenando.
struct LetraCompleta: View {
    @State private var r = Reproductor.compartido
    @State private var letras = Letras.compartido
    @State private var actual: Int?
    @State private var manual = false
    @State private var manualHasta = Date.distantPast

    var body: some View {
        Group {
            if letras.cargando {
                ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if letras.sincronizada {
                sincronizada
            } else if !letras.textoPlano.isEmpty {
                ScrollView(showsIndicators: false) {
                    Text(letras.textoPlano)
                        .font(.title2.weight(.bold))
                        .lineSpacing(10)
                        .foregroundStyle(.white.opacity(0.9))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 26)
                        .padding(.vertical, 24)
                }
            } else if let c = r.actual {
                VStack(spacing: 14) {
                    Image(systemName: "quote.bubble").font(.largeTitle).foregroundStyle(.white.opacity(0.5))
                    Text(letras.mensaje ?? "Esta canción no tiene letra todavía")
                        .foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center)
                    Button("Buscar la letra") { Task { await letras.buscarEnInternet(c, forzar: letras.mensaje != nil) } }
                        .buttonStyle(BotonSecundario())
                }
                .padding(.horizontal, 26)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private var sincronizada: some View {
        ScrollViewReader { lector in
            ScrollView(showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 30) {
                    ForEach(letras.lineas) { l in
                        LineaLetraVista(linea: l, activa: l.id == actual, pasada: (actual ?? -1) > l.id,
                                        distancia: actual.map { abs(l.id - $0) } ?? 2, enfocada: manual)
                            .id(l.id)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                manual = false
                                r.buscar(l.tiempo)
                                if !r.sonando { r.continuar() }
                            }
                            .accessibilityAddTraits(.isButton)
                            .accessibilityHint("Saltar a esta parte")
                    }
                }
                .padding(.horizontal, 26)
                .padding(.top, 36)
                .padding(.bottom, 380)
            }
            .simultaneousGesture(DragGesture(minimumDistance: 6).onChanged { _ in
                manual = true
                manualHasta = Date().addingTimeInterval(3)
            })
            .task(id: "\(letras.cancionId ?? 0)-\(letras.lineas.count)") {
                actual = nil
                while !Task.isCancelled {
                    let i = LectorLRC.indice(letras.lineas, en: r.tiempoExacto + 0.15)
                    let volverASeguir = manual && Date() >= manualHasta
                    if volverASeguir { manual = false }
                    if i != actual || volverASeguir {
                        let primera = actual == nil
                        actual = i
                        if !manual, let i {
                            if primera {
                                lector.scrollTo(i, anchor: UnitPoint(x: 0, y: 0.22))
                            } else {
                                withAnimation(.spring(response: 0.65, dampingFraction: 0.86)) {
                                    lector.scrollTo(i, anchor: UnitPoint(x: 0, y: 0.22))
                                }
                            }
                        }
                    }
                    try? await Task.sleep(for: .milliseconds(100))
                }
            }
        }
    }
}

/// Una línea de la letra.
struct LineaLetraVista: View {
    let linea: LineaLetra
    let activa: Bool
    let pasada: Bool
    let distancia: Int
    /// Mientras el usuario desliza, todas las líneas se ven nítidas (como en Apple Music).
    let enfocada: Bool
    @State private var r = Reproductor.compartido
    @ScaledMetric(relativeTo: .largeTitle) private var tamano: CGFloat = 31

    var body: some View {
        Group {
            if linea.interludio {
                PuntosInterludio(linea: linea, activa: activa)
            } else if activa {
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { _ in
                    karaoke(r.tiempoExacto)
                }
                .accessibilityLabel(linea.texto)
            } else {
                Text(linea.texto).foregroundStyle(.white.opacity(pasada ? 0.3 : 0.36))
            }
        }
        .font(.system(size: tamano, weight: .heavy))
        .lineSpacing(2)
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .blur(radius: activa || enfocada ? 0 : min(Double(distancia) * 0.9, 3.2))
        .scaleEffect(activa ? 1 : 0.97, anchor: .leading)
        .animation(.spring(response: 0.55, dampingFraction: 0.85), value: activa)
        .animation(.easeInOut(duration: 0.3), value: enfocada)
    }

    /// Cada palabra pasa de atenuada a blanca durante su propio tiempo, así la luz avanza con la voz.
    private func karaoke(_ t: Double) -> Text {
        let palabras = LectorLRC.tiemposPalabras(linea)
        return palabras.reduce(Text("")) { texto, p in
            let progreso = min(max((t - p.inicio) / max(p.fin - p.inicio, 0.05), 0), 1)
            return texto + Text(p.texto).foregroundColor(.white.opacity(0.36 + 0.64 * progreso))
        }
    }
}

/// Tres puntos de las pausas sin letra: se llenan uno tras otro mientras dura la pausa.
struct PuntosInterludio: View {
    let linea: LineaLetra
    let activa: Bool
    @State private var r = Reproductor.compartido

    var body: some View {
        Group {
            if activa {
                TimelineView(.animation(minimumInterval: 1.0 / 20)) { contexto in
                    let progreso = min(max((r.tiempoExacto - linea.tiempo) / max(linea.fin - linea.tiempo, 0.5), 0), 1)
                    let latido = 1 + 0.08 * sin(contexto.date.timeIntervalSinceReferenceDate * 3)
                    puntos { i in min(max(progreso * 3 - Double(i), 0), 1) }
                        .scaleEffect(latido, anchor: .leading)
                }
            } else {
                puntos { _ in 0 }
            }
        }
        .frame(height: 30, alignment: .leading)
        .accessibilityLabel("Pausa instrumental")
    }

    private func puntos(_ lleno: @escaping (Int) -> Double) -> some View {
        HStack(spacing: 9) {
            ForEach(0..<3, id: \.self) { i in
                Circle().fill(.white.opacity(0.3 + 0.7 * lleno(i))).frame(width: 11, height: 11)
            }
        }
    }
}

// MARK: - Cola

/// Lo que sonará después, ordenado y sin huecos: modos arriba, la canción actual y la lista.
/// Mantén pulsada una canción para moverla; desliza a la izquierda para quitarla.
struct ColaVista: View {
    @State private var r = Reproductor.compartido

    var body: some View {
        List {
            HStack(spacing: 10) {
                modo("Aleatorio", icono: "shuffle", activo: r.aleatorio) { r.cambiarAleatorio() }
                modo(r.repetir == .una ? "Repetir una" : "Repetir", icono: r.repetir == .una ? "repeat.1" : "repeat", activo: r.repetir != .no) { r.cambiarRepetir() }
                modo("Radio", icono: "infinity", activo: r.radioAutomatica) { r.radioAutomatica.toggle() }
            }
            .fila(arriba: 14, abajo: 4)
            Text(r.radioAutomatica ? "Al terminar la cola seguirán canciones parecidas." : "La reproducción se detiene al terminar la cola.")
                .font(.footnote).foregroundStyle(.white.opacity(0.55))
                .fila(arriba: 0, abajo: 10)

            if let c = r.actual {
                encabezado("Sonando ahora").fila(arriba: 10, abajo: 4)
                cancion(c, actual: true).fila(arriba: 4, abajo: 4)
            }

            HStack {
                encabezado(r.siguientes.isEmpty ? "No hay más canciones" : "A continuación")
                Spacer()
                if !r.siguientes.isEmpty {
                    Button("Vaciar") { r.limpiarSiguientes() }
                        .font(.subheadline.weight(.semibold)).foregroundStyle(.white.opacity(0.8))
                        .buttonStyle(.plain)
                }
            }
            .fila(arriba: 18, abajo: 4)

            ForEach(Array(r.siguientes.enumerated()), id: \.offset) { i, c in
                cancion(c, actual: false)
                    .contentShape(Rectangle())
                    .onTapGesture { r.saltarA(r.indice + 1 + i) }
                    .contextMenu { OpcionesCancion(cancion: c) }
                    .fila(arriba: 4, abajo: 4)
            }
            .onMove { r.moverSiguientes(desde: $0, hacia: $1) }
            .onDelete { idx in for i in idx.sorted(by: >) { r.quitar(en: r.indice + 1 + i) } }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.defaultMinListRowHeight, 0)
    }

    private func encabezado(_ t: String) -> some View {
        Text(t).font(.title3.weight(.bold)).foregroundStyle(.white)
    }

    private func cancion(_ c: Cancion, actual: Bool) -> some View {
        HStack(spacing: 12) {
            Portada(ruta: c.portadaUrl, tamano: 48, radio: 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(c.nombre).font(.body.weight(actual ? .semibold : .regular)).lineLimit(1)
                Text(c.artista).font(.subheadline).foregroundStyle(.white.opacity(0.6)).lineLimit(1)
            }
            Spacer(minLength: 0)
            if actual {
                Image(systemName: r.sonando ? "waveform" : "pause.fill")
                    .symbolEffect(.variableColor.iterative, isActive: r.sonando)
                    .foregroundStyle(.white.opacity(0.8))
            } else {
                Image(systemName: "line.3.horizontal").foregroundStyle(.white.opacity(0.35))
                    .accessibilityHidden(true)
            }
        }
    }

    private func modo(_ nombre: String, icono: String, activo: Bool, accion: @escaping () -> Void) -> some View {
        Button(action: accion) {
            Image(systemName: icono)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(activo ? Color.black : .white)
                .frame(maxWidth: .infinity, minHeight: 36)
                .background(Capsule().fill(activo ? Color.white.opacity(0.85) : .white.opacity(0.12)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(nombre)
        .accessibilityAddTraits(activo ? .isSelected : [])
    }
}

private extension View {
    /// Fila de la cola sin fondo, separadores ni márgenes de sistema (evita los huecos y bandas grises).
    func fila(arriba: CGFloat, abajo: CGFloat) -> some View {
        listRowInsets(EdgeInsets(top: arriba, leading: 26, bottom: abajo, trailing: 26))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}
