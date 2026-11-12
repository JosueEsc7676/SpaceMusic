import SwiftUI

/// Pestañas de la app con el mini reproductor encima.
struct Principal: View {
    @State private var nav = Navegacion.compartida
    @State private var eventos = Eventos.compartido
    @State private var reproductor = Reproductor.compartido
    @State private var avisos = Avisos.compartido
    @State private var conexion = Conexion.compartida
    @State private var altoBarra: CGFloat = 0

    var body: some View {
        TabView(selection: $nav.pestana) {
            pila(.inicio) { InicioVista() }
                .tabItem { Label("Inicio", systemImage: "house.fill") }
                .tag(Pestana.inicio)
            pila(.buscar) { BuscarVista() }
                .tabItem { Label("Buscar", systemImage: "magnifyingglass") }
                .tag(Pestana.buscar)
            pila(.biblioteca) { BibliotecaVista() }
                .tabItem { Label("Biblioteca", systemImage: "square.stack.fill") }
                .tag(Pestana.biblioteca)
            pila(.mensajes) { MensajesVista() }
                .tabItem { Label("Mensajes", systemImage: "bubble.left.and.bubble.right.fill") }
                .tag(Pestana.mensajes)
                .badge(eventos.mensajesSinLeer)
            pila(.perfil) { MiPerfilVista() }
                .tabItem { Label("Tú", systemImage: "person.crop.circle.fill") }
                .tag(Pestana.perfil)
                .badge(eventos.notificacionesSinLeer)
        }
        // Pantalla completa como Apple Music y Spotify (sin la tarjeta de hoja ni bandas arriba y abajo).
        // Se cierra deslizando hacia abajo; el fondo transparente deja ver la app mientras se arrastra.
        .fullScreenCover(isPresented: $nav.reproductorAbierto) {
            ReproductorVista()
                .presentationBackground(.clear)
        }
        .sheet(isPresented: $nav.verActualizacion) { ActualizacionVista() }
        .hojasGlobales(activas: !nav.reproductorAbierto)
        .task {
            Descargas.compartido.cargar()
            Reproductor.compartido.restaurar()
            if !conexion.disponible { nav.pestana = .biblioteca; nav.ir(.descargas) }
            await Biblioteca.compartida.renovarSesionSiHaceFalta()
            guard !Task.isCancelled, Sesion.compartida.conectado else { return }
            Eventos.compartido.conectar()
            Eventos.pedirPermiso()
            await Biblioteca.compartida.cargar()
            if await GustosHoja.debePreguntar() {
                Hojas.compartidas.gustosInicial = true
                Hojas.compartidas.gustos = true
            }
        }
    }

    private func pila<V: View>(_ p: Pestana, @ViewBuilder _ raiz: () -> V) -> some View {
        NavigationStack(path: nav.ruta(p)) {
            raiz()
                .destinos()
        }
        // Las listas de cualquier pantalla (también las abiertas dentro de la pestaña) terminan por encima
        // del mini reproductor: el margen se mide con la barra real, así el último elemento siempre se puede tocar.
        .contentMargins(.bottom, altoBarra, for: .scrollContent)
        .overlay(alignment: .bottom) {
            barraInferior
                .background { GeometryReader { g in Color.clear.preference(key: AltoBarraInferior.self, value: g.size.height) } }
                .ignoresSafeArea(.keyboard, edges: .bottom)
        }
        .onPreferenceChange(AltoBarraInferior.self) { altoBarra = $0 }
    }

    private var barraInferior: some View {
            VStack(spacing: 8) {
                if !conexion.disponible || conexion.mostrandoCopia {
                    // Botón (no NavigationLink): la barra queda fuera de la pila de navegación.
                    Button { Navegacion.compartida.ir(.descargas) } label: {
                        Label("Sin conexión · Abrir descargas", systemImage: "wifi.slash").font(.caption)
                    }
                }
                if let t = avisos.texto {
                    HStack(spacing: 12) {
                        Text(t).font(.subheadline.weight(.medium))
                        if let a = avisos.accion {
                            Button(a.titulo) { avisos.ejecutarAccion() }
                                .font(.subheadline.weight(.bold)).foregroundStyle(Tema.celeste)
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Color(white: 0.18)))
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if reproductor.actual != nil, eventos.chatAbierto == nil {
                    MiniReproductor()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .padding(.vertical, avisos.texto != nil || (reproductor.actual != nil && eventos.chatAbierto == nil) ? 4 : 0)
            // Fondo hasta la barra de pestañas: las filas ya no asoman entre el mini reproductor y las pestañas.
            .background(alignment: .bottom) {
                if reproductor.actual != nil, eventos.chatAbierto == nil {
                    LinearGradient(stops: [.init(color: .black.opacity(0), location: 0), .init(color: .black.opacity(0.92), location: 0.35),
                                           .init(color: .black, location: 1)], startPoint: .top, endPoint: .bottom)
                        .allowsHitTesting(false)
                }
            }
            .animation(.spring(duration: 0.3), value: avisos.texto)
            .animation(.spring(duration: 0.3), value: reproductor.actual?.id)
    }
}

/// Alto de la barra inferior (aviso + mini reproductor) para reservar ese espacio en las listas.
private struct AltoBarraInferior: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Barra pequeña con la canción que suena.
struct MiniReproductor: View {
    @State private var r = Reproductor.compartido
    @State private var biblioteca = Biblioteca.compartida

    var body: some View {
        if let c = r.actual {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Portada(ruta: c.portadaUrl, tamano: 42, radio: 6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(c.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text(c.artista).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Button { biblioteca.alternarFavorito(c) } label: {
                        Image(systemName: biblioteca.esFavorita(c.id) ? "heart.fill" : "heart")
                            .foregroundStyle(biblioteca.esFavorita(c.id) ? Tema.rosa : Tema.texto)
                            .frame(width: 36, height: 40)
                    }
                    Button { r.alternar() } label: {
                        Group {
                            if r.esperaVisible && !r.sonando { ProgressView() } else {
                                Image(systemName: r.sonando ? "pause.fill" : "play.fill")
                            }
                        }
                        .font(.title3)
                        .frame(width: 40, height: 40)
                    }
                    Button { r.siguiente() } label: {
                        Image(systemName: "forward.fill").font(.body).frame(width: 36, height: 40)
                    }
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                ProgressView(value: r.duracion > 0 ? min(r.tiempo / r.duracion, 1) : 0)
                    .progressViewStyle(.linear)
                    .tint(.white)
                    .scaleEffect(x: 1, y: 0.6)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 4)
            }
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(white: 0.13))
                    .shadow(color: .black.opacity(0.5), radius: 12, y: 4)
            )
            .padding(.horizontal, 8)
            .contentShape(Rectangle())
            .onTapGesture { Navegacion.compartida.reproductorAbierto = true }
            .gesture(DragGesture(minimumDistance: 20).onEnded { g in
                if g.translation.width < -60 { r.siguiente() }
                else if g.translation.width > 60 { r.anterior() }
                else if g.translation.height < -30 { Navegacion.compartida.reproductorAbierto = true }
            })
        }
    }
}
