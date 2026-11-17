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
    @State private var posicion: Double?
    @State private var ahora = Date()
    @Environment(\.dismiss) private var cerrar

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(Tema.peligro) }
            if let s = motor.sala, s.id == id { contenido(s) }
            else if let s = detalle { contenido(s) }
            else if id == nil {
                Section {
                    CabeceraExperiencia(icono: "headphones", titulo: "SyncPlay", texto: "Escuchen la misma canción en el mismo segundo. Todos pueden reproducir, pausar, cambiar de canción y mover la posición.")
                    Button { ejecutar { let s = try await motor.crear(); salaElegida = s.id } } label: { Label("Iniciar SyncPlay", systemImage: "person.2.wave.2") }
                }
                ForEach(salas) { s in
                    NavigationLink { JuntosVista(id: s.id) } label: {
                        Label(s.soyAnfitrion ? "Tu SyncPlay" : "SyncPlay de \(s.anfitrion?.nombre ?? "tu amigo")", systemImage: s.dentro ? "headphones" : "envelope")
                    }
                }
                if salas.isEmpty { Text("Todavía no hay SyncPlay ni invitaciones.").foregroundStyle(Tema.secundario) }
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
            ElegirCancionHoja(titulo: "Sumar a la cola de SyncPlay") { c in
                guard let id else { return }
                ejecutar { try await API.hacer("POST", "/api/juntos/\(id)/cola", ["cancionId": c.id]); await cargar() }
            }
        }
    }

    @ViewBuilder private func contenido(_ s: SalaJuntos) -> some View {
        Section {
            Text(s.soyAnfitrion ? "Tu SyncPlay" : "SyncPlay de \(s.anfitrion?.nombre ?? "tu amigo")").font(.title2.bold())
            if let c = s.cancion {
                HStack(spacing: 12) {
                    Portada(ruta: c.portadaUrl, tamano: 68, radio: 12)
                    VStack(alignment: .leading) { Text(c.nombre).font(.headline); Text(c.artista).foregroundStyle(Tema.secundario); Text(s.sonando ? "Sonando ahora" : "En pausa").font(.caption) }
                }
            } else { Text(s.dentro ? "Elige una canción y sonará para todos." : "Únete para escuchar y controlar la música.").foregroundStyle(Tema.secundario) }
            if s.dentro, let c = s.cancion { controles(s, c) }
            if !s.dentro {
                Button("Unirme a SyncPlay") { ejecutar { let nueva: SalaJuntos = try await API.enviar("POST", "/api/juntos/\(s.id)/unirse"); detalle = nueva; motor.activar(nueva) } }
            } else {
                Text("Todos controlan la música: tu reproductor, la pantalla bloqueada y estos botones mandan a todos.").font(.caption)
                Button("Sumar canción a la cola") { agregando = true }
            }
            if s.dentro, let enlace = InvitacionSyncPlay.enlace(sala: s.id, servidor: Sesion.compartida.servidor) {
                ShareLink(item: enlace) { Label("Compartir enlace de SyncPlay", systemImage: "square.and.arrow.up") }
            }
            if s.soyAnfitrion {
                Button("Enviar invitación a un amigo") { ejecutar { personas = try await API.get("/api/mensajes/amigos"); invitando = true } }
            }
            Button(s.soyAnfitrion ? "Terminar SyncPlay" : "Salir de SyncPlay", role: .destructive) {
                ejecutar {
                    if motor.sala?.id == s.id { try await motor.salir() }
                    else { try await API.hacer("POST", "/api/juntos/\(s.id)/salir") }
                    cerrar()
                }
            }
        }
        Section("Escuchando") {
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
                        if s.dentro {
                            Button("Quitar", role: .destructive) { ejecutar { try await API.hacer("DELETE", "/api/juntos/\(s.id)/cola/\(c.id)"); await cargar() } }
                            Button("Poner") { motor.control("poner", ["cancionId": c.id]) }.tint(Tema.celeste)
                        }
                    }
                }
            }
        }
    }

    /// Controles compartidos: cualquiera del SyncPlay los usa y suena igual para todos.
    @ViewBuilder private func controles(_ s: SalaJuntos, _ c: Cancion) -> some View {
        let dur = Double(max(1, c.duracionSegundos))
        VStack(spacing: 8) {
            Slider(value: Binding(get: { posicion ?? motor.objetivo(s) }, set: { posicion = $0 }), in: 0...dur, onEditingChanged: { editando in
                if !editando, let p = posicion { motor.control("seek", ["segundo": p]); posicion = nil }
            })
            .accessibilityLabel("Posición de SyncPlay")
            HStack {
                Text(formato(posicion ?? motor.objetivo(s))).font(.caption.monospacedDigit())
                Spacer()
                Text(formato(dur)).font(.caption.monospacedDigit())
            }
            .foregroundStyle(Tema.secundario)
            .id(ahora)
            HStack(spacing: 36) {
                Button { motor.control("prev") } label: { Image(systemName: "backward.fill").font(.title2) }
                    .accessibilityLabel("Anterior")
                Button { motor.control(s.sonando ? "pause" : "play") } label: {
                    Image(systemName: s.sonando ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 54))
                }
                .accessibilityLabel(s.sonando ? "Pausar para todos" : "Reproducir para todos")
                Button {
                    var datos: [String: Any] = [:]
                    datos["desde"] = c.id
                    motor.control("next", datos)
                } label: { Image(systemName: "forward.fill").font(.title2) }
                    .accessibilityLabel("Siguiente")
            }
            .buttonStyle(.borderless)
            .frame(maxWidth: .infinity)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                ahora = Date()
            }
        }
    }

    private func formato(_ s: Double) -> String {
        guard s.isFinite, s > 0 else { return "0:00" }
        let t = Int(s)
        return "\(t / 60):" + String(format: "%02d", t % 60)
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
