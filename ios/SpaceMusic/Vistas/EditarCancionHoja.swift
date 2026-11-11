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
                Section("Portada") {
                    Portada(ruta: (actualizada ?? cancion).portadaUrl, tamano: 120)
                    PhotosPicker(selection: $foto, matching: .images) {
                        Label("Cambiar portada", systemImage: "photo")
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
            var c: Cancion = try await API.subir("/api/canciones/\(cancion.id)/portada", archivos: [
                ArchivoSubida(campo: "file", nombre: "portada.jpg", tipo: "image/jpeg", datos: jpg)
            ])
            if let ruta = c.portadaUrl {
                c.portadaUrl = ruta + (ruta.contains("?") ? "&" : "?") + "v=\(UUID().uuidString)"
            }
            actualizada = c
            Reproductor.compartido.actualizar(c)
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
