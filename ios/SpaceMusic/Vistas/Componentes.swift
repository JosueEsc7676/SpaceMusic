import SwiftUI

// MARK: - Canciones

/// Fila de una canción. Al tocarla suena la lista desde esa canción.
struct FilaCancion: View {
    let cancion: Cancion
    var numero: Int? = nil
    var mostrarPortada = true
    var detalle: String? = nil
    var accion: () -> Void

    @State private var reproductor = Reproductor.compartido
    @State private var descargas = Descargas.compartido
    @State private var ediciones = CancionesEditadas.compartidas

    var body: some View {
        let cancion = ediciones.actual(self.cancion)
        let esActual = reproductor.actual?.id == cancion.id
        HStack(spacing: 4) {
        Button(action: accion) {
            HStack(spacing: 12) {
                if let numero {
                    ZStack {
                        if esActual {
                            Image(systemName: reproductor.sonando ? "waveform" : "pause.fill")
                                .symbolEffect(.variableColor.iterative, isActive: reproductor.sonando)
                                .foregroundStyle(Tema.celeste)
                        } else {
                            Text("\(numero)").foregroundStyle(Tema.secundario)
                        }
                    }
                    .font(.subheadline.monospacedDigit())
                    .frame(width: 26)
                }
                if mostrarPortada {
                    Portada(ruta: cancion.portadaUrl, tamano: 48, radio: 6)
                }
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        if esActual && numero == nil {
                            Circle().fill(Tema.celeste).frame(width: 6, height: 6)
                        }
                        Text(cancion.nombre)
                            .font(.body.weight(.medium))
                            .foregroundStyle(esActual ? Tema.celeste : Tema.texto)
                            .lineLimit(1)
                    }
                    HStack(spacing: 4) {
                        if descargas.descargada(cancion.id) {
                            Image(systemName: "arrow.down.circle.fill").font(.caption2).foregroundStyle(Tema.celeste)
                        }
                        Text(detalle ?? cancion.artista).font(.subheadline).foregroundStyle(Tema.secundario).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        MenuCancion(cancion: cancion) {
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
                .foregroundStyle(Tema.secundario)
                .frame(width: 36, height: 44)
                .contentShape(Rectangle())
        }
        }
        .contextMenu { OpcionesCancion(cancion: cancion) }
    }
}

/// Botón "…" con las opciones de una canción.
struct MenuCancion<Etiqueta: View>: View {
    let cancion: Cancion
    @ViewBuilder var etiqueta: () -> Etiqueta

    var body: some View {
        Menu { OpcionesCancion(cancion: cancion) } label: { etiqueta() }
    }
}

/// Las mismas opciones que en la web: favorito, cola, playlist, radio, enviar, ir al álbum/artista, descargar.
struct OpcionesCancion: View {
    let cancion: Cancion
    @State private var biblioteca = Biblioteca.compartida
    @State private var descargas = Descargas.compartido
    @State private var hojas = Hojas.compartidas

    var body: some View {
        Button {
            biblioteca.alternarFavorito(cancion)
        } label: {
            biblioteca.esFavorita(cancion.id)
                ? Label("Quitar de favoritos", systemImage: "heart.slash")
                : Label("Agregar a favoritos", systemImage: "heart")
        }
        Button {
            Reproductor.compartido.agregarSiguiente(cancion)
            Avisos.compartido.mostrar("Sonará a continuación")
        } label: { Label("Reproducir a continuación", systemImage: "text.line.first.and.arrowtriangle.forward") }
        Button {
            Reproductor.compartido.agregarAlFinal([cancion])
            Avisos.compartido.mostrar("Agregada a la cola")
        } label: { Label("Agregar a la cola", systemImage: "text.append") }
        Button { hojas.agregarAPlaylist = [cancion] } label: { Label("Agregar a playlist", systemImage: "text.badge.plus") }
        Button { Reproductor.compartido.reproducirSuelta(cancion, contexto: "radio") } label: {
            Label("Iniciar radio", systemImage: "dot.radiowaves.left.and.right")
        }
        Button { hojas.enviarCancion = cancion } label: { Label("Enviar a un amigo", systemImage: "paperplane") }
        Divider()
        Button { Navegacion.compartida.ir(.artista(cancion.artista)) } label: { Label("Ir al artista", systemImage: "music.mic") }
        if !["single", "sencillo", ""].contains(cancion.album.lowercased()) {
            Button { Navegacion.compartida.ir(.album(cancion.album)) } label: { Label("Ir al álbum", systemImage: "square.stack") }
        }
        Divider()
        if descargas.descargada(cancion.id) {
            Button(role: .destructive) { descargas.borrar(cancion) } label: { Label("Quitar descarga", systemImage: "trash") }
        } else {
            Button { descargas.descargar([cancion]) } label: { Label("Descargar", systemImage: "arrow.down.circle") }
        }
        if Sesion.compartida.puedeEditar {
            Button { hojas.editarCancion = cancion } label: {
                Label("Editar canción, portada y letra", systemImage: "square.and.pencil")
            }
        }
        if let url = API.url("/#/album/\(API.segmento(cancion.album))") {
            ShareLink(item: url, subject: Text(cancion.nombre), message: Text("\(cancion.nombre) · \(cancion.artista)")) {
                Label("Compartir", systemImage: "square.and.arrow.up")
            }
        }
    }
}

/// Hojas que se abren desde cualquier menú de canción.
@MainActor
@Observable
final class Hojas {
    static let compartidas = Hojas()
    var agregarAPlaylist: [Cancion]?
    var enviarCancion: Cancion?
    var editarCancion: Cancion?
}

extension View {
    /// Conecta las hojas globales (agregar a playlist, enviar a un amigo) a una pantalla raíz.
    func hojasGlobales(activas: Bool = true) -> some View { modifier(HojasGlobales(activas: activas)) }
}

private struct HojasGlobales: ViewModifier {
    /// Solo una pantalla a la vez muestra las hojas (la app o el reproductor abierto).
    let activas: Bool
    @State private var hojas = Hojas.compartidas

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(get: { activas && hojas.agregarAPlaylist != nil }, set: { if !$0 { hojas.agregarAPlaylist = nil } })) {
                AgregarAPlaylistHoja(canciones: hojas.agregarAPlaylist ?? [])
            }
            .sheet(isPresented: Binding(get: { activas && hojas.enviarCancion != nil }, set: { if !$0 { hojas.enviarCancion = nil } })) {
                if let c = hojas.enviarCancion { EnviarCancionHoja(cancion: c) }
            }
            .sheet(isPresented: Binding(get: { activas && hojas.editarCancion != nil }, set: { if !$0 { hojas.editarCancion = nil } })) {
                if let c = hojas.editarCancion { EditarCancionHoja(cancion: c) }
            }
    }
}

// MARK: - Tarjetas

struct TarjetaAlbum: View {
    let album: Album
    var ancho: CGFloat = 150
    var body: some View {
        NavigationLink(value: Destino.album(album.nombre)) {
            VStack(alignment: .leading, spacing: 6) {
                Portada(ruta: album.portadaUrl, tamano: ancho, radio: 10, icono: "square.stack")
                Text(album.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text([album.artista, album.anio.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
            }
            .frame(width: ancho, alignment: .leading)
        }
        .buttonStyle(.plain)
    }
}

struct TarjetaArtista: View {
    let artista: Artista
    var ancho: CGFloat = 120
    var body: some View {
        NavigationLink(value: Destino.artista(artista.nombre)) {
            VStack(spacing: 8) {
                ImagenRemota(ruta: artista.portadaUrl, icono: "music.mic", redonda: true)
                    .frame(width: ancho, height: ancho)
                Text(artista.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text("Artista").font(.caption).foregroundStyle(Tema.secundario)
            }
            .frame(width: ancho)
        }
        .buttonStyle(.plain)
    }
}

struct TarjetaPlaylist: View {
    let playlist: Playlist
    var ancho: CGFloat = 150
    var body: some View {
        NavigationLink(value: Destino.playlist(playlist.id)) {
            VStack(alignment: .leading, spacing: 6) {
                PortadaPlaylist(playlist: playlist).frame(width: ancho, height: ancho)
                Text(playlist.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(playlist.propia ? Formato.canciones(playlist.cancionesCount) : "De \(playlist.autor ?? "alguien")")
                    .font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
            }
            .frame(width: ancho, alignment: .leading)
        }
        .buttonStyle(.plain)
    }
}

struct PortadaPlaylist: View {
    let playlist: Playlist
    var radio: CGFloat = 10
    var body: some View {
        if let p = playlist.portadaUrl, !p.isEmpty {
            Portada(ruta: p, radio: radio, icono: "music.note.list")
        } else {
            Mosaico(rutas: playlist.portadas, radio: radio)
        }
    }
}

struct TarjetaMix: View {
    let mix: Mix
    var ancho: CGFloat = 150
    var body: some View {
        NavigationLink(value: Destino.mix(id: mix.id, titulo: mix.titulo)) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack(alignment: .bottomLeading) {
                    Mosaico(rutas: mix.portadas, radio: 10)
                    LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .center, endPoint: .bottom)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    Text(mix.titulo).font(.headline).foregroundStyle(.white).lineLimit(2).padding(10)
                }
                .frame(width: ancho, height: ancho)
                Text(mix.subtitulo ?? mix.artistas.joined(separator: ", "))
                    .font(.caption).foregroundStyle(Tema.secundario).lineLimit(2)
            }
            .frame(width: ancho, alignment: .leading)
        }
        .buttonStyle(.plain)
    }
}

/// Tarjeta de género como en la web (`.genre`): color propio que se funde con el negro, nombre arriba
/// y la portada pequeña girada en la esquina. El texto nunca queda debajo de la imagen ni fuera de la tarjeta.
struct TarjetaGenero: View {
    let genero: Genero
    @ScaledMetric(relativeTo: .headline) private var alto: CGFloat = 92

    var body: some View {
        let color = Tema.colorGenero(genero.nombre)
        NavigationLink(value: Destino.genero(genero.nombre)) {
            VStack(alignment: .leading, spacing: 4) {
                Text(genero.nombre)
                    .font(.system(.headline, design: .rounded).weight(.heavy))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
                Text(Formato.canciones(genero.totalCanciones))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.8))
                    .lineLimit(1)
            }
            .padding(.leading, 12).padding(.top, 12).padding(.trailing, 58)
            .frame(maxWidth: .infinity, minHeight: alto, alignment: .topLeading)
            .background {
                LinearGradient(colors: [Color(red: color.0, green: color.1, blue: color.2), Color(red: color.0 * 0.45, green: color.1 * 0.45, blue: color.2 * 0.45)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
            }
            .overlay(alignment: .bottomTrailing) {
                if let ruta = genero.portadaUrl, !ruta.isEmpty {
                    Portada(ruta: ruta, tamano: 70, radio: 6)
                        .shadow(color: .black.opacity(0.4), radius: 9, y: 6)
                        .rotationEffect(.degrees(24))
                        .offset(x: 14, y: 8)
                        .allowsHitTesting(false)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(genero.nombre), \(Formato.canciones(genero.totalCanciones))")
    }
}

/// Tarjeta pequeña de canción para carruseles (toca para reproducir).
struct TarjetaCancion: View {
    let cancion: Cancion
    let lista: [Cancion]
    var contexto = "inicio"
    var ancho: CGFloat = 150

    var body: some View {
        Button {
            Reproductor.compartido.reproducirSuelta(cancion, contexto: contexto)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                Portada(ruta: cancion.portadaUrl, tamano: ancho, radio: 10)
                Text(cancion.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(cancion.artista).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
            }
            .frame(width: ancho, alignment: .leading)
        }
        .buttonStyle(.plain)
        .contextMenu { OpcionesCancion(cancion: cancion) }
    }
}

/// Sección con título y fila horizontal.
struct Carrusel<Contenido: View>: View {
    let titulo: String
    var destino: Destino? = nil
    @ViewBuilder var contenido: () -> Contenido

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(titulo).titulo()
                Spacer()
                if let destino {
                    NavigationLink(value: destino) {
                        Text("Ver todo").font(.subheadline.weight(.semibold)).foregroundStyle(Tema.secundario)
                    }
                }
            }
            .padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14) { contenido() }
                    .padding(.horizontal)
            }
        }
    }
}

struct FilaPersona: View {
    let persona: PerfilMini
    var detalle: String? = nil
    var body: some View {
        NavigationLink(value: Destino.perfil(persona.username)) {
            HStack(spacing: 12) {
                Avatar(ruta: persona.fotoPerfil, nombre: persona.nombre, tamano: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Text(persona.nombre).font(.body.weight(.medium)).foregroundStyle(Tema.texto)
                    Text(detalle ?? "@\(persona.username)" + (persona.amigos ? " · Amigos" : ""))
                        .font(.subheadline).foregroundStyle(Tema.secundario)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(Tema.secundario)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Encabezado de álbum, playlist, mix o género: portada grande, títulos y botones.
struct Encabezado<Arte: View, Extra: View>: View {
    let titulo: String
    var subtitulo: String? = nil
    var detalle: String? = nil
    let canciones: [Cancion]
    let contexto: String
    @ViewBuilder var arte: () -> Arte
    @ViewBuilder var extra: () -> Extra

    var body: some View {
        VStack(spacing: 14) {
            arte()
                .frame(width: 230, height: 230)
                .shadow(color: .black.opacity(0.6), radius: 24, y: 12)
                .padding(.top, 8)
            VStack(spacing: 4) {
                Text(titulo).font(.system(.title2, design: .rounded).weight(.bold)).multilineTextAlignment(.center)
                if let subtitulo { Text(subtitulo).font(.headline).foregroundStyle(Tema.celeste) }
                if let detalle { Text(detalle).font(.subheadline).foregroundStyle(Tema.secundario) }
            }
            .padding(.horizontal)
            HStack(spacing: 14) {
                extra()
                Spacer()
                Button {
                    Reproductor.compartido.reproducir(canciones, contexto: contexto, mezclar: true)
                } label: {
                    Image(systemName: "shuffle").font(.title3.weight(.semibold)).frame(width: 48, height: 48)
                        .background(Circle().fill(Tema.tarjeta))
                }
                .disabled(canciones.isEmpty)
                Button {
                    Reproductor.compartido.reproducir(canciones, contexto: contexto)
                } label: {
                    Image(systemName: "play.fill").font(.title2).foregroundStyle(.black).frame(width: 58, height: 58)
                        .background(Circle().fill(Color.white))
                }
                .disabled(canciones.isEmpty)
            }
            .buttonStyle(.plain)
            .padding(.horizontal)
            Menu {
                Button("Agregar todas a favoritos") { Task { await Biblioteca.compartida.agregarFavoritos(canciones) } }
                Button("Agregar todas a la cola") { Reproductor.compartido.agregarAlFinal(canciones) }
                Button("Agregar a una playlist") { Hojas.compartidas.agregarAPlaylist = canciones }
            } label: { Label("Opciones de la lista", systemImage: "ellipsis") }
            .font(.subheadline)
            .disabled(canciones.isEmpty)
        }
    }
}

/// Lista de canciones dentro de una pantalla con scroll.
struct ListaCanciones: View {
    let canciones: [Cancion]
    let contexto: String
    var numerada = false
    var mostrarPortada = true
    /// Si es false, al tocar una canción suena sola y sigue la radio.
    var enOrden = true
    @State private var filtro = ""

    private var visibles: [(offset: Int, element: Cancion)] {
        let consulta = filtro.trimmingCharacters(in: .whitespacesAndNewlines)
        return Array(canciones.enumerated()).filter { fila in
            consulta.isEmpty || (fila.element.nombre + " " + fila.element.artista + " " + fila.element.album)
                .range(of: consulta, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
    }

    var body: some View {
        LazyVStack(spacing: 10) {
            if canciones.count > 15 {
                TextField("Filtrar canciones de esta lista", text: $filtro)
                    .textFieldStyle(.roundedBorder).autocorrectionDisabled()
            }
            if visibles.isEmpty, !filtro.isEmpty { Text("Sin coincidencias en esta lista").foregroundStyle(Tema.secundario) }
            ForEach(visibles, id: \.element.id) { i, c in
                FilaCancion(cancion: c, numero: numerada ? (c.numeroPista ?? i + 1) : nil, mostrarPortada: mostrarPortada) {
                    if enOrden {
                        Reproductor.compartido.reproducir(canciones, desde: i, contexto: contexto)
                    } else {
                        Reproductor.compartido.reproducirSuelta(c, contexto: contexto)
                    }
                }
            }
        }
        .padding(.horizontal)
    }
}

/// Botón para descargar una lista entera.
struct BotonDescargarLista: View {
    let canciones: [Cancion]
    @State private var descargas = Descargas.compartido

    var body: some View {
        let todas = !canciones.isEmpty && canciones.allSatisfy { descargas.descargada($0.id) }
        let bajando = canciones.contains { descargas.enCurso[$0.id] != nil }
        Button {
            if !todas {
                descargas.descargar(canciones)
                Avisos.compartido.mostrar("Descargando \(Formato.canciones(canciones.count))")
            }
        } label: {
            Group {
                if bajando { ProgressView() } else {
                    Image(systemName: todas ? "arrow.down.circle.fill" : "arrow.down.circle")
                        .foregroundStyle(todas ? Tema.celeste : Tema.texto)
                }
            }
            .font(.title2)
            .frame(width: 40, height: 40)
        }
        .buttonStyle(.plain)
        .disabled(canciones.isEmpty)
    }
}
