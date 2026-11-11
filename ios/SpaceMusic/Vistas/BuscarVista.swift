import SwiftUI

struct BuscarVista: View {
    @State private var texto = ""
    @State private var resultado: Busqueda?
    @State private var generos: [Genero] = []
    @State private var buscando = false
    @State private var error: String?
    @AppStorage("busquedasRecientes") private var recientesGuardadas = ""

    private var recientes: [String] { recientesGuardadas.split(separator: "\n").map(String.init) }

    var body: some View {
        ScrollView {
            if texto.trimmingCharacters(in: .whitespaces).isEmpty {
                explorar
            } else if let r = resultado {
                resultados(r)
            } else if let error {
                EstadoVista(icono: "wifi.exclamationmark", titulo: "No se pudo buscar", texto: error)
            } else {
                Cargando()
            }
        }
        .scrollDismissesKeyboard(.immediately)
        .navigationTitle("Buscar")
        .searchable(text: $texto, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "Canciones, artistas, géneros o ánimo")
        .onSubmit(of: .search) { guardarReciente(texto) }
        .task(id: texto) {
            let q = texto.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty else { resultado = nil; return }
            try? await Task.sleep(for: .milliseconds(280))
            if Task.isCancelled { return }
            buscando = true
            defer { buscando = false }
            do {
                let r: Busqueda = try await API.get("/api/buscar?q=\(API.codificar(q))")
                if !Task.isCancelled { resultado = r; error = nil }
            } catch is CancellationError {
            } catch {
                self.error = error.localizedDescription
                resultado = nil
            }
        }
        .task { if generos.isEmpty { generos = (try? await API.get("/api/generos")) ?? [] } }
    }

    private var explorar: some View {
        VStack(alignment: .leading, spacing: 22) {
            if !recientes.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Búsquedas recientes").titulo()
                        Spacer()
                        Button("Borrar") { recientesGuardadas = "" }.font(.subheadline)
                    }
                    FlujoEtiquetas(etiquetas: recientes) { texto = $0 }
                }
                .padding(.horizontal)
            }
            VStack(alignment: .leading, spacing: 10) {
                Text("Prueba con un ánimo").titulo()
                FlujoEtiquetas(etiquetas: ["música relajante", "para el gym", "fiesta", "romántica", "para estudiar", "triste"]) { texto = $0 }
            }
            .padding(.horizontal)
            if !generos.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Explorar géneros").titulo()
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                        ForEach(generos) { TarjetaGenero(genero: $0) }
                    }
                }
                .padding(.horizontal)
            }
        }
        .padding(.vertical)
    }

    @ViewBuilder
    private func resultados(_ r: Busqueda) -> some View {
        if r.vacia {
            EstadoVista(icono: "magnifyingglass", titulo: "Sin resultados", texto: "No encontramos nada para “\(texto)”.")
        } else {
            LazyVStack(alignment: .leading, spacing: 26) {
                if let e = r.estilo, !e.canciones.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Text(e.titulo).titulo()
                            Spacer()
                            Button {
                                guardarReciente(texto)
                                Reproductor.compartido.reproducir(e.canciones, contexto: "buscar", mezclar: true)
                            } label: {
                                Label("Escuchar", systemImage: "play.fill")
                            }
                            .buttonStyle(BotonPrincipal())
                        }
                        .padding(.horizontal)
                        ListaCanciones(canciones: Array(e.canciones.prefix(8)), contexto: "buscar")
                    }
                }
                if !r.canciones.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Canciones").titulo().padding(.horizontal)
                        LazyVStack(spacing: 10) {
                            ForEach(r.canciones.prefix(20)) { c in
                                FilaCancion(cancion: c) {
                                    guardarReciente(texto)
                                    Reproductor.compartido.reproducirSuelta(c, contexto: "buscar")
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                }
                if !r.artistas.isEmpty {
                    Carrusel(titulo: "Artistas") { ForEach(r.artistas) { TarjetaArtista(artista: $0) } }
                }
                if !r.albumes.isEmpty {
                    Carrusel(titulo: "Álbumes") { ForEach(r.albumes) { TarjetaAlbum(album: $0) } }
                }
                if !r.playlists.isEmpty {
                    Carrusel(titulo: "Playlists") { ForEach(r.playlists) { TarjetaPlaylist(playlist: $0) } }
                }
                if !r.generos.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Géneros").titulo()
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                            ForEach(r.generos) { TarjetaGenero(genero: $0) }
                        }
                    }
                    .padding(.horizontal)
                }
                if !r.usuarios.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Personas").titulo()
                        ForEach(r.usuarios) { FilaPersona(persona: $0) }
                    }
                    .padding(.horizontal)
                }
            }
            .padding(.vertical)
        }
    }

    private func guardarReciente(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        var lista = recientes.filter { $0.lowercased() != t.lowercased() }
        lista.insert(t, at: 0)
        recientesGuardadas = lista.prefix(8).joined(separator: "\n")
    }
}

/// Etiquetas que se acomodan en varias líneas.
struct FlujoEtiquetas: View {
    let etiquetas: [String]
    let accion: (String) -> Void

    var body: some View {
        Flujo(espacio: 8) {
            ForEach(etiquetas, id: \.self) { e in
                Button { accion(e) } label: {
                    Text(e).font(.subheadline)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(Tema.tarjeta))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Distribución en filas que saltan de línea.
struct Flujo: Layout {
    var espacio: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let ancho = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, alto: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let t = s.sizeThatFits(.unspecified)
            if x + t.width > ancho, x > 0 { x = 0; y += alto + espacio; alto = 0 }
            x += t.width + espacio
            maxX = max(maxX, x)
            alto = max(alto, t.height)
        }
        return CGSize(width: min(maxX, ancho), height: y + alto)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, alto: CGFloat = 0
        for s in subviews {
            let t = s.sizeThatFits(.unspecified)
            if x + t.width > bounds.maxX, x > bounds.minX { x = bounds.minX; y += alto + espacio; alto = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(t))
            x += t.width + espacio
            alto = max(alto, t.height)
        }
    }
}
