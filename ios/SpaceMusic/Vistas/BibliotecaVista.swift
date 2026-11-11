import SwiftUI

struct BibliotecaVista: View {
    enum Filtro: String, CaseIterable { case todo = "Todo", playlists = "Playlists", albumes = "Álbumes" }

    @State private var biblioteca = Biblioteca.compartida
    @State private var descargas = Descargas.compartido
    @State private var filtro: Filtro = .todo
    @State private var guardadas: [Playlist] = []
    @State private var albumes: [Album] = []
    @State private var creando = false

    var body: some View {
        List {
            Section {
                Picker("Mostrar", selection: $filtro) {
                    ForEach(Filtro.allCases, id: \.self) { Text($0.rawValue) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }

            if filtro == .todo {
                Section {
                    acceso("Tus favoritos", "\(biblioteca.favoritos.count) canciones", "heart.fill", Tema.rosa, .favoritos)
                    acceso("Descargas", "\(descargas.canciones.count) sin conexión", "arrow.down.circle.fill", Tema.celeste, .descargas)
                    acceso("Historial", "Lo que escuchaste", "clock.fill", Tema.lila, .historial)
                }
            }

            if filtro != .albumes {
                Section("Tus playlists") {
                    Button { creando = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "plus").font(.title3).frame(width: 56, height: 56)
                                .background(RoundedRectangle(cornerRadius: 8).fill(Tema.tarjeta))
                            Text("Nueva playlist").font(.body.weight(.medium))
                        }
                    }
                    .buttonStyle(.plain)
                    ForEach(biblioteca.playlists.filter(\.propia)) { filaPlaylist($0) }
                }
                if !guardadas.isEmpty {
                    Section("Playlists guardadas") {
                        ForEach(guardadas) { filaPlaylist($0) }
                    }
                }
            }

            if filtro != .playlists, !albumes.isEmpty {
                Section("Álbumes guardados") {
                    ForEach(albumes) { a in
                        NavigationLink(value: Destino.album(a.nombre)) {
                            HStack(spacing: 12) {
                                Portada(ruta: a.portadaUrl, tamano: 56, radio: 8, icono: "square.stack")
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(a.nombre).font(.body.weight(.medium)).lineLimit(1)
                                    Text("Álbum · \(a.artista)").font(.subheadline).foregroundStyle(Tema.secundario).lineLimit(1)
                                }
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Biblioteca")
        .refreshable { await recargar() }
        .task { await recargar() }
        .sheet(isPresented: $creando) { EditarPlaylistHoja(playlist: nil) }
    }

    private func acceso(_ t: String, _ d: String, _ icono: String, _ color: Color, _ destino: Destino) -> some View {
        NavigationLink(value: destino) {
            HStack(spacing: 12) {
                Image(systemName: icono).font(.title2).foregroundStyle(.black)
                    .frame(width: 56, height: 56)
                    .background(RoundedRectangle(cornerRadius: 8).fill(color.gradient))
                VStack(alignment: .leading, spacing: 2) {
                    Text(t).font(.body.weight(.medium))
                    Text(d).font(.subheadline).foregroundStyle(Tema.secundario)
                }
            }
        }
    }

    private func filaPlaylist(_ p: Playlist) -> some View {
        NavigationLink(value: Destino.playlist(p.id)) {
            HStack(spacing: 12) {
                PortadaPlaylist(playlist: p, radio: 8).frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.nombre).font(.body.weight(.medium)).lineLimit(1)
                    HStack(spacing: 4) {
                        if p.publica { Image(systemName: "globe").font(.caption2) }
                        Text(p.propia ? "Playlist · \(Formato.canciones(p.cancionesCount))" : "Playlist · \(p.autor ?? "")")
                    }
                    .font(.subheadline).foregroundStyle(Tema.secundario).lineLimit(1)
                }
            }
        }
    }

    private func recargar() async {
        await biblioteca.cargar()
        // Playlists guardadas de otras personas: como la web, una sola petición a las públicas;
        // solo se piden una por una las guardadas que ya no aparecen ahí.
        let todas = biblioteca.playlistsGuardadas
        let publicas: [Playlist] = (try? await API.get("/api/playlists/publicas")) ?? []
        let porId = Dictionary(publicas.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let ids = todas.filter { porId[$0] == nil }
        let sueltas = await withTaskGroup(of: (Int, Playlist?).self) { grupo in
            var indice = 0
            var resultados: [Int: Playlist] = [:]
            for i in 0..<min(4, ids.count) {
                let id = ids[i]
                grupo.addTask { (i, try? await API.get("/api/playlists/\(id)")) }
                indice += 1
            }
            while let resultado = await grupo.next() {
                if let playlist = resultado.1 { resultados[resultado.0] = playlist }
                if indice < ids.count, !Task.isCancelled {
                    let posicion = indice, id = ids[indice]
                    grupo.addTask { (posicion, try? await API.get("/api/playlists/\(id)")) }
                    indice += 1
                }
            }
            return resultados.keys.sorted().compactMap { resultados[$0] }
        }
        let porIdSueltas = Dictionary(sueltas.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        guardadas = todas.compactMap { porId[$0] ?? porIdSueltas[$0] }
        // Álbumes guardados: se leen de la lista de álbumes
        let nombres = biblioteca.albumesGuardados
        if !nombres.isEmpty, let todos: [Album] = try? await API.get("/api/albumes") {
            albumes = nombres.compactMap { n in todos.first { $0.nombre == n } }
        } else {
            albumes = []
        }
    }
}

/// Crear o editar una playlist.
struct EditarPlaylistHoja: View {
    let playlist: Playlist?
    var alGuardar: ((Playlist) -> Void)? = nil

    @Environment(\.dismiss) private var cerrar
    @State private var nombre = ""
    @State private var descripcion = ""
    @State private var publica = false
    @State private var guardando = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Nombre", text: $nombre)
                TextField("Descripción (opcional)", text: $descripcion, axis: .vertical).lineLimit(2...5)
                Toggle("Pública (la ven otras personas)", isOn: $publica).tint(Tema.celeste)
            }
            .navigationTitle(playlist == nil ? "Nueva playlist" : "Editar playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { cerrar() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(playlist == nil ? "Crear" : "Guardar", action: guardar)
                        .disabled(nombre.trimmingCharacters(in: .whitespaces).isEmpty || guardando)
                }
            }
            .onAppear {
                if let p = playlist {
                    nombre = p.nombre
                    descripcion = p.descripcion ?? ""
                    publica = p.publica
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func guardar() {
        guardando = true
        Task {
            defer { guardando = false }
            do {
                let n = nombre.trimmingCharacters(in: .whitespaces)
                let p: Playlist
                if let actual = playlist {
                    p = try await API.enviar("PUT", "/api/playlists/\(actual.id)",
                                             ["nombre": n, "descripcion": descripcion, "publica": publica])
                    await Biblioteca.compartida.recargarPlaylists()
                } else {
                    p = try await Biblioteca.compartida.crearPlaylist(nombre: n, descripcion: descripcion, publica: publica)
                }
                alGuardar?(p)
                cerrar()
            } catch {
                Avisos.compartido.error(error)
            }
        }
    }
}
