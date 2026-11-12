import SwiftUI

/// Usuarios del servidor organizados para el teléfono (equivale a la pestaña Usuarios de la web):
/// búsqueda, filtros por rol y estado, filas con etiquetas claras y las acciones en un menú por persona.
struct UsuariosAdminVista: View {
    enum Filtro: String, CaseIterable, Identifiable {
        case todos = "Todos", admins = "Admins", colaboradores = "Colaboradores", usuarios = "Usuarios", desactivados = "Desactivados"
        var id: String { rawValue }
    }

    @State private var usuarios: [Usuario] = []
    @State private var cargando = true
    @State private var error: String?
    @State private var busqueda = ""
    @State private var filtro: Filtro = .todos
    @State private var trabajando = false
    @State private var desactivar: Usuario?

    private var yo: Int? { Sesion.compartida.usuario?.id }

    private var visibles: [Usuario] {
        let q = busqueda.trimmingCharacters(in: .whitespaces)
        return usuarios.filter { u in
            let coincide = q.isEmpty || (u.nombre + " " + u.username + " " + (u.email ?? ""))
                .range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil
            switch filtro {
            case .todos: return coincide
            case .admins: return coincide && u.rol == "ADMIN"
            case .colaboradores: return coincide && u.rol == "COLABORADOR"
            case .usuarios: return coincide && u.rol == "USER"
            case .desactivados: return coincide && u.activo == false
            }
        }
    }

    var body: some View {
        List {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Filtro.allCases) { f in
                            Button { filtro = f } label: {
                                Text("\(f.rawValue) \(cuenta(f))")
                                    .font(.subheadline.weight(.semibold))
                                    .padding(.horizontal, 12).padding(.vertical, 7)
                                    .foregroundStyle(filtro == f ? Color.black : Tema.texto)
                                    .background(Capsule().fill(filtro == f ? Color.white : Tema.tarjeta))
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(filtro == f ? .isSelected : [])
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }

            if let error {
                Section { Text(error).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } }
            }

            Section {
                ForEach(visibles) { u in
                    NavigationLink(value: Destino.usuarioAdmin(u.id)) { fila(u) }
                        .contextMenu { acciones(u) }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            if u.id != yo {
                                Button(u.activo == false ? "Activar" : "Desactivar") {
                                    if u.activo == false { Task { await estado(u, activo: true) } } else { desactivar = u }
                                }
                                .tint(u.activo == false ? .green : Tema.peligro)
                            }
                        }
                }
            } header: {
                Text(visibles.count == 1 ? "1 usuario" : "\(visibles.count) usuarios")
            } footer: {
                Text("Mantén pulsado un usuario para cambiar su rol, verificar el correo, enviarle un enlace de contraseña o cerrar sus sesiones.")
            }
        }
        .overlay { if cargando && usuarios.isEmpty { ProgressView() } }
        .overlay {
            if !cargando, error == nil, visibles.isEmpty {
                EstadoVista(icono: "person.crop.circle.badge.questionmark", titulo: "Nadie coincide")
            }
        }
        .searchable(text: $busqueda, prompt: "Buscar por nombre, usuario o correo")
        .navigationTitle("Usuarios")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await cargar() }
        .task { await cargar() }
        .disabled(trabajando)
        .confirmationDialog("¿Desactivar la cuenta?", isPresented: Binding(get: { desactivar != nil }, set: { if !$0 { desactivar = nil } }),
                            titleVisibility: .visible, presenting: desactivar) { u in
            Button("Desactivar a \(u.nombre)", role: .destructive) { Task { await estado(u, activo: false) } }
        } message: { u in Text("\(u.nombre) no podrá iniciar sesión.") }
    }

    private func cuenta(_ f: Filtro) -> Int {
        switch f {
        case .todos: return usuarios.count
        case .admins: return usuarios.filter { $0.rol == "ADMIN" }.count
        case .colaboradores: return usuarios.filter { $0.rol == "COLABORADOR" }.count
        case .usuarios: return usuarios.filter { $0.rol == "USER" }.count
        case .desactivados: return usuarios.filter { $0.activo == false }.count
        }
    }

    private func fila(_ u: Usuario) -> some View {
        HStack(spacing: 12) {
            Avatar(ruta: u.fotoPerfil, nombre: u.nombre, tamano: 44)
                .opacity(u.activo == false ? 0.45 : 1)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(u.nombre).font(.body.weight(.semibold)).lineLimit(1)
                    if u.id == yo { Text("Tú").font(.caption2.weight(.bold)).foregroundStyle(Tema.secundario) }
                }
                Text("@\(u.username)").font(.subheadline).foregroundStyle(Tema.secundario).lineLimit(1)
                HStack(spacing: 6) {
                    etiqueta(nombreRol(u.rol), color: colorRol(u.rol))
                    if u.activo == false { etiqueta("Desactivada", color: Tema.peligro) }
                    if let e = u.estadoCuenta, e != "APROBADA" { etiqueta(e.capitalized, color: .orange) }
                    if u.emailVerificado == false { etiqueta("Correo sin verificar", color: .orange) }
                }
                Text(u.ultimoLogin.map { "Último acceso " + Formato.hace($0) } ?? "Nunca ha entrado")
                    .font(.caption).foregroundStyle(Tema.secundario)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private func etiqueta(_ t: String, color: Color) -> some View {
        Text(t).font(.caption2.weight(.bold)).foregroundStyle(color)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(Capsule().fill(color.opacity(0.15)))
            .lineLimit(1).fixedSize()
    }

    @ViewBuilder
    private func acciones(_ u: Usuario) -> some View {
        if u.id != yo {
            Menu("Cambiar rol") {
                ForEach([("USER", "Usuario"), ("COLABORADOR", "Colaborador"), ("ADMIN", "Administrador")], id: \.0) { r in
                    Button { Task { await enviar("PUT", "/api/admin/usuarios/\(u.id)/rol", ["rol": r.0], texto: "\(u.nombre) ahora es \(r.1.lowercased())") } } label: {
                        if u.rol == r.0 { Label(r.1, systemImage: "checkmark") } else { Text(r.1) }
                    }
                }
            }
            if u.activo == false {
                Button("Activar cuenta") { Task { await estado(u, activo: true) } }
            } else {
                Button("Desactivar cuenta", role: .destructive) { desactivar = u }
            }
        }
        if u.emailVerificado == false {
            Button("Marcar correo como verificado") { Task { await enviar("POST", "/api/admin/usuarios/\(u.id)/verificar", nil, texto: "Correo verificado") } }
        }
        Button("Enviar correo para cambiar contraseña") {
            Task { await enviar("POST", "/api/admin/usuarios/\(u.id)/recuperar", nil, texto: "Correo enviado a \(u.nombre)") }
        }
        Button("Cerrar sus sesiones", role: .destructive) {
            Task { await enviar("POST", "/api/admin/usuarios/\(u.id)/cerrar-sesiones", nil, texto: "Sesiones cerradas") }
        }
        Button { Navegacion.compartida.ir(.perfil(u.username)) } label: { Label("Ver perfil", systemImage: "person") }
    }

    private func nombreRol(_ r: String) -> String {
        switch r { case "ADMIN": return "Admin"; case "COLABORADOR": return "Colaborador"; default: return "Usuario" }
    }
    private func colorRol(_ r: String) -> Color {
        switch r { case "ADMIN": return Tema.rosa; case "COLABORADOR": return Tema.celeste; default: return Tema.lila }
    }

    private func estado(_ u: Usuario, activo: Bool) async {
        await enviar("PUT", "/api/admin/usuarios/\(u.id)/estado", ["activo": activo], texto: activo ? "Cuenta activada" : "Cuenta desactivada")
    }

    private func cargar() async {
        do {
            usuarios = try await API.get("/api/admin/usuarios")
            error = nil
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        cargando = false
    }

    private func enviar(_ metodo: String, _ ruta: String, _ cuerpo: [String: Any]?, texto: String) async {
        guard !trabajando else { return }
        trabajando = true
        defer { trabajando = false }
        do {
            let _: RespuestaOK = try await API.enviar(metodo, ruta, cuerpo)
            Avisos.compartido.mostrar(texto)
            await cargar()
        } catch { Avisos.compartido.error(error) }
    }
}
