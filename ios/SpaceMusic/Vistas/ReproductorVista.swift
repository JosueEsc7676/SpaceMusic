import AVKit
import SwiftUI

/// Reproductor a pantalla completa: portada, controles, letra y cola.
struct ReproductorVista: View {
    enum Panel { case portada, letra, cola }

    @State private var r = Reproductor.compartido
    @State private var biblioteca = Biblioteca.compartida
    @State private var letras = Letras.compartido
    @State private var panel: Panel = .portada
    @State private var arrastrando: Double?
    @State private var hojas = Hojas.compartidas

    var body: some View {
        GeometryReader { espacio in
          ZStack {
            if let c = r.actual {
                if espacio.size.height < 430 {
                    VStack(spacing: 8) {
                        cabecera(c)
                        HStack(spacing: 24) {
                            contenidoPanel(c)
                                .frame(maxWidth: .infinity, maxHeight: .infinity).clipped()
                            ScrollView { controles(c).padding(.bottom, 8) }
                                .frame(width: max(220, espacio.size.width * 0.5))
                        }
                    }
                    .padding(.horizontal, 22)
                    .frame(width: espacio.size.width, height: espacio.size.height)
                } else {
                  VStack(spacing: 0) {
                    cabecera(c)
                    if panel != .portada {
                        // Con letra o cola abiertas, la canción pasa arriba (como en la web y Apple Music)
                        // y el panel ocupa todo el centro sin rozar los controles.
                        cabeceraCompacta(c).padding(.top, 8)
                    }
                    Group {
                        switch panel {
                        case .portada: portada(c)
                        case .letra: LetraCompleta().mask(desvanecido)
                        case .cola: ColaVista().mask(desvanecido)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                    controles(c)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 12)
                }
                .padding(.horizontal, 22)
                .padding(.bottom, 8)
                .frame(width: espacio.size.width, height: espacio.size.height)
                }
            } else {
                EstadoVista(icono: "music.note", titulo: "No suena nada")
            }
        }
          .frame(width: espacio.size.width, height: espacio.size.height)
          .background { fondo }
          .clipped()
        }
        .onChange(of: r.actual?.id) { _, _ in arrastrando = nil }
        .hojasGlobales()
    }

    /// Bordes suaves arriba y abajo: las líneas y filas se desvanecen en vez de quedar cortadas en seco.
    private var desvanecido: some View {
        LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.05),
                               .init(color: .black, location: 0.9), .init(color: .clear, location: 1)],
                       startPoint: .top, endPoint: .bottom)
    }

    private func cabeceraCompacta(_ c: Cancion) -> some View {
        HStack(spacing: 12) {
            Portada(ruta: c.portadaUrl, tamano: 52, radio: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(c.nombre).font(.headline).lineLimit(1)
                Text(c.artista).font(.subheadline).foregroundStyle(Tema.secundario).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            botonFavorito(c)
        }
    }

    private func botonFavorito(_ c: Cancion) -> some View {
        Button { biblioteca.alternarFavorito(c) } label: {
            Image(systemName: biblioteca.esFavorita(c.id) ? "heart.fill" : "heart")
                .font(.title2)
                .foregroundStyle(biblioteca.esFavorita(c.id) ? Tema.rosa : Tema.texto)
                .symbolEffect(.bounce, value: biblioteca.esFavorita(c.id))
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(biblioteca.esFavorita(c.id) ? "Quitar de Me gusta" : "Me gusta")
    }

    @ViewBuilder private func contenidoPanel(_ c: Cancion) -> some View {
        switch panel {
        case .portada: portada(c)
        case .letra: LetraCompleta()
        case .cola: ColaVista()
        }
    }

    private var fondo: some View {
        ZStack {
            Color.black
            ImagenRemota(ruta: r.actual?.portadaUrl)
                .blur(radius: 60)
                .opacity(0.55)
                .scaleEffect(1.4)
            LinearGradient(colors: [.black.opacity(0.2), .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
    }

    private func cabecera(_ c: Cancion) -> some View {
        HStack {
            Button { Navegacion.compartida.reproductorAbierto = false } label: {
                Image(systemName: "chevron.down").font(.title3.weight(.semibold)).frame(width: 40, height: 40)
            }
            Spacer()
            VStack(spacing: 2) {
                Text(textoContexto).font(.caption).foregroundStyle(Tema.secundario)
                Text(c.album).font(.caption.weight(.semibold)).lineLimit(1)
            }
            Spacer()
            MenuCancion(cancion: c) {
                Image(systemName: "ellipsis").font(.title3.weight(.semibold)).frame(width: 40, height: 40)
            }
        }
        .buttonStyle(.plain)
        .padding(.top, 14)
    }

    private var textoContexto: String {
        switch r.contexto {
        case "album": return "Desde el álbum"
        case "playlist": return "Desde la playlist"
        case "artista": return "Desde el artista"
        case "radio": return "Radio"
        case "mix": return "Mix"
        case "favoritos": return "Tus favoritos"
        case "buscar": return "Desde la búsqueda"
        default: return "Reproduciendo"
        }
    }

    private func portada(_ c: Cancion) -> some View {
        GeometryReader { espacio in
            let lado = max(0, min(espacio.size.width, espacio.size.height - 16))
            Portada(ruta: c.portadaUrl, tamano: lado, radio: 14)
                .shadow(color: .black.opacity(0.6), radius: 30, y: 16)
                .scaleEffect(r.sonando ? 1 : 0.9)
                .animation(.spring(duration: 0.4), value: r.sonando)
                .gesture(DragGesture(minimumDistance: 30).onEnded { g in
                    if g.translation.width < -60 { r.siguiente() }
                    if g.translation.width > 60 { r.anterior() }
                })
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func controles(_ c: Cancion) -> some View {
        VStack(spacing: 14) {
            if panel == .portada {
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(c.nombre).font(.title3.weight(.bold)).lineLimit(1)
                        Button { Navegacion.compartida.ir(.artista(c.artista)) } label: {
                            Text(c.artista).font(.body).foregroundStyle(Tema.secundario).lineLimit(1)
                        }
                        .buttonStyle(.plain)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    botonFavorito(c)
                }
            }

            if panel == .portada, letras.sincronizada {
                LetraMiniatura().onTapGesture { panel = .letra }
            }

            barraTiempo

            // Cinco columnas iguales: ningún botón puede salirse de la pantalla, en ningún tamaño de iPhone.
            HStack(spacing: 0) {
                Button { r.cambiarAleatorio() } label: {
                    Image(systemName: "shuffle").foregroundStyle(r.aleatorio ? Tema.celeste : Tema.texto).celda()
                }
                .accessibilityLabel(r.aleatorio ? "Desactivar aleatorio" : "Activar aleatorio")
                Button { r.anterior() } label: { Image(systemName: "backward.fill").font(.title).celda() }
                    .accessibilityLabel("Anterior")
                Button { r.alternar() } label: {
                    ZStack {
                        Circle().fill(Color.white).frame(width: 68, height: 68)
                        if r.esperaVisible && !r.sonando {
                            ProgressView().tint(.black)
                        } else {
                            Image(systemName: r.sonando ? "pause.fill" : "play.fill").font(.title).foregroundStyle(.black)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .accessibilityLabel(r.sonando ? "Pausar" : "Reproducir")
                Button { r.siguiente() } label: { Image(systemName: "forward.fill").font(.title).celda() }
                    .accessibilityLabel("Siguiente")
                Button { r.cambiarRepetir() } label: {
                    Image(systemName: r.repetir == .una ? "repeat.1" : "repeat")
                        .foregroundStyle(r.repetir == .no ? Tema.texto : Tema.celeste).celda()
                }
                .accessibilityLabel("Repetir")
            }
            .font(.title3)
            .buttonStyle(.plain)

            if let e = r.error {
                Text(e).font(.caption).foregroundStyle(Tema.peligro)
            }

            HStack(spacing: 0) {
                SelectorSalida().frame(width: 36, height: 36).frame(maxWidth: .infinity)
                Menu {
                    ForEach([5, 10, 15, 30, 45, 60], id: \.self) { minutos in
                        Button("\(minutos) minutos") { r.programarSueno(minutos: minutos) }
                    }
                    Button("Al terminar esta canción") { r.programarSueno(minutos: nil); r.dormirAlTerminar = true }
                    Button("Desactivar", role: .cancel) { r.programarSueno(minutos: nil) }
                } label: {
                    Image(systemName: "moon.zzz").foregroundStyle(r.finTemporizador != nil || r.dormirAlTerminar ? Tema.celeste : Tema.texto).celda()
                }
                .accessibilityLabel("Temporizador para dormir")
                Button { hojas.enviarCancion = c } label: { Image(systemName: "paperplane").celda() }
                    .accessibilityLabel("Enviar a un amigo")
                Button { withAnimation(.spring(duration: 0.35)) { panel = panel == .letra ? .portada : .letra } } label: {
                    Image(systemName: "quote.bubble").foregroundStyle(panel == .letra ? Tema.celeste : Tema.texto).celda()
                }
                .accessibilityLabel("Letra")
                Button { withAnimation(.spring(duration: 0.35)) { panel = panel == .cola ? .portada : .cola } } label: {
                    Image(systemName: "list.bullet").foregroundStyle(panel == .cola ? Tema.celeste : Tema.texto).celda()
                }
                .accessibilityLabel("A continuación")
            }
            .font(.title3)
            .buttonStyle(.plain)
        }
    }

    private var barraTiempo: some View {
        let total = max(r.duracion, 1)
        let valor = min(max(arrastrando ?? r.tiempo, 0), total)
        return VStack(spacing: 4) {
            Slider(value: Binding(get: { valor }, set: { arrastrando = $0 }), in: 0...total) { editando in
                if !editando, let a = arrastrando {
                    r.buscar(a)
                    arrastrando = nil
                }
            }
            .tint(.white)
            HStack {
                Text(Formato.duracion(Int(valor))).monospacedDigit()
                Spacer()
                Text("-" + Formato.duracion(Int(max(0, total - valor)))).monospacedDigit()
            }
            .font(.caption)
            .foregroundStyle(Tema.secundario)
        }
    }
}

/// Botón de AirPlay y Bluetooth.
struct SelectorSalida: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = UIColor(Tema.celeste)
        v.prioritizesVideoDevices = false
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

/// Línea actual de la letra debajo del artista (como en la web 2.7).
struct LetraMiniatura: View {
    @State private var r = Reproductor.compartido
    @State private var letras = Letras.compartido

    var body: some View {
        let i = letras.indice(en: r.tiempo)
        Text(i.map { letras.lineas[$0].texto }.flatMap { $0.isEmpty ? nil : $0 } ?? "♪")
            .font(.headline)
            .foregroundStyle(Tema.texto.opacity(0.9))
            .multilineTextAlignment(.center)
            .lineLimit(2)
            .frame(maxWidth: .infinity, minHeight: 44)
            .animation(.easeInOut(duration: 0.25), value: i)
            .contentTransition(.opacity)
    }
}

/// Letra completa; si es sincronizada sigue la canción y se puede tocar una línea para saltar ahí.
struct LetraCompleta: View {
    @State private var r = Reproductor.compartido
    @State private var letras = Letras.compartido

    var body: some View {
        Group {
            if letras.cargando {
                Cargando()
            } else if letras.sincronizada {
                sincronizada
            } else if !letras.textoPlano.isEmpty {
                ScrollView {
                    Text(letras.textoPlano)
                        .font(.title3.weight(.semibold))
                        .lineSpacing(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 20)
                }
            } else if let c = r.actual {
                VStack(spacing: 14) {
                    Image(systemName: "quote.bubble").font(.largeTitle).foregroundStyle(Tema.secundario)
                    Text(letras.mensaje ?? "Esta canción no tiene letra todavía").foregroundStyle(Tema.secundario)
                    Button("Buscar en internet") { Task { await letras.buscarEnInternet(c, forzar: letras.mensaje != nil) } }
                        .buttonStyle(BotonSecundario())
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private var sincronizada: some View {
        let actual = letras.indice(en: r.tiempo)
        return ScrollViewReader { lector in
            ScrollView(showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 18) {
                    ForEach(letras.lineas) { l in
                        Text(l.texto.isEmpty ? "♪" : l.texto)
                            .font(.title2.weight(.bold))
                            .fixedSize(horizontal: false, vertical: true)
                            .foregroundStyle(l.id == actual ? Tema.texto : Tema.texto.opacity(0.35))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(l.id)
                            .onTapGesture { r.buscar(l.tiempo) }
                    }
                }
                .padding(.vertical, 60)
                .padding(.horizontal, 4)
            }
            .onChange(of: actual) { _, nuevo in
                guard let nuevo else { return }
                withAnimation(.easeInOut(duration: 0.4)) { lector.scrollTo(nuevo, anchor: .center) }
            }
            .onAppear { if let actual { lector.scrollTo(actual, anchor: .center) } }
        }
    }
}

/// Lo que sonará después. Se puede reordenar y quitar.
struct ColaVista: View {
    @State private var r = Reproductor.compartido

    var body: some View {
        List {
            Section {
                Toggle(isOn: $r.radioAutomatica) {
                    Label("Seguir con canciones parecidas", systemImage: "dot.radiowaves.left.and.right")
                }
                .tint(Tema.celeste)
            }
            .listRowBackground(Color.white.opacity(0.06))
            Section {
                ForEach(Array(r.siguientes.enumerated()), id: \.element.id) { i, c in
                    HStack(spacing: 12) {
                        Portada(ruta: c.portadaUrl, tamano: 40, radio: 5)
                        VStack(alignment: .leading) {
                            Text(c.nombre).lineLimit(1)
                            Text(c.artista).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { r.saltarA(r.indice + 1 + i) }
                }
                .onMove { r.moverSiguientes(desde: $0, hacia: $1) }
                .onDelete { idx in
                    for i in idx.sorted(by: >) { r.quitar(en: r.indice + 1 + i) }
                }
            } header: {
                HStack {
                    Text("A continuación")
                    Spacer()
                    if !r.siguientes.isEmpty {
                        Button("Vaciar") { r.limpiarSiguientes() }.font(.caption)
                    }
                }
            }
            .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.editMode, .constant(.active))
    }
}

private extension View {
    /// Celda de botón: ocupa su columna completa y mantiene un área táctil de 44 pt.
    func celda() -> some View { frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle()) }
}
