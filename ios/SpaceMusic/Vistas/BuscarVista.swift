import SwiftUI

struct BuscarVista: View {
    @State private var texto = ""
    @State private var intento = 0
    @State private var resultado: Busqueda?
    @State private var generos: [Genero] = []
    @State private var buscando = false
    @State private var error: String?
    @State private var pestana: PestanaBusqueda = .todo
    @AppStorage("busquedasRecientes") private var recientesGuardadas = ""

    private var recientes: [String] { recientesGuardadas.split(separator: "\n").map(String.init) }

    var body: some View {
        ScrollView {
            if texto.trimmingCharacters(in: .whitespaces).isEmpty {
                explorar
            } else if buscando {
                Cargando()
            } else if let r = resultado {
                resultados(r)
            } else if let error {
                EstadoVista(icono: "wifi.exclamationmark", titulo: "No se pudo buscar", texto: error) { intento += 1 }
            } else {
                Cargando()
            }
        }
        .scrollDismissesKeyboard(.immediately)
        .navigationTitle("Buscar")
        .searchable(text: $texto, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "Canciones, artistas, géneros o ánimo")
        .onSubmit(of: .search) { guardarReciente(texto) }
        .onChange(of: texto) { _, _ in pestana = .todo }
        .task(id: texto + "\n" + String(intento)) {
            let q = texto.trimmingCharacters(in: .whitespaces)
            resultado = nil
            error = nil
            buscando = !q.isEmpty
            guard !q.isEmpty else { return }
            try? await Task.sleep(for: .milliseconds(280))
            if Task.isCancelled { return }
            defer { if !Task.isCancelled { buscando = false } }
            do {
                let r: Busqueda = try await API.get("/api/buscar?q=\(API.codificar(q))")
                if !Task.isCancelled { resultado = r; error = nil }
            } catch is CancellationError {
            } catch {
                guard !Task.isCancelled else { return }
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
                    Text("Explorar todo").titulo()
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                        ForEach(generos) { TarjetaGenero(genero: $0) }
                    }
                }
                .padding(.horizontal)
            }
        }
        .padding(.vertical)
    }

    /// Pestañas de resultados, las mismas de la web (`#/buscar?q=…&tab=…`).
    enum PestanaBusqueda: String, CaseIterable, Identifiable {
        case todo = "Todo", canciones = "Canciones", artistas = "Artistas", albumes = "Álbumes", playlists = "Playlists", personas = "Personas"
        var id: String { rawValue }
    }

    @ViewBuilder
    private func resultados(_ r: Busqueda) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(PestanaBusqueda.allCases) { p in
                        Button { withAnimation(.snappy) { pestana = p } } label: {
                            Text(p.rawValue).font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .foregroundStyle(pestana == p ? Color.black : Tema.texto)
                                .background(Capsule().fill(pestana == p ? Color.white : Tema.tarjeta))
                                .overlay(Capsule().strokeBorder(Color.white.opacity(pestana == p ? 0 : 0.14)))
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(pestana == p ? .isSelected : [])
                    }
                }
                .padding(.horizontal)
            }
            if r.vacia {
                EstadoVista(icono: "magnifyingglass", titulo: "Sin resultados para “\(texto)”", texto: "Revisa la ortografía o prueba con menos palabras.")
            } else {
                switch pestana {
                case .todo: todo(r)
                case .canciones:
                    if r.canciones.isEmpty { EstadoVista(icono: "music.note", titulo: "No hay canciones") }
                    else { ListaCanciones(canciones: r.canciones, contexto: "buscar") }
                case .artistas:
                    if r.artistas.isEmpty { EstadoVista(icono: "music.mic", titulo: "No hay artistas") }
                    else { cuadricula(minimo: 110) { ForEach(r.artistas) { TarjetaArtista(artista: $0, ancho: 105) } } }
                case .albumes:
                    if r.albumes.isEmpty { EstadoVista(icono: "square.stack", titulo: "No hay álbumes") }
                    else { cuadricula(minimo: 150) { ForEach(r.albumes) { TarjetaAlbum(album: $0, ancho: 156) } } }
                case .playlists:
                    if r.playlists.isEmpty { EstadoVista(icono: "music.note.list", titulo: "No hay playlists") }
                    else { cuadricula(minimo: 150) { ForEach(r.playlists) { TarjetaPlaylist(playlist: $0, ancho: 156) } } }
                case .personas:
                    if r.usuarios.isEmpty { EstadoVista(icono: "person.2", titulo: "No encontramos a nadie con ese nombre") }
                    else { VStack(spacing: 12) { ForEach(r.usuarios) { FilaPersona(persona: $0) } }.padding(.horizontal) }
                }
            }
        }
        .padding(.vertical)
    }

    private func cuadricula<C: View>(minimo: CGFloat, @ViewBuilder _ contenido: () -> C) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: minimo), spacing: 14)], alignment: .leading, spacing: 18) { contenido() }
            .padding(.horizontal)
    }

    @ViewBuilder
    private func todo(_ r: Busqueda) -> some View {
        LazyVStack(alignment: .leading, spacing: 28) {
            if let e = r.estilo, !e.canciones.isEmpty {
                // Género o ánimo ("banda", "relajante"): su música lista para escuchar, como en la web.
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Label("Según lo que buscaste", systemImage: "sparkles").font(.caption.weight(.bold)).foregroundStyle(Tema.lila)
                        Text(e.titulo).font(.system(.title2, design: .rounded).weight(.heavy))
                        Text(Formato.canciones(e.total ?? e.canciones.count) + " en el servidor").font(.subheadline).foregroundStyle(Tema.secundario)
                        HStack(spacing: 10) {
                            Button { guardarReciente(texto); Reproductor.compartido.reproducir(e.canciones, contexto: "buscar") } label: {
                                Label("Reproducir", systemImage: "play.fill")
                            }.buttonStyle(BotonPrincipal())
                            Button { guardarReciente(texto); Reproductor.compartido.reproducir(e.canciones, contexto: "buscar", mezclar: true) } label: {
                                Label("Aleatorio", systemImage: "shuffle")
                            }.buttonStyle(BotonSecundario())
                        }
                        .padding(.top, 6)
                    }
                    .padding(18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(LinearGradient(
                        colors: [Tema.lila.opacity(0.18), Tema.celeste.opacity(0.08), Tema.rosa.opacity(0.14)], startPoint: .topLeading, endPoint: .bottomTrailing)))
                    .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.white.opacity(0.08)))
                    .padding(.horizontal)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 14) {
                            ForEach(Array(e.canciones.prefix(20))) { TarjetaCancion(cancion: $0, lista: e.canciones, contexto: "buscar") }
                        }
                        .padding(.horizontal)
                    }
                }
            }

            if let principal = resultadoPrincipal(r) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Resultado principal").titulo().padding(.horizontal)
                    principal.padding(.horizontal)
                }
            }

            if !r.canciones.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Canciones").titulo()
                        Spacer()
                        if r.canciones.count > 5 {
                            Button("Mostrar todo") { withAnimation(.snappy) { pestana = .canciones } }
                                .font(.caption.weight(.bold))
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .overlay(Capsule().strokeBorder(Color.white.opacity(0.25)))
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                    LazyVStack(spacing: 10) {
                        ForEach(Array(r.canciones.prefix(5))) { c in
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
                Carrusel(titulo: "Artistas") { ForEach(Array(r.artistas.prefix(6))) { TarjetaArtista(artista: $0) } }
            }
            if !r.albumes.isEmpty {
                Carrusel(titulo: "Álbumes") { ForEach(Array(r.albumes.prefix(6))) { TarjetaAlbum(album: $0) } }
            }
            if !r.playlists.isEmpty {
                Carrusel(titulo: "Playlists") { ForEach(Array(r.playlists.prefix(6))) { TarjetaPlaylist(playlist: $0) } }
            }
            if !r.usuarios.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Personas").titulo()
                    ForEach(Array(r.usuarios.prefix(6))) { FilaPersona(persona: $0) }
                }
                .padding(.horizontal)
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
        }
    }

    /// Igual que la web: el artista si su nombre empieza por lo buscado (o no hay canciones); si no, la primera canción.
    private func resultadoPrincipal(_ r: Busqueda) -> AnyView? {
        let consulta = texto.trimmingCharacters(in: .whitespaces).lowercased()
        if let a = r.artistas.first, a.nombre.lowercased().hasPrefix(consulta) || r.canciones.isEmpty {
            return AnyView(TarjetaResultado(titulo: a.nombre, etiqueta: "Artista", detalle: nil, ruta: a.portadaUrl, redonda: true,
                                            destino: .artista(a.nombre)) {
                guardarReciente(texto)
                Task {
                    if let cs: [Cancion] = try? await API.get("/api/shuffle/artista/\(API.segmento(a.nombre))?cantidad=50"), !cs.isEmpty {
                        Reproductor.compartido.reproducir(cs, contexto: "artista")
                    }
                }
            })
        }
        if let c = r.canciones.first {
            return AnyView(TarjetaResultado(titulo: c.nombre, etiqueta: "Canción", detalle: c.artista, ruta: c.portadaUrl, redonda: false, destino: nil) {
                guardarReciente(texto)
                Reproductor.compartido.reproducirSuelta(c, contexto: "buscar")
            })
        }
        return nil
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

/// Tarjeta grande de "Resultado principal" (artista redondo o canción) con botón de reproducir.
struct TarjetaResultado: View {
    let titulo: String
    let etiqueta: String
    let detalle: String?
    let ruta: String?
    let redonda: Bool
    let destino: Destino?
    let reproducir: () -> Void

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Group {
                if let destino {
                    NavigationLink(value: destino) { contenido }.buttonStyle(.plain)
                } else {
                    Button(action: reproducir) { contenido }.buttonStyle(.plain)
                }
            }
            Button(action: reproducir) {
                Image(systemName: "play.fill").font(.title3).foregroundStyle(.black)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(Color.white))
                    .shadow(color: .black.opacity(0.4), radius: 10, y: 4)
            }
            .buttonStyle(.plain)
            .padding(16)
            .accessibilityLabel("Reproducir \(titulo)")
        }
    }

    private var contenido: some View {
        VStack(alignment: .leading, spacing: 12) {
            if redonda {
                ImagenRemota(ruta: ruta, icono: "music.mic", redonda: true).frame(width: 92, height: 92)
            } else {
                Portada(ruta: ruta, tamano: 92, radio: 8)
            }
            Text(titulo).font(.system(.title2, design: .rounded).weight(.heavy)).lineLimit(2)
            HStack(spacing: 8) {
                Text(etiqueta).font(.caption.weight(.bold))
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Capsule().fill(Color.white.opacity(0.1)))
                if let detalle { Text(detalle).font(.subheadline).foregroundStyle(Tema.secundario).lineLimit(1) }
            }
        }
        .padding(18)
        .padding(.trailing, 60)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tema.tarjeta))
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
