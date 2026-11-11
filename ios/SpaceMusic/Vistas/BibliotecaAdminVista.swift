import SwiftUI

struct BibliotecaAdminVista: View {
    @State private var estado: ValorAdmin?
    @State private var error: String?
    @State private var ocupada = false
    @State private var confirmarLimpieza = false
    @State private var confirmarForzado = false
    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } }
            if let e = estado {
                Section("Biblioteca del servidor") {
                    Text(e["ruta"].texto).font(.footnote).textSelection(.enabled)
                    LabeledContent("Carpeta accesible", value: e["rutaExiste"].texto)
                    LabeledContent("Canciones", value: e["totalCanciones"].texto)
                    LabeledContent("Proceso", value: e["fase"].texto)
                    if e["escaneando"].texto == "Sí" {
                        ProgressView(value: Double(e["procesados"].entero), total: Double(max(1, e["archivosEncontrados"].entero)))
                    }
                    ForEach([("Archivos", "archivosEncontrados"), ("Procesados", "procesados"), ("Nuevas", "nuevas"), ("Reubicadas", "reubicadas"), ("Errores", "errores"), ("Último inicio", "ultimoInicio"), ("Último fin", "ultimoFin"), ("Último error", "ultimoError")], id: \.1) { f in
                        LabeledContent(f.0, value: e[f.1].texto)
                    }
                }
                Section("Mantenimiento") {
                    Button("Buscar canciones nuevas") { Task { await ejecutar("/api/admin/biblioteca/escanear") } }
                    Button("Completar datos de los archivos") { Task { await ejecutar("/api/admin/biblioteca/metadatos") } }
                    Button("Corregir portadas desde los archivos") { Task { await ejecutar("/api/admin/biblioteca/metadatos?portadas=true") } }
                    Button("Quitar canciones cuyos archivos ya no existen", role: .destructive) { confirmarLimpieza = true }
                }
                .disabled(ocupada || e["escaneando"].texto == "Sí")
            } else if error == nil { Cargando() }
            Section { NavigationLink(value: Destino.subir) { Label("Subir canciones", systemImage: "square.and.arrow.up") } }
        }
        .navigationTitle("Biblioteca del servidor")
        .refreshable { await cargar() }
        .task {
            await cargar()
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(5)) } catch { return }
                if estado?["escaneando"].texto == "Sí" { await cargar() }
            }
        }
        .confirmationDialog("Se quitarán también de playlists y favoritos las canciones cuyo archivo ya no existe. Los archivos del servidor no se borran.", isPresented: $confirmarLimpieza, titleVisibility: .visible) {
            Button("Quitar canciones borradas", role: .destructive) { Task { await ejecutar("/api/admin/biblioteca/limpiar") } }
        }
        .confirmationDialog(error ?? "Confirmar limpieza", isPresented: $confirmarForzado, titleVisibility: .visible) {
            Button("Quitar igualmente", role: .destructive) { Task { await ejecutar("/api/admin/biblioteca/limpiar?forzar=true") } }
        }
    }
    private func cargar() async {
        do { estado = try await API.get("/api/admin/biblioteca"); error = nil }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func ejecutar(_ ruta: String) async {
        guard !ocupada else { return }; ocupada = true; defer { ocupada = false }
        do { try await API.hacer("POST", ruta); Avisos.compartido.mostrar("Proceso iniciado"); await cargar() }
        catch {
            self.error = error.localizedDescription
            if ruta == "/api/admin/biblioteca/limpiar", error.localizedDescription.contains("Se borrarían") { confirmarForzado = true }
        }
    }
}

struct ResumenAdminVista: View {
    @State private var datos: ValorAdmin?
    @State private var tendencias: [Cancion] = []
    @State private var artistas: [ValorAdmin] = []
    @State private var generos: [ValorAdmin] = []
    @State private var porMes: [ValorAdmin] = []
    @State private var error: String?
    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } }
            if let d = datos {
                Section("Estadísticas generales") {
                    ForEach([("Canciones", "totalCanciones"), ("Artistas", "totalArtistas"), ("Álbumes", "totalAlbumes"), ("Duración", "duracionFormateada"), ("Reproducciones", "totalReproducciones"), ("Reproducciones en 30 días", "reproducciones30d"), ("Usuarios activos en 30 días", "usuariosActivos30d"), ("Con letra", "conLetra"), ("Con letra sincronizada", "conLetraSincronizada")], id: \.1) { f in
                        LabeledContent(f.0, value: d[f.1].texto)
                    }
                    if case .objeto(let formatos) = d["formatos"] {
                        ForEach(formatos.keys.sorted(), id: \.self) { f in LabeledContent(f.uppercased(), value: formatos[f]?.texto ?? "0") }
                    }
                }
                Section("Tendencias de la semana") {
                    ForEach(tendencias) { c in FilaCancion(cancion: c) { Reproductor.compartido.reproducirSuelta(c, contexto: "tendencias") } }
                }
                Section("Artistas más escuchados") {
                    ForEach(Array(artistas.enumerated()), id: \.offset) { _, a in
                        NavigationLink(a["artista"].texto, value: Destino.artista(a["artista"].texto))
                    }
                }
                if !generos.isEmpty {
                    Section("Géneros en la biblioteca") {
                        ForEach(Array(generos.prefix(15).enumerated()), id: \.offset) { _, g in
                            NavigationLink(value: Destino.genero(g["genero"].texto)) {
                                LabeledContent(g["genero"].texto, value: "\(g["canciones"].texto) · \(g["porcentaje"].texto) %")
                            }
                        }
                    }
                }
                if !porMes.isEmpty {
                    Section("Canciones agregadas por mes") {
                        ForEach(Array(porMes.enumerated()), id: \.offset) { _, m in
                            LabeledContent(m["etiqueta"].texto, value: m["cantidad"].texto)
                        }
                    }
                }
            } else if error == nil { Cargando() }
        }
        .navigationTitle("Resumen del servidor").task { await cargar() }.refreshable { await cargar() }
    }
    private func cargar() async {
        do {
            async let d: ValorAdmin = API.get("/api/estadisticas")
            async let t: [Cancion] = API.get("/api/estadisticas/tendencias?dias=7&limite=10")
            async let a: [ValorAdmin] = API.get("/api/estadisticas/top-artistas?limite=6")
            let r = try await (d, t, a); datos = r.0; tendencias = r.1; artistas = r.2; error = nil
            // Composición de la biblioteca (rutas de estadísticas que la web antigua usaba).
            generos = (try? await API.get("/api/estadisticas/top-generos")) ?? []
            porMes = (try? await API.get("/api/estadisticas/por-mes")) ?? []
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
