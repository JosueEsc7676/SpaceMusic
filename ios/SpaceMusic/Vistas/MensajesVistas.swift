import SwiftUI

/// Lista de conversaciones. Solo se puede escribir a amigos (se siguen los dos).
struct MensajesVista: View {
    @State private var conversaciones: [Conversacion]?
    @State private var amigos: [PerfilMini] = []
    @State private var error: String?
    @State private var nuevo = false
    @State private var eventos = Eventos.compartido

    var body: some View {
        List {
            if let conversaciones {
                if conversaciones.isEmpty {
                    EstadoVista(icono: "bubble.left.and.bubble.right", titulo: "Sin mensajes",
                                texto: "Cuando tú y otra persona se sigan, podrán escribirse y compartir canciones.")
                        .listRowSeparator(.hidden)
                }
                ForEach(conversaciones) { c in
                    NavigationLink(value: Destino.chat(c.usuario.username)) { fila(c) }
                }
            } else if let error {
                EstadoVista(icono: "wifi.exclamationmark", titulo: "No se pudo cargar", texto: error) { Task { await cargar() } }
            } else {
                Cargando().listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .navigationTitle("Mensajes")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { nuevo = true } label: { Image(systemName: "square.and.pencil") }
            }
        }
        .refreshable { await cargar() }
        .task { await cargar() }
        .onChange(of: eventos.ultimoMensaje?.mensaje.id) { _, _ in Task { await cargar() } }
        .sheet(isPresented: $nuevo) { nuevoMensaje }
    }

    private func fila(_ c: Conversacion) -> some View {
        HStack(spacing: 12) {
            Avatar(ruta: c.usuario.fotoPerfil, nombre: c.usuario.nombre, tamano: 52)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(c.usuario.nombre).font(.body.weight(c.noLeidos > 0 ? .bold : .medium)).lineLimit(1)
                    Spacer()
                    Text(Formato.hace(c.ultimo?.fecha)).font(.caption).foregroundStyle(Tema.secundario)
                }
                HStack {
                    Text(resumen(c.ultimo)).font(.subheadline)
                        .foregroundStyle(c.noLeidos > 0 ? Tema.texto : Tema.secundario).lineLimit(1)
                    Spacer()
                    if c.noLeidos > 0 {
                        Text("\(c.noLeidos)").font(.caption2.weight(.bold)).foregroundStyle(.black)
                            .padding(.horizontal, 7).padding(.vertical, 2).background(Capsule().fill(Tema.celeste))
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func resumen(_ m: Mensaje?) -> String {
        guard let m else { return "" }
        let prefijo = m.mio ? "Tú: " : ""
        if let c = m.cancion { return prefijo + "🎵 \(c.nombre)" }
        return prefijo + m.texto
    }

    private func cargar() async {
        do {
            let c: [Conversacion] = try await API.get("/api/mensajes", tiempo: 20)
            conversaciones = c
            error = nil
            await eventos.actualizarResumen()
        } catch is CancellationError {
        } catch {
            if conversaciones == nil { self.error = error.localizedDescription }
        }
    }

    private var nuevoMensaje: some View {
        NavigationStack {
            List {
                if amigos.isEmpty {
                    Text("Para escribir a alguien tienen que seguirse los dos.").foregroundStyle(Tema.secundario)
                }
                ForEach(amigos) { a in
                    Button {
                        nuevo = false
                        Navegacion.compartida.ir(.chat(a.username))
                    } label: {
                        HStack(spacing: 12) {
                            Avatar(ruta: a.fotoPerfil, nombre: a.nombre, tamano: 40)
                            Text(a.nombre).foregroundStyle(Tema.texto)
                        }
                    }
                }
            }
            .navigationTitle("Nuevo mensaje")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { nuevo = false } } }
            .task { amigos = (try? await API.get("/api/mensajes/amigos")) ?? [] }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Conversación con una persona, en tiempo real.
struct ChatVista: View {
    let username: String
    @State private var chat: Chat?
    @State private var mensajes: [Mensaje] = []
    @State private var texto = ""
    @State private var error: String?
    @State private var enviando = false
    @State private var cargandoMas = false
    @State private var eventos = Eventos.compartido
    @FocusState private var escribiendo: Bool

    var body: some View {
        VStack(spacing: 0) {
            if chat == nil, let error {
                EstadoVista(icono: "wifi.exclamationmark", titulo: "No se pudo abrir", texto: error) { Task { await cargar() } }
                    .frame(maxHeight: .infinity)
            } else if chat == nil {
                Cargando().frame(maxHeight: .infinity)
            } else {
                lista
                barra
            }
        }
        .navigationTitle(chat?.usuario?.nombre ?? username)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: Destino.perfil(username)) {
                    Avatar(ruta: chat?.usuario?.fotoPerfil, nombre: chat?.usuario?.nombre ?? username, tamano: 30)
                }
            }
        }
        .task { await cargar() }
        .onAppear { eventos.chatAbierto = username }
        .onDisappear { if eventos.chatAbierto == username { eventos.chatAbierto = nil } }
        .onChange(of: eventos.ultimoMensaje?.mensaje.id) { _, _ in
            guard let ev = eventos.ultimoMensaje, ev.con.lowercased() == username.lowercased() else { return }
            if !mensajes.contains(where: { $0.id == ev.mensaje.id }) {
                withAnimation { mensajes.append(ev.mensaje) }
            }
            if !ev.mensaje.mio { Task { try? await API.hacer("POST", "/api/mensajes/\(API.segmento(username))/leidos") } }
        }
        .onChange(of: eventos.ultimoVisto?.cuando) { _, _ in
            guard eventos.ultimoVisto?.por.lowercased() == username.lowercased() else { return }
            Task { await cargar() }
        }
    }

    private var lista: some View {
        ScrollViewReader { lector in
            ScrollView {
                LazyVStack(spacing: 6) {
                    if chat?.hayMas == true {
                        Button(cargandoMas ? "Cargando…" : "Ver mensajes anteriores") { Task { await anteriores() } }
                            .font(.caption).padding(.vertical, 8)
                            .disabled(cargandoMas)
                    }
                    ForEach(mensajes) { m in
                        Burbuja(mensaje: m).id(m.id)
                    }
                    if let ultimo = mensajes.last, ultimo.mio, ultimo.leido {
                        Text("Visto").font(.caption2).foregroundStyle(Tema.secundario)
                            .frame(maxWidth: .infinity, alignment: .trailing).padding(.trailing, 4)
                    }
                    Color.clear.frame(height: 1).id("fin")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: mensajes.last?.id) { _, _ in withAnimation { lector.scrollTo("fin", anchor: .bottom) } }
            .onChange(of: escribiendo) { _, si in if si { withAnimation { lector.scrollTo("fin", anchor: .bottom) } } }
            .onAppear { lector.scrollTo("fin", anchor: .bottom) }
        }
    }

    @ViewBuilder
    private var barra: some View {
        if chat?.puedeEscribir == false {
            Text("Solo pueden escribirse si se siguen los dos.")
                .font(.footnote).foregroundStyle(Tema.secundario).padding()
        } else {
            HStack(alignment: .bottom, spacing: 8) {
                if let c = Reproductor.compartido.actual {
                    Button { Task { await enviar(cancion: c) } } label: {
                        Image(systemName: "music.note").font(.body.weight(.semibold))
                            .frame(width: 38, height: 38).background(Circle().fill(Tema.tarjeta))
                    }
                    .accessibilityLabel("Compartir la canción que suena")
                    .disabled(enviando)
                }
                TextField("Mensaje", text: $texto, axis: .vertical)
                    .lineLimit(1...5)
                    .focused($escribiendo)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(RoundedRectangle(cornerRadius: 20).fill(Tema.tarjeta))
                Button { Task { await enviar(cancion: nil) } } label: {
                    Image(systemName: "arrow.up").font(.body.weight(.bold)).foregroundStyle(.black)
                        .frame(width: 38, height: 38).background(Circle().fill(Tema.celeste))
                }
                .disabled(texto.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || enviando)
                .opacity(texto.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.5 : 1)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
        }
    }

    private func cargar() async {
        do {
            let c: Chat = try await API.get("/api/mensajes/\(API.segmento(username))", tiempo: 20)
            guard !Task.isCancelled else { return }
            chat = c
            // Conserva los mensajes anteriores y los recibidos mientras cargaba.
            var unidos = Dictionary(mensajes.map { ($0.id, $0) }, uniquingKeysWith: { _, nuevo in nuevo })
            for mensaje in c.mensajes { unidos[mensaje.id] = mensaje }
            mensajes = unidos.values.sorted { $0.id < $1.id }
            error = nil
            await eventos.actualizarResumen()
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func anteriores() async {
        guard let primero = mensajes.first, !cargandoMas else { return }
        cargandoMas = true
        defer { cargandoMas = false }
        if let c: Chat = try? await API.get("/api/mensajes/\(API.segmento(username))?antes=\(primero.id)") {
            guard !Task.isCancelled else { return }
            let ids = Set(mensajes.map(\.id))
            mensajes = c.mensajes.filter { !ids.contains($0.id) } + mensajes
            chat?.hayMas = c.hayMas
        }
    }

    private func enviar(cancion: Cancion?) async {
        let t = texto.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !enviando, !t.isEmpty || cancion != nil else { return }
        enviando = true
        defer { enviando = false }
        var cuerpo: [String: Any] = ["texto": cancion == nil ? t : ""]
        if let cancion { cuerpo["cancionId"] = cancion.id }
        do {
            let m: Mensaje = try await API.enviar("POST", "/api/mensajes/\(API.segmento(username))", cuerpo)
            if cancion == nil, texto.trimmingCharacters(in: .whitespacesAndNewlines) == t { texto = "" }
            if !mensajes.contains(where: { $0.id == m.id }) { withAnimation { mensajes.append(m) } }
        } catch {
            Avisos.compartido.error(error)
        }
    }
}

/// Burbuja de un mensaje; si trae canción se puede reproducir.
struct Burbuja: View {
    let mensaje: Mensaje

    var body: some View {
        HStack {
            if mensaje.mio { Spacer(minLength: 50) }
            VStack(alignment: mensaje.mio ? .trailing : .leading, spacing: 6) {
                if let c = mensaje.cancion {
                    Button { Reproductor.compartido.reproducirSuelta(c, contexto: "mensajes") } label: {
                        HStack(spacing: 10) {
                            Portada(ruta: c.portadaUrl, tamano: 48, radio: 6)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(c.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                                Text(c.artista).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                            }
                            Image(systemName: "play.circle.fill").font(.title2)
                        }
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.08)))
                    }
                    .buttonStyle(.plain)
                    .contextMenu { OpcionesCancion(cancion: c) }
                }
                if !(mensaje.cancion != nil && mensaje.texto == "🎵") {
                    Text(mensaje.texto)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(mensaje.mio ? AnyShapeStyle(Tema.celeste.opacity(0.85)) : AnyShapeStyle(Color(white: 0.17)))
                        )
                        .foregroundStyle(mensaje.mio ? Color.black : Tema.texto)
                        .textSelection(.enabled)
                }
                Text(Formato.hora(mensaje.fecha)).font(.caption2).foregroundStyle(Tema.secundario)
            }
            if !mensaje.mio { Spacer(minLength: 50) }
        }
    }
}

/// Campana: nuevos seguidores, amigos, canciones nuevas, playlists y avisos.
struct NotificacionesVista: View {
    @State private var lista: [Notificacion]?
    @State private var eventos = Eventos.compartido

    var body: some View {
        List {
            if let lista {
                if lista.isEmpty {
                    EstadoVista(icono: "bell", titulo: "Sin notificaciones").listRowSeparator(.hidden)
                }
                ForEach(lista) { n in
                    Button { abrir(n) } label: { fila(n) }
                        .buttonStyle(.plain)
                        .listRowBackground(n.leida ? Color.clear : Tema.celeste.opacity(0.07))
                }
                .onDelete { idx in
                    let ids = idx.map { lista[$0].id }
                    self.lista?.remove(atOffsets: idx)
                    Task { for id in ids { try? await API.hacer("DELETE", "/api/notificaciones/\(id)") } }
                }
            } else {
                Cargando().listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .navigationTitle("Notificaciones")
        .toolbar {
            if lista?.isEmpty == false {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Vaciar") {
                        lista = []
                        Task { try? await API.hacer("DELETE", "/api/notificaciones") }
                    }
                }
            }
        }
        .refreshable { await cargar() }
        .task { await cargar() }
    }

    private func cargar() async {
        lista = (try? await API.get("/api/notificaciones")) ?? lista ?? []
        try? await API.hacer("POST", "/api/notificaciones/leidas")
        eventos.notificacionesLeidas()
    }

    private func abrir(_ n: Notificacion) {
        if let r = n.ruta, !r.isEmpty { Navegacion.compartida.abrir(ruta: r) }
        else if let u = n.de?.username { Navegacion.compartida.ir(.perfil(u)) }
    }

    private func fila(_ n: Notificacion) -> some View {
        HStack(alignment: .top, spacing: 12) {
            if let de = n.de {
                Avatar(ruta: de.fotoPerfil, nombre: de.nombre, tamano: 42)
            } else {
                Image(systemName: icono(n.tipo)).font(.title3).foregroundStyle(.black)
                    .frame(width: 42, height: 42).background(Circle().fill(Tema.aurora))
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(n.texto).font(.subheadline).foregroundStyle(Tema.texto)
                Text(Formato.hace(n.fecha)).font(.caption).foregroundStyle(Tema.secundario)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    private func icono(_ tipo: String) -> String {
        switch tipo {
        case "ACTUALIZACION": return "sparkles"
        case "CANCIONES": return "music.note"
        case "PLAYLIST": return "music.note.list"
        case "SEGUIDOR": return "person.badge.plus"
        case "AMIGO": return "person.2.fill"
        case "COLABORADOR": return "checkmark.seal.fill"
        case "AVISO": return "megaphone.fill"
        default: return "bell.fill"
        }
    }
}
