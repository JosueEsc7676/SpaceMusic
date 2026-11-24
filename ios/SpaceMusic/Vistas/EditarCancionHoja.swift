import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

/// Usa las mismas rutas y permisos de edición que la web.
struct EditarCancionHoja: View {
    let cancion: Cancion
    @Environment(\.dismiss) private var cerrar
    @State private var nombre = ""
    @State private var artista = ""
    @State private var album = ""
    @State private var genero = ""
    @State private var anio = ""
    @State private var pista = ""
    @State private var letra = ""
    @State private var foto: PhotosPickerItem?
    @State private var ocupada = false
    @State private var cargandoLetra = true
    @State private var error: String?
    @State private var actualizada: Cancion?
    @State private var importar = false
    @State private var confirmarBorrado = false
    @State private var buscarPortada = false
    /// Qué portada cambiar: "cancion" (el álbum conserva la suya), "album" (solo el álbum) o "todo".
    @State private var alcancePortada = "cancion"

    private var tieneAlbum: Bool {
        let a = cancion.album.trimmingCharacters(in: .whitespaces).lowercased()
        return !a.isEmpty && !["single", "sencillo", "desconocido"].contains(a)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
                Section("Información") {
                    TextField("Título", text: $nombre)
                    TextField("Artista", text: $artista)
                    TextField("Álbum", text: $album)
                    TextField("Género", text: $genero)
                    TextField("Año", text: $anio).keyboardType(.numberPad)
                    TextField("Número de pista", text: $pista).keyboardType(.numberPad)
                    Button("Guardar información") { Task { await guardarInformacion() } }
                        .disabled(nombre.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                Section {
                    Portada(ruta: (actualizada ?? cancion).portadaUrl, tamano: 120)
                    if tieneAlbum {
                        Picker("Cambiar", selection: $alcancePortada) {
                            Text("Solo esta canción").tag("cancion")
                            Text("Solo el álbum").tag("album")
                            Text("Álbum y canciones").tag("todo")
                        }
                    }
                    PhotosPicker(selection: $foto, matching: .images) {
                        Label("Cambiar portada", systemImage: "photo")
                    }
                    Button { buscarPortada = true } label: {
                        Label("Buscar portada original (HD)", systemImage: "magnifyingglass")
                    }
                } header: { Text("Portada") } footer: {
                    if tieneAlbum {
                        Text(alcancePortada == "cancion" ? "El álbum conserva su portada." : alcancePortada == "album" ? "Las canciones conservan sus portadas." : "Cambia la portada del álbum y de todas sus canciones.")
                    }
                }
                Section("Letra") {
                    if cargandoLetra { ProgressView("Cargando letra…") }
                    Text("Pega texto o una letra sincronizada LRC: [01:23.45] línea.")
                        .font(.footnote).foregroundStyle(Tema.secundario)
                    TextEditor(text: $letra).frame(minHeight: 200)
                    Button("Importar archivo LRC o TXT") { importar = true }
                    Button("Buscar letra en internet") { Task { await buscarLetra() } }
                    Button("Borrar letra", role: .destructive) { confirmarBorrado = true }
                    Button("Guardar letra") { Task { await guardarLetra() } }
                        .disabled(cargandoLetra)
                }
            }
            .disabled(ocupada)
            .sheet(isPresented: $buscarPortada) {
                PortadaEnLineaHoja(cancion: actualizada ?? cancion, alcance: tieneAlbum ? alcancePortada : "cancion") { c in
                    actualizada = c
                    Reproductor.compartido.actualizar(c)
                }
            }
            .navigationTitle("Editar canción")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cerrar") { cerrar() }.disabled(ocupada)
                }
                ToolbarItem(placement: .topBarTrailing) { if ocupada { ProgressView() } }
            }
            .task {
                nombre = cancion.nombre; artista = cancion.artista
                album = cancion.album; genero = cancion.genero
                anio = cancion.anio.map(String.init) ?? ""
                pista = cancion.numeroPista.map(String.init) ?? ""
                defer { cargandoLetra = false }
                do {
                    let l: Letra = try await API.get("/api/letras/\(cancion.id)")
                    letra = l.letraSincronizada.isEmpty ? l.letra : l.letraSincronizada
                } catch {
                    self.error = "No se pudo cargar la letra: \(error.localizedDescription)"
                }
            }
            .fileImporter(isPresented: $importar, allowedContentTypes: [.plainText, UTType(filenameExtension: "lrc") ?? .data]) { resultado in
                do {
                    let url = try resultado.get()
                    let permiso = url.startAccessingSecurityScopedResource()
                    defer { if permiso { url.stopAccessingSecurityScopedResource() } }
                    let datos = try Data(contentsOf: url)
                    guard let texto = String(data: datos, encoding: .utf8) ?? String(data: datos, encoding: .isoLatin1) else { return }
                    letra = texto
                } catch { self.error = error.localizedDescription }
            }
            .confirmationDialog("¿Borrar la letra de esta canción?", isPresented: $confirmarBorrado, titleVisibility: .visible) {
                Button("Borrar letra", role: .destructive) { Task { await borrarLetra() } }
            }
            .onChange(of: foto) { _, nueva in
                guard let nueva else { return }
                Task { await cambiarPortada(nueva) }
            }
        }
    }

    private func guardarInformacion() async {
        guard !ocupada else { return }
        guard anio.isEmpty || Int(anio).map({ (1000...2999).contains($0) }) == true,
              pista.isEmpty || Int(pista).map({ (1...999).contains($0) }) == true else {
            error = "Revisa el año (1000–2999) y la pista (1–999)."; return
        }
        ocupada = true; error = nil
        defer { ocupada = false }
        do {
            let c: Cancion = try await API.enviar("PUT", "/api/canciones/\(cancion.id)/editar", [
                "nombre": nombre, "artista": artista, "album": album, "genero": genero,
                "anio": anio, "numeroPista": pista
            ])
            actualizada = c
            Reproductor.compartido.actualizar(c)
            Avisos.compartido.mostrar("Información guardada")
        } catch { self.error = error.localizedDescription }
    }

    private func cambiarPortada(_ item: PhotosPickerItem) async {
        guard !ocupada else { return }
        ocupada = true; error = nil
        defer { ocupada = false }
        do {
            guard let datos = try await item.loadTransferable(type: Data.self),
                  let jpg = Fotos.jpeg(datos, maximo: 1200) else {
                error = "No se pudo leer la foto."; return
            }
            var c: Cancion = try await API.subir("/api/canciones/\(cancion.id)/portada?alcance=\(tieneAlbum ? alcancePortada : "cancion")", archivos: [
                ArchivoSubida(campo: "file", nombre: "portada.jpg", tipo: "image/jpeg", datos: jpg)
            ])
            if let ruta = c.portadaUrl {
                c.portadaUrl = ruta + (ruta.contains("?") ? "&" : "?") + "v=\(UUID().uuidString)"
            }
            if alcancePortada == "album" && tieneAlbum {
                NotificationCenter.default.post(name: .bibliotecaCambio, object: nil)
                Avisos.compartido.mostrar("Portada del álbum guardada"); return
            }
            actualizada = c
            Reproductor.compartido.actualizar(c)
            NotificationCenter.default.post(name: .bibliotecaCambio, object: nil)
            Avisos.compartido.mostrar("Portada guardada")
        } catch { self.error = error.localizedDescription }
    }

    private func buscarLetra() async {
        guard !ocupada else { return }; ocupada = true; error = nil
        defer { ocupada = false }
        do {
            let resultado: Letra = try await API.enviar("POST", "/api/letras/\(cancion.id)/buscar?forzar=true")
            if resultado.encontrada == false { error = resultado.message ?? "No se encontró letra"; return }
            letra = resultado.letraSincronizada.isEmpty ? resultado.letra : resultado.letraSincronizada
            if Reproductor.compartido.actual?.id == cancion.id {
                Letras.compartido.cargar(actualizada ?? cancion, forzar: true)
            }
        } catch { self.error = error.localizedDescription }
    }

    private func borrarLetra() async {
        guard !ocupada else { return }; ocupada = true; error = nil
        defer { ocupada = false }
        do {
            try await API.hacer("DELETE", "/api/letras/\(cancion.id)")
            letra = ""
            if Reproductor.compartido.actual?.id == cancion.id { Letras.compartido.cargar(cancion, forzar: true) }
        } catch { self.error = error.localizedDescription }
    }

    private func guardarLetra() async {
        guard !ocupada else { return }
        ocupada = true; error = nil
        defer { ocupada = false }
        do {
            try await API.hacer("PUT", "/api/letras/\(cancion.id)", ["texto": letra])
            if Reproductor.compartido.actual?.id == cancion.id {
                Letras.compartido.cargar(actualizada ?? cancion, forzar: true)
            }
            Avisos.compartido.mostrar("Letra guardada")
        } catch { self.error = error.localizedDescription }
    }
}

/// Una portada encontrada en internet (iTunes o Deezer).
struct OpcionPortada: Decodable, Identifiable {
    var id: String { url }
    var fuente: String
    var titulo: String
    var artista: String
    var album: String
    var miniatura: String
    var url: String
    var puntaje: Int
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        fuente = c.texto("fuente") ?? ""; titulo = c.texto("titulo") ?? ""; artista = c.texto("artista") ?? ""
        album = c.texto("album") ?? ""; miniatura = c.texto("miniatura") ?? ""; url = c.texto("url") ?? ""
        puntaje = c.entero("puntaje") ?? 0
    }
}

/// Busca la portada original de la canción en HD (para las bajadas de YouTube, que traen la miniatura del video)
/// y la pone al elegirla. Igual que «Buscar portada original (HD)» de la web.
struct PortadaEnLineaHoja: View {
    let cancion: Cancion
    var alcance: String = "cancion"
    var alCambiar: (Cancion) -> Void
    @Environment(\.dismiss) private var cerrar
    @State private var consulta = ""
    @State private var opciones: [OpcionPortada]?
    @State private var error: String?
    @State private var poniendo: String?

    private let columnas = [GridItem(.adaptive(minimum: 140), spacing: 14)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 8) {
                        TextField("Artista y canción", text: $consulta)
                            .textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled()
                            .submitLabel(.search)
                            .onSubmit { Task { await buscar() } }
                        Button("Buscar") { Task { await buscar() } }.buttonStyle(BotonSecundario())
                    }
                    if let error { Text(error).font(.subheadline).foregroundStyle(Tema.peligro) }
                    if let opciones {
                        if opciones.isEmpty {
                            Text("No se encontraron portadas. Prueba escribiendo el artista y el nombre de otra forma.")
                                .font(.subheadline).foregroundStyle(Tema.secundario)
                        }
                        LazyVGrid(columns: columnas, spacing: 16) {
                            ForEach(opciones) { o in
                                Button { Task { await poner(o) } } label: { tarjeta(o) }
                                    .buttonStyle(.plain)
                                    .disabled(poniendo != nil)
                            }
                        }
                    } else if error == nil {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                    }
                    Text("Portadas de iTunes y Deezer en alta calidad. Toca la que corresponde a esta canción.")
                        .font(.footnote).foregroundStyle(Tema.secundario)
                }
                .padding(16)
            }
            .navigationTitle("Portada original")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { cerrar() } } }
            .task {
                consulta = "\(cancion.artista) \(cancion.nombre)".trimmingCharacters(in: .whitespaces)
                await buscar(conTexto: false)
            }
        }
    }

    private func tarjeta(_ o: OpcionPortada) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack {
                AsyncImage(url: URL(string: o.miniatura)) { img in img.resizable().scaledToFill() } placeholder: { Color.white.opacity(0.06) }
                if poniendo == o.url { Color.black.opacity(0.5); ProgressView().tint(.white) }
            }
            .aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            Text(o.album.isEmpty ? o.titulo : o.album).font(.footnote.weight(.semibold)).lineLimit(2)
            Text("\(o.artista) · \(o.fuente)\(o.puntaje >= 3 ? " · coincide" : "")").font(.caption2).foregroundStyle(Tema.secundario).lineLimit(1)
        }
    }

    private func buscar(conTexto: Bool = true) async {
        error = nil
        opciones = nil
        var ruta = "/api/canciones/\(cancion.id)/portada/buscar"
        if conTexto, !consulta.trimmingCharacters(in: .whitespaces).isEmpty { ruta += "?q=" + API.codificar(consulta) }
        do { opciones = try await API.get(ruta) }
        catch { self.error = error.localizedDescription; opciones = [] }
    }

    private func poner(_ o: OpcionPortada) async {
        poniendo = o.url
        defer { poniendo = nil }
        do {
            var c: Cancion = try await API.enviar("POST", "/api/canciones/\(cancion.id)/portada/en-linea", ["url": o.url, "alcance": alcance])
            NotificationCenter.default.post(name: .bibliotecaCambio, object: nil)
            if alcance == "album" { Avisos.compartido.mostrar("Portada del álbum actualizada"); cerrar(); return }
            if let ruta = c.portadaUrl { c.portadaUrl = ruta + (ruta.contains("?") ? "&" : "?") + "v=\(UUID().uuidString)" }
            alCambiar(c)
            Avisos.compartido.mostrar("Portada actualizada")
            cerrar()
        } catch { self.error = error.localizedDescription }
    }
}
