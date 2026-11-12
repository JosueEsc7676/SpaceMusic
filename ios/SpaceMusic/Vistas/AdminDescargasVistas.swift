import SwiftUI

// Administración → «Descargar música» (descargas-admin.js y descargas-buscar.js) y «Reportes» (reportes-admin.js).

// MARK: - Modelos

struct EstadoDescargasAdmin: Decodable {
    struct Lista: Decodable, Identifiable {
        var id: String { nombre }
        var nombre: String; var entradas: Int; var fecha: String?; var muestra: [String]; var enCurso: Bool
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            nombre = c.texto("nombre") ?? UUID().uuidString; entradas = c.entero("entradas") ?? 0; fecha = c.texto("fecha")
            muestra = c.lista("muestra"); enCurso = c.bool("enCurso") ?? false
        }
    }
    struct Progreso: Decodable {
        var entrada: String?; var actual: Int; var de: Int; var inicio: String?
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            entrada = c.texto("entrada"); actual = c.entero("actual") ?? 0; de = c.entero("de") ?? 0; inicio = c.texto("inicio")
        }
    }
    var instalado: Bool; var nota: String?; var activo: Bool
    var cola: [Lista]; var terminadas: [Lista]; var fallidas: [String]; var registro: [String]
    var progreso: Progreso?; var spotifyConfigurado: Bool; var spotifyLimitadoHasta: String?
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        instalado = c.bool("instalado") ?? true; nota = c.texto("nota"); activo = c.bool("activo") ?? false
        cola = c.lista("cola"); terminadas = c.lista("terminadas"); fallidas = c.lista("fallidas"); registro = c.lista("registro")
        progreso = c.v("progreso"); spotifyConfigurado = c.bool("spotifyConfigurado") ?? false; spotifyLimitadoHasta = c.texto("spotifyLimitadoHasta")
    }
}

struct ResultadoYT: Decodable, Identifiable {
    var id: String { videoId ?? browseId ?? titulo }
    var videoId: String?; var browseId: String?; var tipo: String?
    var titulo: String; var artistas: [String]; var album: String?; var anio: String?
    var duracion: Int?; var portada: String?; var estado: String
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        videoId = c.texto("videoId"); browseId = c.texto("browseId"); tipo = c.texto("tipo")
        titulo = c.texto("titulo") ?? ""; artistas = c.lista("artistas"); album = c.texto("album"); anio = c.texto("anio")
        duracion = c.entero("duracion"); portada = c.texto("portada"); estado = c.texto("estado") ?? "disponible"
    }
    var bloqueado: Bool { ["biblioteca", "en_cola", "descargando"].contains(estado) }
    var textoEstado: String? {
        switch estado {
        case "biblioteca": "En tu biblioteca"; case "en_cola": "En cola"; case "descargando": "Descargando"
        case "completado": "Completada"; case "fallido": "Falló"; default: nil
        }
    }
}

private struct BusquedaYT: Decodable {
    var resultados: [ResultadoYT]; var hayMas: Bool
    init(from decoder: Decoder) throws { let c = try decoder.campos(); resultados = c.lista("resultados"); hayMas = c.bool("hayMas") ?? false }
}
private struct GrupoYT: Decodable {
    var titulo: String?; var resultados: [ResultadoYT]
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        resultados = c.lista("resultados")
        struct Info: Decodable { var titulo: String?; init(from decoder: Decoder) throws { titulo = try decoder.campos().texto("titulo") } }
        titulo = (c.v("album") as Info?)?.titulo ?? (c.v("artista") as Info?)?.titulo
    }
}
private struct RespuestaAgregadas: Decodable {
    var agregadas: Int; var omitidas: [String: String]; var mensaje: String?
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        agregadas = c.entero("agregadas") ?? 0; omitidas = c.v("omitidas") ?? [:]; mensaje = c.texto("mensaje")
    }
}

// MARK: - Descargar música

struct DescargasAdminVista: View {
    @State private var estado: EstadoDescargasAdmin?
    @State private var error: String?
    @AppStorage("descargasBorrador") private var texto = ""
    @State private var filtro = ""
    @State private var enviando = false
    @State private var configurandoSpotify = false
    @State private var clientId = ""
    @State private var clientSecret = ""

    private var entradas: [String] {
        texto.replacingOccurrences(of: "\r", with: "").split(separator: "\n").flatMap { l -> [String] in
            let t = l.trimmingCharacters(in: .whitespaces)
            guard !t.isEmpty else { return [] }
            return t.lowercased().hasPrefix("http") && t.contains(" ") ? t.split(separator: " ").map(String.init) : [t]
        }
    }

    var body: some View {
        List {
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            if let e = estado {
                if !e.instalado {
                    EstadoVista(icono: "arrow.down.circle", titulo: "Descargas no disponibles en este servidor", texto: e.nota)
                } else {
                    seccionEstado(e)
                    Section {
                        NavigationLink { BuscarYouTubeVista { await cargar() } } label: { Label("Buscar en YouTube Music", systemImage: "magnifyingglass") }
                    } footer: { Text("Busca y agrega a la cola con un toque. Lo que ya está en tu biblioteca o en la cola no se repite.") }
                    seccionAgregar
                    Section("Cola") {
                        if e.cola.isEmpty { Text("La cola está vacía.").foregroundStyle(Tema.secundario) }
                        ForEach(e.cola) { l in
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text("\(l.entradas) entrada(s)").font(.body.weight(.semibold))
                                    Text("· \(Formato.hace(l.fecha))").font(.caption).foregroundStyle(Tema.secundario)
                                    Spacer()
                                    if l.enCurso { Text("En curso").font(.caption.weight(.bold)).foregroundStyle(.orange) }
                                }
                                Text(l.muestra.joined(separator: " · ")).font(.caption).foregroundStyle(Tema.secundario).lineLimit(2)
                            }
                            .swipeActions {
                                if !l.enCurso {
                                    Button("Quitar", role: .destructive) { Task { await accion("/api/admin/descargas/cola/\(API.segmento(l.nombre))", "DELETE", "Lista quitada") } }
                                }
                            }
                        }
                    }
                    Section("Terminadas") {
                        if e.terminadas.isEmpty { Text("Todavía no hay listas terminadas.").foregroundStyle(Tema.secundario) }
                        ForEach(e.terminadas) { l in LabeledContent("\(l.entradas) entrada(s)", value: Formato.hace(l.fecha)) }
                    }
                    Section {
                        if e.fallidas.isEmpty { Text("Nada falló. 🎉").foregroundStyle(Tema.secundario) }
                        else {
                            Text("\(e.fallidas.count) entrada(s) no se pudieron bajar después de 3 intentos. Suele ser algo pasajero: reinténtalas más tarde.")
                                .font(.caption).foregroundStyle(Tema.secundario)
                            ForEach(e.fallidas, id: \.self) { Text($0).font(.caption.monospaced()).lineLimit(2) }
                            Button("Reintentar todas") { Task { await accion("/api/admin/descargas/reintentar", "POST", nil) } }
                            Button("Olvidar la lista de fallidas", role: .destructive) { Task { await accion("/api/admin/descargas/fallidas", "DELETE", "Lista de fallidas vacía") } }
                        }
                    } header: { Text("Fallidas") }
                    Section {
                        TextField("Filtrar líneas", text: $filtro).autocorrectionDisabled().textInputAutocapitalization(.never)
                        let lineas = filtro.isEmpty ? e.registro : e.registro.filter { $0.localizedCaseInsensitiveContains(filtro) }
                        if lineas.isEmpty { Text("Todavía no hay registro.").foregroundStyle(Tema.secundario) }
                        ForEach(Array(lineas.suffix(200).enumerated()), id: \.offset) { _, l in
                            Text(l).font(.caption2.monospaced()).foregroundStyle(colorLinea(l))
                        }
                    } header: { Text("Registro en vivo") }
                    Section {
                        Label(e.spotifyConfigurado ? "Configurada: las descargas de Spotify usan tus propias claves." : "Sin configurar: con tus claves (gratis) Spotify bloquea menos.",
                              systemImage: e.spotifyConfigurado ? "checkmark.seal.fill" : "exclamationmark.circle")
                            .font(.subheadline)
                        Button(e.spotifyConfigurado ? "Cambiar claves" : "Configurar") { configurandoSpotify = true }
                    } header: { Text("Cuenta de Spotify") }
                }
            } else if error == nil { ProgressView().frame(maxWidth: .infinity) }
        }
        .navigationTitle("Descargar música")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await cargar() }
        // En vivo: cada 3 s mientras descarga; cada 15 s si no (como la web).
        .task {
            while !Task.isCancelled {
                await cargar()
                try? await Task.sleep(for: .seconds(estado?.activo == true ? 3 : 15))
            }
        }
        .sheet(isPresented: $configurandoSpotify) { hojaSpotify }
    }

    private func seccionEstado(_ e: EstadoDescargasAdmin) -> some View {
        Section {
            HStack {
                Text(e.activo ? "Descargando" : e.cola.isEmpty ? "Sin descargas pendientes" : "En pausa")
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill((e.activo ? Color.orange : e.cola.isEmpty ? Color.green : Color.gray).opacity(0.2)))
                Spacer()
            }
            if e.activo, let p = e.progreso, p.de > 0 {
                if let entrada = p.entrada { Text(entrada).font(.subheadline.weight(.semibold)).lineLimit(2) }
                ProgressView(value: Double(max(0, p.actual - 1)), total: Double(p.de)).tint(Tema.celeste)
                Text("Entrada \(p.actual) de \(p.de)" + (p.inicio.map { " · empezó \($0)" } ?? "")).font(.caption).foregroundStyle(Tema.secundario)
            } else {
                Text(e.cola.isEmpty ? "Todo al día" : "\(e.cola.count) lista(s) esperando").foregroundStyle(Tema.secundario)
            }
            if e.spotifyLimitadoHasta != nil {
                Label("Spotify limitó tus claves por un tiempo. Mientras tanto se usa el modo sin claves (más lento, pero sigue).", systemImage: "info.circle")
                    .font(.caption).foregroundStyle(.orange)
            }
            if e.activo {
                Button("Detener", role: .destructive) { Task { await accion("/api/admin/descargas/detener", "POST", nil) } }
            } else if !e.cola.isEmpty {
                Button("Reanudar") { Task { await accion("/api/admin/descargas/reanudar", "POST", nil) } }
            }
        } header: { Text("Estado") } footer: {
            Text("La descarga sigue en el servidor aunque cierres esta pantalla. Detener deja la lista en la cola y no repite lo ya bajado.")
        }
    }

    private var seccionAgregar: some View {
        Section {
            TextField("https://open.spotify.com/album/…\nhttps://www.youtube.com/watch?v=…\nBad Bunny - Tití Me Preguntó", text: $texto, axis: .vertical)
                .lineLimit(4...10).font(.callout.monospaced())
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            if !entradas.isEmpty {
                let repetidas = entradas.count - Set(entradas).count
                Text("\(entradas.count) entrada(s)" + (repetidas > 0 ? " · \(repetidas) repetida(s) se envían una vez" : ""))
                    .font(.caption).foregroundStyle(Tema.secundario)
            }
            HStack {
                Button("Vaciar", role: .destructive) { texto = "" }.disabled(texto.isEmpty)
                Spacer()
                Button { Task { await encolar() } } label: { Label("Agregar a la cola", systemImage: "arrow.down.circle") }
                    .disabled(entradas.isEmpty || enviando)
            }
            .buttonStyle(.borderless)
        } header: { Text("Agregar a la cola") } footer: {
            Text("Pega enlaces de Spotify (canción, álbum, playlist o artista), YouTube, YouTube Music, SoundCloud y otros, uno por línea, o escribe «Artista - Canción». Lo ya descargado no se repite.")
        }
    }

    private var hojaSpotify: some View {
        NavigationStack {
            Form {
                Section {
                    Text("1. Entra a developer.spotify.com/dashboard e inicia sesión.\n2. «Create app»: nombre SpaceMusic, Redirect URI http://127.0.0.1:8080, marca «Web API».\n3. En «Settings» copia el Client ID y el Client secret.")
                        .font(.subheadline)
                }
                Section {
                    TextField("Client ID", text: $clientId).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Client secret", text: $clientSecret)
                } footer: { Text("Se guardan solo en el servidor y no se vuelven a mostrar.") }
            }
            .navigationTitle("Claves de Spotify")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { configurandoSpotify = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        Task {
                            do {
                                try await API.hacer("PUT", "/api/admin/descargas/spotify", ["clientId": clientId, "clientSecret": clientSecret])
                                clientId = ""; clientSecret = ""; configurandoSpotify = false
                                Avisos.compartido.mostrar("Claves guardadas")
                                await cargar()
                            } catch { Avisos.compartido.error(error) }
                        }
                    }
                    .disabled(clientId.isEmpty || clientSecret.isEmpty)
                }
            }
        }
    }

    private func colorLinea(_ l: String) -> Color {
        if l.hasPrefix("✓") || l.contains("Todo descargado") { return .green }
        if l.hasPrefix("✗") || l.localizedCaseInsensitiveContains("error") || l.localizedCaseInsensitiveContains("falló") { return Tema.peligro }
        if l.hasPrefix("▶") || l.hasPrefix("════") { return Tema.celeste }
        if l.contains("Reintentando") || l.contains("pendiente") || l.contains("limitó") { return .orange }
        return Tema.secundario
    }

    private func cargar() async {
        do { estado = try await API.get("/api/admin/descargas?lineas=200"); error = nil }
        catch { if !Task.isCancelled, estado == nil { self.error = error.localizedDescription } }
    }
    private func encolar() async {
        enviando = true
        defer { enviando = false }
        do {
            let r: RespuestaAgregadas = try await API.enviar("POST", "/api/admin/descargas", ["texto": texto])
            Avisos.compartido.mostrar("\(r.agregadas) entrada(s) agregadas a la cola")
            texto = ""
            await cargar()
        } catch { Avisos.compartido.error(error) }
    }
    private func accion(_ ruta: String, _ metodo: String, _ ok: String?) async {
        do {
            let r: RespuestaAgregadas = try await API.enviar(metodo, ruta)
            Avisos.compartido.mostrar(r.mensaje ?? ok ?? "Listo")
            await cargar()
        } catch { Avisos.compartido.error(error) }
    }
}

/// Buscar en YouTube Music y agregar a la cola (descargas-buscar.js).
struct BuscarYouTubeVista: View {
    var alCambiar: () async -> Void
    enum Tipo: String, CaseIterable { case songs, albums, artists
        var nombre: String { switch self { case .songs: "Canciones"; case .albums: "Álbumes"; case .artists: "Artistas" } }
    }
    @State private var consulta = ""
    @State private var tipo: Tipo = .songs
    @State private var limite = 20
    @State private var resultados: [ResultadoYT] = []
    @State private var hayMas = false
    @State private var buscando = false
    @State private var error: String?
    @State private var elegidas: Set<String> = []
    @State private var grupo: (titulo: String, lista: [ResultadoYT])?

    var body: some View {
        List {
            Section {
                Picker("Tipo", selection: $tipo) { ForEach(Tipo.allCases, id: \.self) { Text($0.nombre) } }.pickerStyle(.segmented)
            } footer: { Text("Búsqueda con ytmusicapi, una biblioteca no oficial: no todo resultado se puede descargar.") }
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            if buscando { ProgressView().frame(maxWidth: .infinity) }
            ForEach(resultados) { r in
                if r.videoId != nil { filaCancion(r) } else {
                    Button { Task { await abrirGrupo(r) } } label: {
                        HStack(spacing: 12) {
                            ImagenExterna(url: r.portada, redonda: r.tipo == "artista").frame(width: 48, height: 48)
                            VStack(alignment: .leading) {
                                Text(r.titulo).lineLimit(1)
                                Text(r.tipo == "album" ? "Álbum" + (r.artistas.isEmpty ? "" : " · " + r.artistas.joined(separator: ", ")) + (r.anio.map { " · \($0)" } ?? "") : "Artista")
                                    .font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(Tema.secundario)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            if hayMas { Button("Cargar más") { limite = min(50, limite + 15); Task { await buscar() } } }
        }
        .searchable(text: $consulta, placement: .navigationBarDrawer(displayMode: .always), prompt: "Canción, artista o álbum")
        .onSubmit(of: .search) { limite = 20; Task { await buscar() } }
        .onChange(of: tipo) { _, _ in if consulta.count >= 2 { limite = 20; Task { await buscar() } } }
        .navigationTitle("YouTube Music")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !elegidas.isEmpty {
                ToolbarItem(placement: .bottomBar) {
                    Button("Agregar \(elegidas.count) a la cola") { Task { await agregar(Array(elegidas)) } }
                }
            }
        }
        .sheet(isPresented: Binding(get: { grupo != nil }, set: { if !$0 { grupo = nil } })) {
            if let g = grupo { GrupoYTHoja(titulo: g.titulo, lista: g.lista) { ids in await agregar(ids) } }
        }
    }

    private func filaCancion(_ r: ResultadoYT) -> some View {
        let id = r.videoId ?? ""
        return HStack(spacing: 12) {
            Button { if elegidas.contains(id) { elegidas.remove(id) } else { elegidas.insert(id) } } label: {
                Image(systemName: r.bloqueado ? "checkmark.circle" : elegidas.contains(id) ? "checkmark.circle.fill" : "circle")
                    .font(.title3).foregroundStyle(r.bloqueado ? Tema.secundario : Tema.celeste)
            }
            .buttonStyle(.plain).disabled(r.bloqueado)
            ImagenExterna(url: r.portada, redonda: false).frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(r.titulo).lineLimit(1)
                Text(r.artistas.joined(separator: ", ") + (r.album.map { " · \($0)" } ?? "")).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                if let t = r.textoEstado { Text(t).font(.caption2.weight(.bold)).foregroundStyle(r.estado == "fallido" ? Tema.peligro : Tema.celeste) }
            }
            Spacer(minLength: 4)
            if let d = r.duracion { Text(Formato.duracion(d)).font(.caption).monospacedDigit().foregroundStyle(Tema.secundario) }
            if !r.bloqueado {
                Button { Task { await agregar([id]) } } label: { Image(systemName: "plus.circle.fill").font(.title3) }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Agregar \(r.titulo) a la cola")
            }
        }
    }

    private func buscar() async {
        let q = consulta.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return }
        buscando = true; error = nil
        defer { buscando = false }
        do {
            let r: BusquedaYT = try await API.get("/api/admin/descargas/buscar?q=\(API.codificar(q))&tipo=\(tipo.rawValue)&limite=\(limite)", tiempo: 45)
            resultados = r.resultados; hayMas = r.hayMas
        } catch { self.error = error.localizedDescription }
    }
    private func abrirGrupo(_ r: ResultadoYT) async {
        guard let id = r.browseId else { return }
        do {
            let g: GrupoYT = try await API.get("/api/admin/descargas/buscar/\(r.tipo == "album" ? "album" : "artista")/\(API.segmento(id))", tiempo: 45)
            grupo = (g.titulo ?? r.titulo, g.resultados)
        } catch { Avisos.compartido.error(error) }
    }
    private func agregar(_ ids: [String]) async {
        do {
            let r: RespuestaAgregadas = try await API.enviar("POST", "/api/admin/descargas/canciones", ["ids": ids])
            Avisos.compartido.mostrar(r.agregadas > 0 ? "\(r.agregadas) agregada(s) a la cola" + (r.omitidas.isEmpty ? "" : " · \(r.omitidas.count) ya estaban") : "Ya estaban en la biblioteca o en la cola")
            for i in resultados.indices {
                guard let v = resultados[i].videoId, ids.contains(v) else { continue }
                resultados[i].estado = r.omitidas[v] ?? "en_cola"
            }
            ids.forEach { elegidas.remove($0) }
            await alCambiar()
        } catch { Avisos.compartido.error(error) }
    }
}

/// Canciones de un álbum o artista de YouTube Music para elegir cuáles agregar.
private struct GrupoYTHoja: View {
    let titulo: String
    let lista: [ResultadoYT]
    let agregar: ([String]) async -> Void
    @Environment(\.dismiss) private var cerrar
    @State private var sel: Set<String> = []

    var body: some View {
        NavigationStack {
            List(lista) { r in
                let id = r.videoId ?? ""
                Button { if sel.contains(id) { sel.remove(id) } else { sel.insert(id) } } label: {
                    HStack {
                        Image(systemName: r.bloqueado ? "checkmark.circle" : sel.contains(id) ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(r.bloqueado ? Tema.secundario : Tema.celeste)
                        VStack(alignment: .leading) {
                            Text(r.titulo).lineLimit(1)
                            Text(r.artistas.joined(separator: ", ")).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                        }
                        Spacer()
                        if let t = r.textoEstado { Text(t).font(.caption2.weight(.bold)).foregroundStyle(Tema.secundario) }
                    }
                }
                .buttonStyle(.plain)
                .disabled(r.bloqueado)
            }
            .navigationTitle(titulo)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { cerrar() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Agregar \(sel.count)") { Task { await agregar(Array(sel)); cerrar() } }.disabled(sel.isEmpty)
                }
            }
            .onAppear { sel = Set(lista.filter { !$0.bloqueado }.compactMap(\.videoId)) }
        }
    }
}

/// Imagen de otro sitio (portadas de YouTube Music): sin credenciales del servidor.
struct ImagenExterna: View {
    let url: String?
    let redonda: Bool
    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { fase in
            if let img = fase.image { img.resizable().scaledToFill() } else { Color.white.opacity(0.08).overlay(Image(systemName: "music.note").foregroundStyle(Tema.secundario)) }
        }
        .clipShape(redonda ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: 6)))
    }
}

// MARK: - Reportes de los usuarios

struct ReporteAdmin: Decodable, Identifiable {
    var id: Int; var cancion: Cancion?; var estado: String; var motivo: String; var motivoTexto: String?
    var usuario: String?; var cliente: String?; var fecha: String?; var detalle: String?; var respuesta: String?
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0; cancion = c.v("cancion"); estado = c.texto("estado") ?? ""; motivo = c.texto("motivo") ?? ""
        motivoTexto = c.texto("motivoTexto"); usuario = c.texto("usuario"); cliente = c.texto("cliente"); fecha = c.texto("fecha")
        detalle = c.texto("detalle"); respuesta = c.texto("respuesta")
    }
}
private struct PaginaReportes: Decodable {
    var contenido: [ReporteAdmin]; var paginas: Int
    init(from decoder: Decoder) throws { let c = try decoder.campos(); contenido = c.lista("contenido"); paginas = c.entero("paginas") ?? 1 }
}

struct ReportesAdminVista: View {
    @State private var filtro = "ABIERTO"
    @State private var pagina = 0
    @State private var datos: PaginaReportes?
    @State private var error: String?
    @State private var respondiendo: (ReporteAdmin, String)?
    @State private var respuesta = ""

    var body: some View {
        List {
            Section {
                Picker("Estado", selection: $filtro) {
                    Text("Abiertos").tag("ABIERTO"); Text("Resueltos").tag("RESUELTO"); Text("Descartados").tag("DESCARTADO"); Text("Todos").tag("")
                }
                .pickerStyle(.segmented)
            } footer: { Text("Al marcar un reporte como resuelto se le avisa a quien lo envió.") }
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            if let d = datos {
                if d.contenido.isEmpty { EstadoVista(icono: "checkmark.circle", titulo: "No hay reportes aquí") }
                ForEach(d.contenido) { r in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 12) {
                            Portada(ruta: r.cancion?.portadaUrl, tamano: 48, radio: 6, icono: "flag")
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.cancion?.nombre ?? "Canción eliminada").font(.body.weight(.semibold)).lineLimit(1)
                                Text(r.cancion?.artista ?? "").font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                            }
                            Spacer()
                            Text(r.estado == "ABIERTO" ? "Abierto" : r.estado == "RESUELTO" ? "Resuelto" : "Descartado").font(.caption2.weight(.bold))
                                .foregroundStyle(r.estado == "ABIERTO" ? .orange : r.estado == "RESUELTO" ? .green : Tema.secundario)
                        }
                        Text("\(r.motivoTexto ?? r.motivo) · @\(r.usuario ?? "?") · \(r.cliente ?? "web") · \(Formato.hace(r.fecha))")
                            .font(.caption).foregroundStyle(Tema.secundario)
                        if let det = r.detalle, !det.isEmpty { Text("“\(det)”").font(.caption.italic()) }
                        if let resp = r.respuesta, !resp.isEmpty { Text("Respuesta: \(resp)").font(.caption).foregroundStyle(Tema.secundario) }
                    }
                    .swipeActions(edge: .trailing) {
                        if r.estado == "ABIERTO" {
                            Button("Resuelto") { respondiendo = (r, "RESUELTO") }.tint(.green)
                            Button("Descartar") { respondiendo = (r, "DESCARTADO") }.tint(.gray)
                        } else {
                            Button("Reabrir") { Task { await guardar(r, "ABIERTO", nil) } }.tint(.orange)
                        }
                    }
                    .swipeActions(edge: .leading) {
                        if let c = r.cancion { Button { Reproductor.compartido.reproducirSuelta(c) } label: { Label("Escuchar", systemImage: "play.fill") }.tint(Tema.celeste) }
                    }
                }
                if d.paginas > 1 {
                    HStack {
                        Button { pagina -= 1 } label: { Image(systemName: "chevron.left") }.disabled(pagina == 0)
                        Spacer(); Text("Página \(pagina + 1) de \(d.paginas)").font(.caption); Spacer()
                        Button { pagina += 1 } label: { Image(systemName: "chevron.right") }.disabled(pagina + 1 >= d.paginas)
                    }
                    .buttonStyle(.borderless)
                }
            } else if error == nil { ProgressView().frame(maxWidth: .infinity) }
        }
        .navigationTitle("Reportes")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(filtro)-\(pagina)") { await cargar() }
        .onChange(of: filtro) { _, _ in pagina = 0 }
        .refreshable { await cargar() }
        .alert(respondiendo?.1 == "RESUELTO" ? "Marcar como resuelto" : "Descartar reporte",
               isPresented: Binding(get: { respondiendo != nil }, set: { if !$0 { respondiendo = nil } })) {
            TextField("Mensaje para el usuario (opcional)", text: $respuesta)
            Button("Guardar") {
                if let par = respondiendo {
                    let texto = respuesta.isEmpty ? nil : respuesta
                    respuesta = ""
                    Task { await guardar(par.0, par.1, texto) }
                }
            }
            Button("Cancelar", role: .cancel) {}
        }
    }

    private func cargar() async {
        do { datos = try await API.get("/api/admin/comunidad/reportes?estado=\(filtro)&pagina=\(pagina)&tam=25"); error = nil }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func guardar(_ r: ReporteAdmin, _ estado: String, _ texto: String?) async {
        var cuerpo: [String: Any] = ["estado": estado]
        if let texto { cuerpo["respuesta"] = texto } else { cuerpo["respuesta"] = NSNull() }
        do { try await API.hacer("PUT", "/api/admin/comunidad/reportes/\(r.id)", cuerpo); Avisos.compartido.mostrar("Guardado"); await cargar() }
        catch { Avisos.compartido.error(error) }
    }
}
