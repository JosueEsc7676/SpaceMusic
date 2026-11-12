import PhotosUI
import SwiftUI

/// Carga un dato del servidor y muestra carga, error o contenido.
struct Cargador<T, Contenido: View>: View {
    let cargar: () async throws -> T
    @ViewBuilder var contenido: (T, @escaping () async -> Void) -> Contenido

    @State private var valor: T?
    @State private var error: String?

    var body: some View {
        Group {
            if let valor {
                contenido(valor, { await traer() })
            } else if let error {
                ScrollView {
                    EstadoVista(icono: "exclamationmark.triangle", titulo: "No se pudo cargar", texto: error) {
                        Task { await traer() }
                    }
                }
            } else {
                Cargando()
            }
        }
        .task { if valor == nil { await traer() } }
        .onReceive(NotificationCenter.default.publisher(for: .cancionActualizada)) { _ in Task { await traer() } }
    }

    private func traer() async {
        do {
            valor = try await cargar()
            error = nil
        } catch is CancellationError {
        } catch {
            if valor == nil { self.error = error.localizedDescription }
        }
    }
}

// MARK: - Álbum

struct AlbumVista: View {
    let nombre: String
    @State private var biblioteca = Biblioteca.compartida

    var body: some View {
        Cargador(cargar: { () async throws -> Album in try await API.get("/api/albumes/detalle?nombre=\(API.codificar(nombre))") }) { a, recargar in
            ScrollView {
                VStack(spacing: 22) {
                    Encabezado(titulo: a.nombre, subtitulo: a.artista,
                               detalle: [a.anio.map(String.init), Formato.canciones(a.canciones.count), Formato.duracionLarga(a.duracionTotal)]
                                   .compactMap { $0 }.joined(separator: " · "),
                               canciones: a.canciones, contexto: "album") {
                        Portada(ruta: a.portadaUrl, radio: 12, icono: "square.stack")
                    } extra: {
                        Button { biblioteca.alternarAlbum(a.nombre) } label: {
                            Image(systemName: biblioteca.albumGuardado(a.nombre) ? "checkmark.circle.fill" : "plus.circle")
                                .font(.title2)
                                .foregroundStyle(biblioteca.albumGuardado(a.nombre) ? Tema.celeste : Tema.texto)
                        }
                        BotonDescargarLista(canciones: a.canciones)
                        Menu {
                            Button { Hojas.compartidas.agregarAPlaylist = a.canciones } label: {
                                Label("Agregar todo a una playlist", systemImage: "text.badge.plus")
                            }
                            Button { Reproductor.compartido.agregarAlFinal(a.canciones) } label: {
                                Label("Agregar a la cola", systemImage: "text.append")
                            }
                            Button { Navegacion.compartida.ir(.artista(a.artista)) } label: {
                                Label("Ir al artista", systemImage: "music.mic")
                            }
                            Button { Task { await crearPlaylist(con: a) } } label: {
                                Label("Crear playlist con este álbum", systemImage: "plus.rectangle.on.rectangle")
                            }
                            if let url = API.url("/#/album/\(API.segmento(a.nombre))") {
                                ShareLink(item: url, subject: Text(a.nombre), message: Text("\(a.nombre) · \(a.artista)")) {
                                    Label("Compartir enlace", systemImage: "square.and.arrow.up")
                                }
                            }
                        } label: { Image(systemName: "ellipsis").font(.title2).frame(width: 40, height: 40) }
                    }
                    ListaCanciones(canciones: a.canciones, contexto: "album", numerada: true, mostrarPortada: false)
                }
                .padding(.bottom, 20)
            }
            .refreshable { await recargar() }
        }
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Como la web: una playlist nueva con el nombre del álbum y todas sus canciones, y se abre.
    private func crearPlaylist(con a: Album) async {
        do {
            let p = try await biblioteca.crearPlaylist(nombre: a.nombre, descripcion: "De \(a.artista)", publica: false)
            try await API.hacer("POST", "/api/playlists/\(p.id)/canciones", ["ids": a.canciones.map(\.id)])
            await biblioteca.recargarPlaylists()
            Avisos.compartido.mostrar("Playlist creada")
            Navegacion.compartida.ir(.playlist(p.id))
        } catch { Avisos.compartido.error(error) }
    }
}

// MARK: - Artista

struct ArtistaVista: View {
    let nombre: String
    @State private var todas = false

    var body: some View {
        Cargador(cargar: { () async throws -> ArtistaDetalle in try await API.get("/api/artistas/\(API.segmento(nombre))") }) { a, recargar in
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    ZStack(alignment: .bottomLeading) {
                        ImagenRemota(ruta: a.portadaUrl, icono: "music.mic")
                            .frame(height: 300)
                            .frame(maxWidth: .infinity)
                        LinearGradient(colors: [.clear, .black], startPoint: .center, endPoint: .bottom)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(a.nombre).font(.system(size: 36, weight: .heavy, design: .rounded)).lineLimit(2)
                            Text("\(Formato.canciones(a.totalCanciones)) · \(a.totalReproducciones) reproducciones")
                                .font(.subheadline).foregroundStyle(Tema.secundario)
                        }
                        .padding()
                    }
                    .frame(height: 300)
                    .clipped()

                    HStack(spacing: 14) {
                        BotonDescargarLista(canciones: a.canciones)
                        if let url = API.url("/#/artista/\(API.segmento(a.nombre))") {
                            ShareLink(item: url, subject: Text(a.nombre)) { Image(systemName: "square.and.arrow.up").font(.title2) }
                                .accessibilityLabel("Compartir enlace")
                        }
                        NavigationLink(value: Destino.mix(id: "artista:" + a.nombre, titulo: "Mix de " + a.nombre)) {
                            Image(systemName: "sparkles").font(.title2)
                        }
                        Spacer()
                        Button {
                            Task {
                                if let cs: [Cancion] = try? await API.get("/api/shuffle/artista/\(API.segmento(a.nombre))?cantidad=50") {
                                    Reproductor.compartido.reproducir(cs, contexto: "artista")
                                }
                            }
                        } label: {
                            Label("Radio", systemImage: "dot.radiowaves.left.and.right")
                        }
                        .buttonStyle(BotonSecundario())
                        Button { Reproductor.compartido.reproducir(a.canciones, contexto: "artista", mezclar: true) } label: {
                            Image(systemName: "shuffle").font(.title3.weight(.semibold)).frame(width: 48, height: 48)
                                .background(Circle().fill(Tema.tarjeta))
                        }
                        Button { Reproductor.compartido.reproducir(a.populares, contexto: "artista") } label: {
                            Image(systemName: "play.fill").font(.title2).foregroundStyle(.black).frame(width: 58, height: 58)
                                .background(Circle().fill(Color.white))
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal)

                    if !a.populares.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Populares").titulo().padding(.horizontal)
                            ListaCanciones(canciones: todas ? a.populares : Array(a.populares.prefix(5)), contexto: "artista", numerada: true)
                            if a.populares.count > 5 {
                                Button(todas ? "Ver menos" : "Ver más") { withAnimation { todas.toggle() } }
                                    .buttonStyle(BotonSecundario()).padding(.horizontal)
                            }
                        }
                    }
                    if !a.albumes.isEmpty {
                        Carrusel(titulo: "Álbumes") { ForEach(a.albumes) { TarjetaAlbum(album: $0) } }
                    }
                    if !a.sencillos.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Sencillos").titulo().padding(.horizontal)
                            ListaCanciones(canciones: a.sencillos, contexto: "artista")
                        }
                    }
                    if !a.relacionados.isEmpty {
                        Carrusel(titulo: "Te puede gustar") { ForEach(a.relacionados) { TarjetaArtista(artista: $0) } }
                    }
                }
                .padding(.bottom, 20)
            }
            .ignoresSafeArea(edges: .top)
            .refreshable { await recargar() }
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Playlist

struct PlaylistVista: View {
    let id: Int
    @State private var playlist: Playlist?
    @State private var error: String?
    @State private var editando = false
    @State private var ordenando = false
    @State private var recomendadas: [Cancion] = []
    @State private var agregando: Set<Int> = []
    @State private var confirmarBorrar = false
    @State private var foto: PhotosPickerItem?
    @State private var eligiendoFoto = false
    @State private var biblioteca = Biblioteca.compartida
    @Environment(\.dismiss) private var cerrar

    var body: some View {
        Group {
            if let p = playlist {
                if ordenando { listaOrden(p) } else { detalle(p) }
            } else if let error {
                EstadoVista(icono: "exclamationmark.triangle", titulo: "No se pudo cargar", texto: error) { Task { await cargar() } }
            } else {
                Cargando()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { if playlist == nil { await cargar() } }
        .onReceive(NotificationCenter.default.publisher(for: .cancionActualizada)) { _ in Task { await cargar() } }
        .sheet(isPresented: $editando) {
            if let p = playlist { EditarPlaylistHoja(playlist: p) { nueva in Task { await cargar(); _ = nueva } } }
        }
        .photosPicker(isPresented: $eligiendoFoto, selection: $foto, matching: .images)
        .onChange(of: foto) { _, item in
            guard let item else { return }
            Task { await subirImagen(item) }
        }
        .confirmationDialog("¿Borrar esta playlist?", isPresented: $confirmarBorrar, titleVisibility: .visible) {
            Button("Borrar", role: .destructive) { Task { await borrar() } }
        } message: { Text("No se puede deshacer.") }
    }

    private func cargar() async {
        do {
            let nueva: Playlist = try await API.get("/api/playlists/\(id)")
            playlist = nueva
            error = nil
            if nueva.propia, let semilla = nueva.canciones.last {
                let excluir = nueva.canciones.map { String($0.id) }.joined(separator: ",")
                recomendadas = (try? await API.get("/api/radio/\(semilla.id)?cantidad=8&excluir=\(excluir)")) ?? []
            }
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func detalle(_ p: Playlist) -> some View {
        ScrollView {
            VStack(spacing: 22) {
                Encabezado(titulo: p.nombre, subtitulo: p.propia ? nil : p.autor,
                           detalle: [p.publica ? "Pública" : "Privada", Formato.canciones(p.canciones.count), Formato.duracionLarga(p.duracionTotal)]
                               .joined(separator: " · "),
                           canciones: p.canciones, contexto: "playlist") {
                    PortadaPlaylist(playlist: p, radio: 12)
                } extra: {
                    if !p.propia {
                        Button { biblioteca.alternarPlaylist(p.id) } label: {
                            Image(systemName: biblioteca.playlistGuardada(p.id) ? "checkmark.circle.fill" : "plus.circle")
                                .font(.title2)
                                .foregroundStyle(biblioteca.playlistGuardada(p.id) ? Tema.celeste : Tema.texto)
                        }
                    }
                    BotonDescargarLista(canciones: p.canciones)
                    menu(p)
                }
                if let d = p.descripcion, !d.isEmpty {
                    Text(d).font(.subheadline).foregroundStyle(Tema.secundario).frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal)
                }
                if let autor = p.autorUsername, !p.propia {
                    NavigationLink(value: Destino.perfil(autor)) {
                        Label("De \(p.autor ?? autor)", systemImage: "person.crop.circle")
                            .font(.subheadline.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                }
                if p.canciones.isEmpty {
                    EstadoVista(icono: "music.note.list", titulo: "Playlist vacía",
                                texto: "Agrega canciones desde el menú … de cualquier canción.")
                } else {
                    LazyVStack(spacing: 10) {
                        ForEach(Array(p.canciones.enumerated()), id: \.element.id) { i, c in
                            FilaCancion(cancion: c) {
                                Reproductor.compartido.reproducir(p.canciones, desde: i, contexto: "playlist")
                            }
                            .contextMenu {
                                OpcionesCancion(cancion: c)
                                if p.propia {
                                    Button(role: .destructive) { Task { await quitar(c) } } label: {
                                        Label("Quitar de esta playlist", systemImage: "minus.circle")
                                    }
                                }
                            }
                        }
                    }
                    .padding(.horizontal)
                }
                if p.propia, !recomendadas.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Recomendadas para esta playlist").titulo()
                        ForEach(recomendadas) { c in
                            HStack {
                                FilaCancion(cancion: c) { Reproductor.compartido.reproducirSuelta(c, contexto: "recomendadas") }
                                Button { Task { await agregarRecomendada(c) } } label: { Image(systemName: "plus.circle") }
                                    .disabled(agregando.contains(c.id)).accessibilityLabel("Agregar a esta playlist")
                            }
                        }
                    }.padding(.horizontal)
                }
            }
            .padding(.bottom, 20)
        }
        .refreshable { await cargar() }
    }

    private func agregarRecomendada(_ c: Cancion) async {
        guard !agregando.contains(c.id) else { return }; agregando.insert(c.id)
        defer { agregando.remove(c.id) }
        do {
            try await API.hacer("POST", "/api/playlists/\(id)/canciones", ["ids": [c.id]])
            recomendadas.removeAll { $0.id == c.id }
            await cargar(); await biblioteca.recargarPlaylists()
        } catch { Avisos.compartido.error(error) }
    }

    private func menu(_ p: Playlist) -> some View {
        Menu {
            if p.propia {
                Button { editando = true } label: { Label("Editar nombre y privacidad", systemImage: "pencil") }
                Button { withAnimation { ordenando = true } } label: { Label("Ordenar o quitar canciones", systemImage: "arrow.up.arrow.down") }
                Button { eligiendoFoto = true } label: { Label("Cambiar portada", systemImage: "photo") }
                if p.portadaUrl != nil {
                    Button { Task { await quitarImagen() } } label: { Label("Quitar portada", systemImage: "photo.badge.minus") }
                }
            }
            Button { Reproductor.compartido.agregarAlFinal(p.canciones) } label: { Label("Agregar a la cola", systemImage: "text.append") }
            Button { Task { await duplicar() } } label: {
                Label(p.propia ? "Duplicar" : "Guardar una copia en mi biblioteca", systemImage: "plus.square.on.square")
            }
            if let url = API.url("/#/playlist/\(p.id)") {
                ShareLink(item: url, subject: Text(p.nombre)) { Label("Compartir enlace", systemImage: "square.and.arrow.up") }
            }
            if p.propia {
                Divider()
                Button(role: .destructive) { confirmarBorrar = true } label: { Label("Borrar playlist", systemImage: "trash") }
            }
        } label: { Image(systemName: "ellipsis").font(.title2).frame(width: 40, height: 40) }
    }

    private func listaOrden(_ p: Playlist) -> some View {
        List {
            ForEach(p.canciones) { c in
                HStack(spacing: 12) {
                    Portada(ruta: c.portadaUrl, tamano: 40, radio: 5)
                    VStack(alignment: .leading) {
                        Text(c.nombre).lineLimit(1)
                        Text(c.artista).font(.caption).foregroundStyle(Tema.secundario)
                    }
                }
            }
            .onMove { origen, destino in
                var cs = p.canciones
                cs.move(fromOffsets: origen, toOffset: destino)
                playlist?.canciones = cs
                Task { await guardarOrden(cs) }
            }
            .onDelete { idx in
                let quitadas = idx.map { p.canciones[$0] }
                let antes = p.canciones.map(\.id)
                playlist?.canciones.remove(atOffsets: idx)
                Task { for c in quitadas { await quitar(c, recargar: false, antes: antes) } }
            }
        }
        .environment(\.editMode, .constant(.active))
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Listo") { withAnimation { ordenando = false } }
            }
        }
    }

    private func guardarOrden(_ cs: [Cancion]) async {
        do {
            let _: Playlist = try await API.enviar("PUT", "/api/playlists/\(id)/orden", ["ids": cs.map(\.id)])
        } catch {
            Avisos.compartido.error(error)
            await cargar()
        }
    }

    private func quitar(_ c: Cancion, recargar: Bool = true, antes: [Int]? = nil) async {
        let orden = antes ?? playlist?.canciones.map(\.id) ?? []
        do {
            try await API.hacer("DELETE", "/api/playlists/\(id)/canciones/\(c.id)")
            if recargar { await cargar() }
            await biblioteca.recargarPlaylists()
            // Como la web: «Deshacer» la vuelve a agregar en el mismo lugar.
            Avisos.compartido.mostrar("Quitada de la playlist", accion: ("Deshacer", {
                Task { await deshacerQuitar(c, orden: orden) }
            }))
        } catch {
            Avisos.compartido.error(error)
        }
    }

    private func deshacerQuitar(_ c: Cancion, orden: [Int]) async {
        do {
            try await API.hacer("POST", "/api/playlists/\(id)/canciones", ["ids": [c.id]])
            if orden.contains(c.id) {
                let _: Playlist = try await API.enviar("PUT", "/api/playlists/\(id)/orden", ["ids": orden])
            }
            await cargar(); await biblioteca.recargarPlaylists()
        } catch { Avisos.compartido.error(error) }
    }

    private func duplicar() async {
        do {
            let nueva: Playlist = try await API.enviar("POST", "/api/playlists/\(id)/duplicar")
            await biblioteca.recargarPlaylists()
            Avisos.compartido.mostrar("Copiada como “\(nueva.nombre)”")
        } catch {
            Avisos.compartido.error(error)
        }
    }

    private func borrar() async {
        do {
            try await API.hacer("DELETE", "/api/playlists/\(id)")
            await biblioteca.recargarPlaylists()
            cerrar()
        } catch {
            Avisos.compartido.error(error)
        }
    }

    private func subirImagen(_ item: PhotosPickerItem) async {
        guard let datos = try? await item.loadTransferable(type: Data.self),
              let jpg = Fotos.jpeg(datos, maximo: 1200) else { return }
        do {
            playlist = try await API.subir("/api/playlists/\(id)/imagen",
                                           archivos: [ArchivoSubida(campo: "file", nombre: "portada.jpg", tipo: "image/jpeg", datos: jpg)])
            await cargar()
            await biblioteca.recargarPlaylists()
        } catch {
            Avisos.compartido.error(error)
        }
        foto = nil
    }

    private func quitarImagen() async {
        do {
            let _: Playlist = try await API.enviar("DELETE", "/api/playlists/\(id)/imagen")
            await cargar()
        } catch {
            Avisos.compartido.error(error)
        }
    }
}

/// Convierte fotos a JPEG de tamaño razonable antes de subirlas.
enum Fotos {
    static func jpeg(_ datos: Data, maximo: CGFloat) -> Data? {
        guard let img = UIImage(data: datos) else { return nil }
        let m = max(img.size.width, img.size.height)
        let f = m > maximo ? maximo / m : 1
        let tam = CGSize(width: img.size.width * f, height: img.size.height * f)
        let r = UIGraphicsImageRenderer(size: tam)
        let nueva = r.image { _ in img.draw(in: CGRect(origin: .zero, size: tam)) }
        return nueva.jpegData(compressionQuality: 0.85)
    }
}

// MARK: - Género y mix

struct GeneroVista: View {
    let nombre: String
    var body: some View {
        Cargador(cargar: { () async throws -> GeneroDetalle in try await API.get("/api/generos/\(API.segmento(nombre))") }) { g, recargar in
            ScrollView {
                VStack(spacing: 22) {
                    Encabezado(titulo: g.nombre, detalle: Formato.canciones(g.canciones.count), canciones: g.canciones, contexto: "genero") {
                        Mosaico(rutas: Array(Set(g.canciones.compactMap(\.portadaUrl))).sorted().prefix(4).map { $0 }, radio: 12)
                    } extra: {
                        BotonDescargarLista(canciones: g.canciones)
                        NavigationLink(value: Destino.mix(id: "genero:" + g.nombre, titulo: "Mix de " + g.nombre)) {
                            Image(systemName: "sparkles").font(.title2)
                        }
                        .accessibilityLabel("Mix de \(g.nombre)")
                        // Aleatorio con todo el género del servidor, no solo las canciones cargadas (`/api/shuffle/genero`).
                        Button {
                            Task {
                                if let cs: [Cancion] = try? await API.get("/api/shuffle/genero/\(API.segmento(g.nombre))?cantidad=50"), !cs.isEmpty {
                                    Reproductor.compartido.reproducir(cs, contexto: "genero")
                                } else { Avisos.compartido.mostrar("No se pudo crear la mezcla") }
                            }
                        } label: { Image(systemName: "dice").font(.title2) }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Aleatorio de todo el género")
                    }
                    ListaCanciones(canciones: g.canciones, contexto: "genero")
                }
                .padding(.bottom, 20)
            }
            .refreshable { await recargar() }
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct MixVista: View {
    let id: String
    let titulo: String
    @State private var version = 0
    @State private var guardando = false
    private var clave: String { version == 0 || id.hasPrefix("genero:") || id.hasPrefix("artista:") ? id : "aleatorio" }
    var body: some View {
        Cargador(cargar: { () async throws -> [Cancion] in try await API.get("/api/mixes/\(API.segmento(clave))?cantidad=40") }) { cs, recargar in
            ScrollView {
                VStack(spacing: 22) {
                    Encabezado(titulo: titulo, detalle: "Mix hecho para ti · \(Formato.canciones(cs.count))", canciones: cs, contexto: "mix") {
                        Mosaico(rutas: Array(cs.compactMap(\.portadaUrl).prefix(4)), radio: 12)
                    } extra: {
                        BotonDescargarLista(canciones: cs)
                        Button { Hojas.compartidas.agregarAPlaylist = cs } label: {
                            Image(systemName: "text.badge.plus").font(.title2)
                        }
                    }
                    Button("Guardar este mix como playlist") {
                        Task {
                            guard !guardando else { return }; guardando = true; defer { guardando = false }
                            do {
                                let nueva = try await Biblioteca.compartida.crearPlaylist(nombre: titulo, canciones: cs.map(\.id))
                                Navegacion.compartida.ir(.playlist(nueva.id))
                            } catch { Avisos.compartido.error(error) }
                        }
                    }.disabled(guardando || cs.isEmpty)
                    ListaCanciones(canciones: cs, contexto: "mix")
                }
                .padding(.bottom, 20)
            }
            .refreshable { await recargar() }
        }
        .id(version)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Renovar mix") { version += 1 } } }
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Favoritos, historial y descargas

struct FavoritosVista: View {
    var body: some View {
        Cargador(cargar: { () async throws -> [Cancion] in try await API.get("/api/usuario/favoritos") }) { cs, recargar in
            ScrollView {
                VStack(spacing: 22) {
                    Encabezado(titulo: "Tus favoritos", detalle: Formato.canciones(cs.count), canciones: cs, contexto: "favoritos") {
                        ZStack {
                            RoundedRectangle(cornerRadius: 12).fill(LinearGradient(colors: [Tema.lila, Tema.rosa], startPoint: .topLeading, endPoint: .bottomTrailing))
                            Image(systemName: "heart.fill").font(.system(size: 80)).foregroundStyle(.white)
                        }
                    } extra: {
                        BotonDescargarLista(canciones: cs)
                    }
                    if cs.isEmpty {
                        EstadoVista(icono: "heart", titulo: "Aún no tienes favoritos", texto: "Toca el corazón en cualquier canción.")
                    }
                    ListaCanciones(canciones: cs, contexto: "favoritos")
                }
                .padding(.bottom, 20)
            }
            .refreshable { await recargar() }
        }
        .navigationTitle("Favoritos")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct HistorialVista: View {
    var body: some View {
        Cargador(cargar: { () async throws -> [Cancion] in try await API.get("/api/usuario/recientes?limite=100") }) { cs, recargar in
            ScrollView {
                if cs.isEmpty {
                    EstadoVista(icono: "clock", titulo: "Todavía no escuchas nada")
                }
                ListaCanciones(canciones: cs, contexto: "historial", enOrden: false).padding(.vertical)
            }
            .refreshable { await recargar() }
        }
        .navigationTitle("Historial")
    }
}

struct DescargasVista: View {
    @State private var descargas = Descargas.compartido
    @State private var espacio: Int64 = 0
    @State private var confirmar = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    VStack(alignment: .leading) {
                        Text(Formato.canciones(descargas.canciones.count)).font(.headline)
                        Text(ByteCountFormatter.string(fromByteCount: espacio, countStyle: .file))
                            .font(.subheadline).foregroundStyle(Tema.secundario)
                    }
                    Spacer()
                    if !descargas.canciones.isEmpty {
                        Button { Reproductor.compartido.reproducir(descargas.canciones, contexto: "descargas", mezclar: true) } label: {
                            Image(systemName: "shuffle").font(.title3.weight(.semibold)).frame(width: 48, height: 48)
                                .background(Circle().fill(Tema.tarjeta))
                        }
                        Button { Reproductor.compartido.reproducir(descargas.canciones, contexto: "descargas") } label: {
                            Image(systemName: "play.fill").font(.title2).foregroundStyle(.black).frame(width: 54, height: 54)
                                .background(Circle().fill(Color.white))
                        }
                    }
                }
                .buttonStyle(.plain)
                .padding(.horizontal)

                if !descargas.enCurso.isEmpty {
                    Label("Descargando \(Formato.canciones(descargas.enCurso.count))…", systemImage: "arrow.down.circle")
                        .font(.subheadline).foregroundStyle(Tema.celeste).padding(.horizontal)
                }
                if !descargas.enCurso.isEmpty {
                    Button("Cancelar descargas pendientes", role: .destructive) { descargas.cancelarPendientes() }.padding(.horizontal)
                }
                if !descargas.fallidas.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("No se pudieron descargar \(descargas.fallidas.count) canciones").foregroundStyle(Tema.rosa)
                        ForEach(Array(descargas.fallidas.values).sorted { $0.nombre < $1.nombre }) { c in
                            Text("\(c.nombre): \(descargas.errores[c.id] ?? "Error de descarga")").font(.caption)
                        }
                        Button("Reintentar descargas") { descargas.reintentar() }
                    }.padding(.horizontal)
                }
                if descargas.canciones.isEmpty {
                    EstadoVista(icono: "arrow.down.circle", titulo: "Sin descargas",
                                texto: "Descarga canciones, álbumes o playlists para escucharlos sin conexión.")
                } else {
                    ListaCanciones(canciones: descargas.canciones, contexto: "descargas")
                    Button("Borrar todas las descargas", role: .destructive) { confirmar = true }
                        .frame(maxWidth: .infinity).padding(.top)
                }
            }
            .padding(.vertical)
        }
        .navigationTitle("Descargas")
        .onAppear { espacio = descargas.espacio() }
        .onChange(of: descargas.canciones.count) { _, _ in espacio = descargas.espacio() }
        .confirmationDialog("¿Borrar todas las descargas de este iPhone?", isPresented: $confirmar, titleVisibility: .visible) {
            Button("Borrar", role: .destructive) {
                descargas.borrarTodo()
            }
        }
    }
}

// MARK: - Todos los álbumes y artistas

struct TodosAlbumesVista: View {
    @State private var filtro = ""
    var body: some View {
        Cargador(cargar: { () async throws -> [Album] in try await API.get("/api/albumes") }) { lista, recargar in
            let vis = filtro.isEmpty ? lista : lista.filter { $0.nombre.localizedCaseInsensitiveContains(filtro) || $0.artista.localizedCaseInsensitiveContains(filtro) }
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 14)], spacing: 18) {
                    ForEach(vis) { TarjetaAlbum(album: $0, ancho: 160) }
                }
                .padding()
            }
            .refreshable { await recargar() }
        }
        .searchable(text: $filtro, prompt: "Filtrar álbumes")
        .navigationTitle("Álbumes")
    }
}

struct TodosArtistasVista: View {
    @State private var filtro = ""
    var body: some View {
        Cargador(cargar: { () async throws -> [Artista] in try await API.get("/api/artistas") }) { lista, recargar in
            let vis = filtro.isEmpty ? lista : lista.filter { $0.nombre.localizedCaseInsensitiveContains(filtro) }
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 14)], spacing: 18) {
                    ForEach(vis) { TarjetaArtista(artista: $0, ancho: 105) }
                }
                .padding()
            }
            .refreshable { await recargar() }
        }
        .searchable(text: $filtro, prompt: "Filtrar artistas")
        .navigationTitle("Artistas")
    }
}
