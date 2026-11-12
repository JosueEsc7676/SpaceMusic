import Charts
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Estadísticas

struct EstadisticasVista: View {
    @State private var dias = 30
    @State private var datos: MisEstadisticas?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Picker("Periodo", selection: $dias) {
                    Text("7 días").tag(7)
                    Text("30 días").tag(30)
                    Text("1 año").tag(365)
                    Text("Todo").tag(0)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)

                if let d = datos {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        cifra("\(d.totalReproducciones)", "escuchas", "play.circle")
                        cifra(d.tiempoEscuchado, "escuchando", "clock")
                        cifra("\(d.cancionesDistintas)", "canciones distintas", "music.note")
                        cifra("\(d.rachaDias) días", "racha actual", "flame")
                    }
                    .padding(.horizontal)

                    if d.porHora.contains(where: { $0 > 0 }) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("¿A qué hora escuchas?").titulo()
                            Chart(Array(d.porHora.enumerated()), id: \.offset) { h, n in
                                BarMark(x: .value("Hora", h), y: .value("Escuchas", n))
                                    .foregroundStyle(Tema.aurora)
                                    .cornerRadius(3)
                            }
                            .chartXAxis {
                                AxisMarks(values: [0, 6, 12, 18, 23]) { v in
                                    AxisValueLabel { if let h = v.as(Int.self) { Text("\(h)h") } }
                                }
                            }
                            .frame(height: 160)
                        }
                        .padding(.horizontal)
                    }

                    if !d.topCanciones.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Tus canciones").titulo().padding(.horizontal)
                            LazyVStack(spacing: 10) {
                                ForEach(Array(d.topCanciones.enumerated()), id: \.offset) { i, t in
                                    if let c = t.cancion {
                                        FilaCancion(cancion: c, numero: i + 1, detalle: "\(c.artista) · \(t.veces) veces") {
                                            Reproductor.compartido.reproducir(d.topCanciones.compactMap(\.cancion), desde: i, contexto: "estadisticas")
                                        }
                                    }
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                    if !d.topArtistas.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Tus artistas").titulo()
                            ForEach(Array(d.topArtistas.enumerated()), id: \.offset) { i, a in
                                NavigationLink(value: Destino.artista(a.artista)) {
                                    HStack(spacing: 12) {
                                        Text("\(i + 1)").font(.subheadline.monospacedDigit()).foregroundStyle(Tema.secundario).frame(width: 22)
                                        ImagenRemota(ruta: a.portadaUrl, icono: "music.mic", redonda: true).frame(width: 44, height: 44)
                                        Text(a.artista).font(.body.weight(.medium))
                                        Spacer()
                                        Text("\(a.veces)").font(.subheadline).foregroundStyle(Tema.secundario)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal)
                    }
                    if !d.topGeneros.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Tus géneros").titulo()
                            Chart(Array(d.topGeneros.prefix(8).enumerated()), id: \.offset) { _, g in
                                BarMark(x: .value("%", g.porcentaje), y: .value("Género", g.genero))
                                    .foregroundStyle(Tema.celeste.gradient)
                                    .cornerRadius(3)
                            }
                            .frame(height: CGFloat(min(d.topGeneros.count, 8)) * 34)
                        }
                        .padding(.horizontal)
                    }
                } else if let error {
                    EstadoVista(icono: "chart.bar", titulo: "No se pudo cargar", texto: error) { Task { await cargar() } }
                } else {
                    Cargando()
                }
            }
            .padding(.vertical)
        }
        .navigationTitle("Tus estadísticas")
        .task(id: dias) { await cargar() }
        .refreshable { await cargar() }
    }

    private func cifra(_ v: String, _ t: String, _ icono: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: icono).foregroundStyle(Tema.celeste)
            Text(v).font(.title2.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
            Text(t).font(.caption).foregroundStyle(Tema.secundario)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(Tema.tarjeta))
    }

    private func cargar() async {
        do {
            datos = try await API.get("/api/usuario/estadisticas?dias=\(dias)")
            error = nil
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// MARK: - Novedades

struct NovedadesVista: View {
    var body: some View {
        Cargador(cargar: { () async throws -> Novedad in try await API.get("/app/novedades.json") }) { n, recargar in
            List {
                bloque(n, actual: true)
                ForEach(n.anteriores) { bloque($0, actual: false) }
            }
            .refreshable { await recargar() }
        }
        .navigationTitle("Novedades")
    }

    private func bloque(_ n: Novedad, actual: Bool) -> some View {
        Section {
            ForEach(n.puntos, id: \.self) { p in
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "sparkle").foregroundStyle(actual ? Tema.celeste : Tema.secundario).padding(.top, 3)
                    Text(p).font(.subheadline)
                }
            }
        } header: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Versión \(n.version)" + (actual ? " · Actual" : "")).font(.caption.weight(.bold))
                Text(n.titulo).font(.headline).foregroundStyle(Tema.texto).textCase(nil)
            }
        }
    }
}

// MARK: - Cuenta

struct CuentaVista: View {
    @State private var sesion = Sesion.compartida
    @State private var privacidad: Perfil.Privacidad?
    @State private var biografia = ""
    @State private var biografiaGuardada = ""
    @State private var cargandoPerfil = true
    @State private var claveRepetida = ""
    @State private var reproductor = Reproductor.compartido
    @State private var canciones = true
    @State private var generos = true
    @State private var vibras = true
    @State private var actividad = true
    @State private var claveActual = ""
    @State private var claveNueva = ""
    @State private var correoNuevo = ""
    @State private var claveCorreo = ""
    @State private var motivo = ""
    @State private var mensaje: String?
    @State private var trabajando = false

    var body: some View {
        Form {
            if let u = sesion.usuario {
                Section("Tu cuenta") {
                    LabeledContent("Usuario", value: "@\(u.username)")
                    LabeledContent("Correo", value: u.email ?? "")
                    if let p = u.emailPendiente, !p.isEmpty {
                        Text("Falta confirmar el cambio a \(p). Revisa tu correo.").font(.caption).foregroundStyle(Tema.rosa)
                    }
                    LabeledContent("Tipo de cuenta", value: nombreRol(u.rol))
                }
            }

            Section("Tu música") {
                NavigationLink(value: Destino.resumen) { Label("Tu resumen (tu año y tus meses)", systemImage: "gift") }
                NavigationLink(value: Destino.dedicatorias) { Label("Dedicatorias", systemImage: "heart.text.square") }
                NavigationLink(value: Destino.coincidimos) { Label("Coincidimos", systemImage: "person.2") }
                NavigationLink(value: Destino.descubrir) { Label("Descubrir", systemImage: "sparkles") }
                NavigationLink(value: Destino.rutas) { Label("Rutas musicales", systemImage: "safari") }
                NavigationLink(value: Destino.preparar) { Label("Preparar sin internet", systemImage: "arrow.down.circle") }
                Button { Hojas.compartidas.gustosInicial = false; Hojas.compartidas.gustos = true } label: {
                    Label("Tus gustos", systemImage: "heart.circle")
                }
            }

            Section("Reproducción") {
                Toggle("Radio automática", isOn: $reproductor.radioAutomatica)
                AjustesReproduccion()
            }
            Section("App de iPhone") {
                Button { Navegacion.compartida.verActualizacion = true } label: {
                    LabeledContent("Versión", value: Actualizaciones.compartida.versionActual)
                }
                .foregroundStyle(Tema.texto)
            }
            Section {
                TextField("Sobre ti (máximo 160 caracteres)", text: $biografia, axis: .vertical)
                Button("Guardar biografía") { guardarPrivacidad() }.disabled(cargandoPerfil || biografia.count > 160)
                Toggle("Mostrar mis canciones más escuchadas", isOn: $canciones)
                Toggle("Mostrar mis géneros", isOn: $generos)
                Toggle("Mostrar mis vibras", isOn: $vibras)
                Toggle("Mostrar lo que escucho ahora", isOn: $actividad)
            } header: {
                Text("Privacidad del perfil")
            } footer: {
                Text("Lo que ocultes no lo verán las demás personas.")
            }
            .tint(Tema.celeste)
            .onChange(of: [canciones, generos, vibras, actividad]) { _, _ in guardarPrivacidad() }

            .disabled(cargandoPerfil || trabajando)
            Section("Cambiar contraseña") {
                SecureField("Contraseña actual", text: $claveActual).textContentType(.password)
                SecureField("Nueva contraseña (mínimo 8)", text: $claveNueva).textContentType(.newPassword)
                SecureField("Repite la nueva contraseña", text: $claveRepetida).textContentType(.newPassword)
                Button("Cambiar contraseña") { Task { await cambiarClave() } }
                    .disabled(claveActual.isEmpty || claveNueva.count < 8 || claveNueva != claveRepetida || trabajando)
            }

            Section {
                TextField("Correo nuevo", text: $correoNuevo)
                    .keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Tu contraseña", text: $claveCorreo).textContentType(.password)
                Button("Cambiar correo") { Task { await cambiarCorreo() } }
                    .disabled(correoNuevo.isEmpty || claveCorreo.isEmpty || trabajando)
            } header: { Text("Cambiar correo") }

            if sesion.rol == "USER" {
                Section {
                    if let s = sesion.usuario?.solicitudColaborador, s == "PENDIENTE" {
                        Text("Tu solicitud está pendiente de revisión.").foregroundStyle(Tema.secundario)
                    } else {
                        if let r = sesion.usuario?.respuestaAdmin, !r.isEmpty {
                            Text("Respuesta: \(r)").font(.caption).foregroundStyle(Tema.secundario)
                        }
                        TextField("¿Por qué quieres ser colaborador?", text: $motivo, axis: .vertical).lineLimit(2...4)
                        Button("Enviar solicitud") { Task { await solicitar() } }.disabled(motivo.count < 5 || trabajando)
                    }
                } header: { Text("Ser colaborador") } footer: {
                    Text("Los colaboradores pueden subir canciones y editar sus datos.")
                }
            }

            if let mensaje {
                Section { Text(mensaje).foregroundStyle(Tema.celeste) }
            }
        }
        .navigationTitle("Cuenta y privacidad")
        .task { await cargar() }
    }

    private func nombreRol(_ r: String) -> String {
        switch r {
        case "ADMIN": return "Administrador"
        case "COLABORADOR": return "Colaborador"
        default: return "Usuario"
        }
    }

    private func cargar() async {
        cargandoPerfil = true
        defer { cargandoPerfil = false }
        if let me: Usuario = try? await API.get("/api/auth/me") { sesion.ponerUsuario(me) }
        guard let u = sesion.usuario, let p: Perfil = try? await API.get("/api/perfiles/\(API.segmento(u.username))"),
              let pr = p.privacidad else { return }
        biografia = p.biografia ?? ""
        biografiaGuardada = biografia
        privacidad = pr
        canciones = pr.canciones
        generos = pr.generos
        vibras = pr.vibras
        actividad = pr.actividad
    }

    private func guardarPrivacidad() {
        guard !cargandoPerfil, !trabajando, privacidad != nil, biografia.count <= 160 else { return }
        guard let previa = privacidad else { return }
        guard previa.canciones != canciones || previa.generos != generos || previa.vibras != vibras || previa.actividad != actividad || biografia != biografiaGuardada else { return }
        trabajando = true
        let cuerpo: [String: Any] = ["canciones": canciones, "generos": generos, "vibras": vibras, "actividad": actividad, "biografia": biografia]
        Task {
            defer { trabajando = false }
            do {
                try await API.hacer("PUT", "/api/perfiles/yo/privacidad", cuerpo)
                biografiaGuardada = biografia
                privacidad?.canciones = canciones
                privacidad?.generos = generos
                privacidad?.vibras = vibras
                privacidad?.actividad = actividad
                Avisos.compartido.mostrar("Privacidad guardada")
            } catch {
                Avisos.compartido.error(error)
            }
        }
    }

    private func cambiarClave() async {
        trabajando = true
        defer { trabajando = false }
        do {
            let r: RespuestaOK = try await API.enviar("POST", "/api/usuario/cambiar-password", ["actual": claveActual, "nueva": claveNueva])
            // El servidor cierra las otras sesiones y entrega un token nuevo para este iPhone
            if let t = r.token { sesion.guardar(token: t, usuario: nil) }
            claveActual = ""
            claveNueva = ""
            claveRepetida = ""
            mensaje = r.message ?? "Contraseña actualizada"
        } catch {
            mensaje = error.localizedDescription
        }
    }

    private func cambiarCorreo() async {
        trabajando = true
        defer { trabajando = false }
        do {
            let r: RespuestaOK = try await API.enviar("POST", "/api/usuario/cambiar-email", ["nuevoEmail": correoNuevo, "password": claveCorreo])
            if let u = r.usuario { sesion.ponerUsuario(u) }
            claveCorreo = ""
            mensaje = r.message
            await cargar()
        } catch {
            mensaje = error.localizedDescription
        }
    }

    private func solicitar() async {
        trabajando = true
        defer { trabajando = false }
        do {
            let r: RespuestaOK = try await API.enviar("POST", "/api/usuario/solicitar-colaborador", ["motivo": motivo])
            mensaje = r.message ?? "Solicitud enviada"
            motivo = ""
            await cargar()
        } catch {
            mensaje = error.localizedDescription
        }
    }
}

// MARK: - Subir canciones

struct SubirVista: View {
    struct Archivo: Identifiable {
        let id = UUID()
        let url: URL
        var estado: Estado = .pendiente
        enum Estado: Equatable { case pendiente, subiendo, listo, error(String) }
    }

    @State private var archivos: [Archivo] = []
    @State private var eligiendo = false
    @State private var subiendo = false

    var body: some View {
        List {
            Section {
                Button { eligiendo = true } label: { Label("Elegir canciones", systemImage: "folder.badge.plus") }
                    .disabled(subiendo)
            } footer: {
                Text("MP3, M4A, FLAC, OGG o WAV desde la app Archivos. Se suben de dos en dos y el servidor lee los datos y la portada.")
            }
            if !archivos.isEmpty {
                Section {
                    ForEach(archivos) { a in
                        HStack {
                            Image(systemName: "music.note")
                            Text(a.url.lastPathComponent).lineLimit(1)
                            Spacer()
                            switch a.estado {
                            case .pendiente: Image(systemName: "clock").foregroundStyle(Tema.secundario)
                            case .subiendo: ProgressView()
                            case .listo: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                            case .error(let m): Image(systemName: "exclamationmark.circle.fill").foregroundStyle(Tema.peligro).help(m)
                            }
                        }
                    }
                } header: {
                    Text(resumen)
                }
                Section {
                    Button(subiendo ? "Subiendo…" : "Subir \(Formato.canciones(archivos.filter { $0.estado == .pendiente }.count))") {
                        Task { await subirTodo() }
                    }
                    .disabled(subiendo || !archivos.contains { $0.estado == .pendiente })
                    Button("Reintentar archivos con error") {
                        for i in archivos.indices { if case .error = archivos[i].estado { archivos[i].estado = .pendiente } }
                        Task { await subirTodo() }
                    }.disabled(subiendo)
                    Button("Limpiar lista", role: .destructive) { archivos = [] }.disabled(subiendo)
                }
            }
        }
        .navigationTitle("Subir canciones")
        .fileImporter(isPresented: $eligiendo, allowedContentTypes: [.audio, .mp3, .mpeg4Audio, UTType(filenameExtension: "flac") ?? .audio],
                      allowsMultipleSelection: true) { r in
            if case .success(let urls) = r {
                archivos.append(contentsOf: urls.prefix(50).map { Archivo(url: $0) })
            }
        }
    }

    private var resumen: String {
        let listos = archivos.filter { $0.estado == .listo }.count
        let errores = archivos.filter { if case .error = $0.estado { return true } else { return false } }.count
        return "\(listos) subidas · \(errores) con error · \(archivos.count) en total"
    }

    private func subirTodo() async {
        guard !subiendo else { return }
        subiendo = true
        defer { subiendo = false }
        let pendientes = archivos.indices.filter { archivos[$0].estado == .pendiente }
        // Dos a la vez, como en la web
        for grupo in stride(from: 0, to: pendientes.count, by: 2).map({ Array(pendientes[$0..<min($0 + 2, pendientes.count)]) }) {
            await withTaskGroup(of: Void.self) { g in
                for i in grupo {
                    archivos[i].estado = .subiendo
                    let url = archivos[i].url
                    g.addTask { await subir(i, url) }
                }
            }
        }
        let listos = archivos.filter { $0.estado == .listo }.count
        Avisos.compartido.mostrar("\(Formato.canciones(listos)) subidas")
    }

    private func subir(_ i: Int, _ url: URL) async {
        let acceso = url.startAccessingSecurityScopedResource()
        defer { if acceso { url.stopAccessingSecurityScopedResource() } }
        guard let datos = try? Data(contentsOf: url) else {
            await MainActor.run { archivos[i].estado = .error("No se pudo leer el archivo") }
            return
        }
        let tipo = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "audio/mpeg"
        do {
            let _: Vacio = try await API.subir("/api/canciones/subir",
                                              archivos: [ArchivoSubida(campo: "files", nombre: url.lastPathComponent, tipo: tipo, datos: datos)])
            await MainActor.run { archivos[i].estado = .listo }
        } catch {
            let m = error.localizedDescription
            await MainActor.run { archivos[i].estado = .error(m) }
        }
    }
}
