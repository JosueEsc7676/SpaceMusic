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
        ZStack {
            fondo
            if let c = r.actual {
                VStack(spacing: 0) {
                    cabecera(c)
                    Group {
                        switch panel {
                        case .portada: portada(c)
                        case .letra: LetraCompleta()
                        case .cola: ColaVista()
                        }
                    }
                    .frame(maxHeight: .infinity)
                    controles(c)
                }
                .padding(.horizontal, 22)
                .padding(.bottom, 8)
            } else {
                EstadoVista(icono: "music.note", titulo: "No suena nada")
            }
        }
        .hojasGlobales()
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
        VStack(spacing: 22) {
            Spacer(minLength: 8)
            Portada(ruta: c.portadaUrl, radio: 14)
                .shadow(color: .black.opacity(0.6), radius: 30, y: 16)
                .scaleEffect(r.sonando ? 1 : 0.9)
                .animation(.spring(duration: 0.4), value: r.sonando)
                .gesture(DragGesture(minimumDistance: 30).onEnded { g in
                    if g.translation.width < -60 { r.siguiente() }
                    if g.translation.width > 60 { r.anterior() }
                })
            Spacer(minLength: 8)
        }
    }

    private func controles(_ c: Cancion) -> some View {
        VStack(spacing: 14) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(c.nombre).font(.title3.weight(.bold)).lineLimit(1)
                    Button { Navegacion.compartida.ir(.artista(c.artista)) } label: {
                        Text(c.artista).font(.body).foregroundStyle(Tema.secundario).lineLimit(1)
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
                Button { biblioteca.alternarFavorito(c) } label: {
                    Image(systemName: biblioteca.esFavorita(c.id) ? "heart.fill" : "heart")
                        .font(.title2)
                        .foregroundStyle(biblioteca.esFavorita(c.id) ? Tema.rosa : Tema.texto)
                        .symbolEffect(.bounce, value: biblioteca.esFavorita(c.id))
                }
                .buttonStyle(.plain)
            }

            if panel == .portada, letras.sincronizada {
                LetraMiniatura().onTapGesture { panel = .letra }
            }

            barraTiempo

            HStack {
                Button { r.cambiarAleatorio() } label: {
                    Image(systemName: "shuffle").foregroundStyle(r.aleatorio ? Tema.celeste : Tema.texto)
                }
                Spacer()
                Button { r.anterior() } label: { Image(systemName: "backward.fill").font(.title) }
                Spacer()
                Button { r.alternar() } label: {
                    ZStack {
                        Circle().fill(Color.white).frame(width: 72, height: 72)
                        if r.cargando && !r.sonando {
                            ProgressView().tint(.black)
                        } else {
                            Image(systemName: r.sonando ? "pause.fill" : "play.fill").font(.title).foregroundStyle(.black)
                        }
                    }
                }
                Spacer()
                Button { r.siguiente() } label: { Image(systemName: "forward.fill").font(.title) }
                Spacer()
                Button { r.cambiarRepetir() } label: {
                    Image(systemName: r.repetir == .una ? "repeat.1" : "repeat")
                        .foregroundStyle(r.repetir == .no ? Tema.texto : Tema.celeste)
                }
            }
            .font(.title3)
            .buttonStyle(.plain)

            if let e = r.error {
                Text(e).font(.caption).foregroundStyle(Tema.peligro)
            }

            HStack {
                SelectorSalida().frame(width: 36, height: 36)
                Spacer()
                Button { hojas.enviarCancion = c } label: { Image(systemName: "paperplane") }
                Spacer()
                Button { panel = panel == .letra ? .portada : .letra } label: {
                    Image(systemName: "quote.bubble").foregroundStyle(panel == .letra ? Tema.celeste : Tema.texto)
                }
                Spacer()
                Button { panel = panel == .cola ? .portada : .cola } label: {
                    Image(systemName: "list.bullet").foregroundStyle(panel == .cola ? Tema.celeste : Tema.texto)
                }
            }
            .font(.title3)
            .buttonStyle(.plain)
            .padding(.horizontal, 6)
        }
    }

    private var barraTiempo: some View {
        let total = max(r.duracion, 1)
        let valor = arrastrando ?? min(r.tiempo, total)
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
                            .foregroundStyle(l.id == actual ? Tema.texto : Tema.texto.opacity(0.35))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(l.id)
                            .onTapGesture { r.buscar(l.tiempo) }
                    }
                }
                .padding(.vertical, 140)
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
