import SwiftUI

/// Elegir una playlist propia (o crear una) para agregar canciones.
struct AgregarAPlaylistHoja: View {
    let canciones: [Cancion]
    @Environment(\.dismiss) private var cerrar
    @State private var biblioteca = Biblioteca.compartida
    @State private var creando = false
    @State private var nombre = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if creando {
                        HStack {
                            TextField("Nombre de la playlist", text: $nombre)
                                .submitLabel(.done)
                                .onSubmit(crear)
                            Button("Crear", action: crear).disabled(nombre.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                    } else {
                        Button { creando = true } label: { Label("Nueva playlist", systemImage: "plus") }
                    }
                }
                Section("Tus playlists") {
                    ForEach(biblioteca.playlists.filter(\.propia)) { p in
                        Button {
                            Task {
                                await biblioteca.agregar(canciones, a: p)
                                cerrar()
                            }
                        } label: {
                            HStack(spacing: 12) {
                                PortadaPlaylist(playlist: p, radio: 6).frame(width: 44, height: 44)
                                VStack(alignment: .leading) {
                                    Text(p.nombre).foregroundStyle(Tema.texto)
                                    Text(Formato.canciones(p.cancionesCount)).font(.caption).foregroundStyle(Tema.secundario)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(canciones.count == 1 ? "Agregar “\(canciones[0].nombre)”" : "Agregar \(Formato.canciones(canciones.count))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { cerrar() } } }
            .task { await biblioteca.recargarPlaylists() }
        }
        .presentationDetents([.medium, .large])
    }

    private func crear() {
        let n = nombre.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        Task {
            do {
                _ = try await biblioteca.crearPlaylist(nombre: n, canciones: canciones.map(\.id))
                Avisos.compartido.mostrar("Playlist “\(n)” creada")
                cerrar()
            } catch {
                Avisos.compartido.error(error)
            }
        }
    }
}

/// Enviar la canción por mensaje a un amigo (tienen que seguirse los dos).
struct EnviarCancionHoja: View {
    let cancion: Cancion
    @Environment(\.dismiss) private var cerrar
    @State private var amigos: [PerfilMini]?
    @State private var texto = ""
    @State private var enviando: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        Portada(ruta: cancion.portadaUrl, tamano: 48, radio: 6)
                        VStack(alignment: .leading) {
                            Text(cancion.nombre).font(.headline)
                            Text(cancion.artista).font(.subheadline).foregroundStyle(Tema.secundario)
                        }
                    }
                    TextField("Escribe algo (opcional)", text: $texto)
                }
                Section("Amigos") {
                    if let amigos {
                        if amigos.isEmpty {
                            Text("Para enviar mensajes, tú y la otra persona tienen que seguirse.")
                                .font(.subheadline).foregroundStyle(Tema.secundario)
                        }
                        ForEach(amigos) { a in
                            Button { enviar(a) } label: {
                                HStack(spacing: 12) {
                                    Avatar(ruta: a.fotoPerfil, nombre: a.nombre, tamano: 40)
                                    Text(a.nombre).foregroundStyle(Tema.texto)
                                    Spacer()
                                    if enviando == a.username { ProgressView() } else {
                                        Image(systemName: "paperplane.fill").foregroundStyle(Tema.celeste)
                                    }
                                }
                            }
                            .disabled(enviando != nil)
                        }
                    } else {
                        ProgressView()
                    }
                }
            }
            .navigationTitle("Enviar a un amigo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { cerrar() } } }
            .task { amigos = (try? await API.get("/api/mensajes/amigos")) ?? [] }
        }
        .presentationDetents([.medium, .large])
    }

    private func enviar(_ a: PerfilMini) {
        enviando = a.username
        Task {
            do {
                try await API.hacer("POST", "/api/mensajes/\(API.segmento(a.username))",
                                    ["texto": texto, "cancionId": cancion.id])
                Avisos.compartido.mostrar("Enviada a \(a.nombre)")
                cerrar()
            } catch {
                Avisos.compartido.error(error)
            }
            enviando = nil
        }
    }
}
