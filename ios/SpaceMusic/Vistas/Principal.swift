import SwiftUI

/// Pestañas de la app con el mini reproductor encima.
struct Principal: View {
    @State private var nav = Navegacion.compartida
    @State private var eventos = Eventos.compartido
    @State private var reproductor = Reproductor.compartido
    @State private var avisos = Avisos.compartido
    @State private var conexion = Conexion.compartida

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
        .sheet(isPresented: $nav.reproductorAbierto) {
            ReproductorVista()
                .presentationDragIndicator(.visible)
        }
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
        }
    }

    private func pila<V: View>(_ p: Pestana, @ViewBuilder _ raiz: () -> V) -> some View {
        NavigationStack(path: nav.ruta(p)) {
            raiz()
                .destinos()
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 8) {
                if !conexion.disponible || conexion.mostrandoCopia {
                    NavigationLink(value: Destino.descargas) {
                        Label("Sin conexión · Abrir descargas", systemImage: "wifi.slash").font(.caption)
                    }
                }
                if let t = avisos.texto {
                    Text(t)
                        .font(.subheadline.weight(.medium))
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
            .animation(.spring(duration: 0.3), value: avisos.texto)
            .animation(.spring(duration: 0.3), value: reproductor.actual?.id)
        }
    }
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
                            if r.cargando && !r.sonando { ProgressView() } else {
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
