import SwiftUI

struct JuntosVista: View {
    var id: Int? = nil
    @State private var salas: [SalaJuntos] = []
    @State private var detalle: SalaJuntos?
    @State private var motor = EscuchaJuntos.compartida
    @State private var error: String?
    @State private var ocupada = false
    @State private var personas: [PersonaBreve] = []
    @State private var invitando = false
    @State private var agregando = false
    @State private var filtro = ""
    @State private var salaElegida: Int?
    @Environment(\.dismiss) private var cerrar

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(Tema.peligro) }
            if let s = motor.sala, s.id == id { contenido(s) }
            else if let s = detalle { contenido(s) }
            else if id == nil {
                Section {
                    CabeceraExperiencia(icono: "headphones", titulo: "SyncPlay", texto: "Abre una sala y comparte la canción, el momento y la cola con tus amigos.")
                    Button { ejecutar { let s = try await motor.crear(); salaElegida = s.id } } label: { Label("Iniciar SyncPlay", systemImage: "person.2.wave.2") }
                }
                ForEach(salas) { s in
                    NavigationLink { JuntosVista(id: s.id) } label: {
                        Label(s.soyAnfitrion ? "Tu sala" : "Sala de \(s.anfitrion?.nombre ?? "tu amigo")", systemImage: s.dentro ? "headphones" : "envelope")
                    }
                }
                if salas.isEmpty { Text("Todavía no hay salas ni invitaciones.").foregroundStyle(Tema.secundario) }
            } else if error == nil { ProgressView() }
        }
        .navigationTitle("SyncPlay")
        .navigationDestination(item: $salaElegida) { JuntosVista(id: $0) }
        .navigationBarTitleDisplayMode(.inline)
        .disabled(ocupada)
        .task { await cargar() }
        .refreshable { await cargar() }
        .sheet(isPresented: $invitando) { elegirPersona }
        .sheet(isPresented: $agregando) {
            ElegirCancionHoja(titulo: "Sumar a la sala") { c in
                guard let id else { return }
                ejecutar { try await API.hacer("POST", "/api/juntos/\(id)/cola", ["cancionId": c.id]); await cargar() }
            }
        }
    }

    @ViewBuilder private func contenido(_ s: SalaJuntos) -> some View {
        Section {
            Text(s.soyAnfitrion ? "Tu sala" : "Sala de \(s.anfitrion?.nombre ?? "tu amigo")").font(.title2.bold())
            if let c = s.cancion {
                HStack(spacing: 12) {
                    Portada(ruta: c.portadaUrl, tamano: 68, radio: 12)
                    VStack(alignment: .leading) { Text(c.nombre).font(.headline); Text(c.artista).foregroundStyle(Tema.secundario); Text(s.sonando ? "Sonando ahora" : "En pausa").font(.caption) }
                }
            } else { Text("Esperando la música del anfitrión…").foregroundStyle(Tema.secundario) }
            if !s.dentro {
                Button("Unirme y escuchar") { ejecutar { let nueva: SalaJuntos = try await API.enviar("POST", "/api/juntos/\(s.id)/unirse"); detalle = nueva; motor.activar(nueva) } }
            } else {
                Text(s.soyAnfitrion ? "Lo que pongas en tu reproductor sonará para todos." : "La sala sigue al anfitrión, también cuando cambias de pantalla.").font(.caption)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack { ForEach(s.reacciones, id: \.self) { e in Button(e) { ejecutar { try await API.hacer("POST", "/api/juntos/\(s.id)/reaccion", ["emoji": e]) } }.font(.title) } }
                }
                Button("Sumar canción") { agregando = true }
            }
            if s.dentro, let enlace = InvitacionSyncPlay.enlace(sala: s.id, servidor: Sesion.compartida.servidor) {
                ShareLink(item: enlace) { Label("Compartir enlace de SyncPlay", systemImage: "square.and.arrow.up") }
            }
            if s.soyAnfitrion {
                Button("Enviar invitación a un amigo") { ejecutar { personas = try await API.get("/api/mensajes/amigos"); invitando = true } }
            }
            Button(s.soyAnfitrion ? "Cerrar la sala" : "Salir de la sala", role: .destructive) {
                ejecutar {
                    if motor.sala?.id == s.id { try await motor.salir() }
                    else { try await API.hacer("POST", "/api/juntos/\(s.id)/salir") }
                    cerrar()
                }
            }
        }
        Section("En la sala") {
            ForEach(s.personas, id: \.username) { p in Label(p.nombre, systemImage: p.username == s.anfitrion?.username ? "crown" : "person") }
            ForEach(s.invitados, id: \.username) { p in Text("\(p.nombre) · invitado").foregroundStyle(Tema.secundario) }
        }
        Section("A continuación") {
            ForEach(Array(s.cola.enumerated()), id: \.offset) { _, entrada in
                if let c = entrada.cancion {
                    HStack {
                        Portada(ruta: c.portadaUrl, tamano: 44, radio: 6)
                        VStack(alignment: .leading) { Text(c.nombre).lineLimit(1); Text("\(c.artista) · \(entrada.de?.nombre ?? "")").font(.caption).foregroundStyle(Tema.secundario) }
                    }
                    .swipeActions {
                        if s.soyAnfitrion || entrada.de?.username == Sesion.compartida.usuario?.username {
                            Button("Quitar", role: .destructive) { ejecutar { try await API.hacer("DELETE", "/api/juntos/\(s.id)/cola/\(c.id)"); await cargar() } }
                        }
                    }
                }
            }
        }
    }

    private var elegirPersona: some View {
        NavigationStack {
            List(personas.filter { filtro.isEmpty || ($0.nombre + $0.username).localizedCaseInsensitiveContains(filtro) }, id: \.username) { p in
                Button(p.nombre + " · @" + p.username) {
                    guard id != nil else { return }
                    ejecutar {
                        _ = try await motor.invitarPorMensaje(p.username)
                        invitando = false; await cargar()
                    }
                }
            }
            .searchable(text: $filtro, prompt: "Nombre o usuario")
            .navigationTitle("Invitar")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { invitando = false } } }
        }
    }
    private func cargar() async {
        do {
            if let id {
                let s: SalaJuntos = try await API.get("/api/juntos/\(id)")
                detalle = s
                if s.dentro { motor.activar(s) }
            } else { salas = try await API.get("/api/juntos") }
            error = nil
        } catch { self.error = error.localizedDescription }
    }
    private func ejecutar(_ tarea: @escaping @MainActor () async throws -> Void) {
        guard !ocupada else { return }
        ocupada = true
        Task { defer { ocupada = false }; do { try await tarea(); error = nil } catch { self.error = error.localizedDescription } }
    }
}
