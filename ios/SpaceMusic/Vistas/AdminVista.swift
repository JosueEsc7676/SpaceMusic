import SwiftUI

/// Panel de administración: cuentas nuevas, colaboradores, usuarios, biblioteca y avisos.
struct AdminVista: View {
    @State private var resumen: AdminResumen?
    @State private var pendientes: [Usuario] = []
    @State private var solicitudes: [Usuario] = []
    @State private var usuarios: [Usuario] = []
    @State private var aviso = ""
    @State private var rutaAviso = ""
    @State private var mensaje: String?
    @State private var trabajando = false

    var body: some View {
        List {
            if let r = resumen {
                Section("Resumen") {
                    LabeledContent("Usuarios", value: "\(r.totalUsuarios)")
                    LabeledContent("Administradores", value: "\(r.admins)")
                    LabeledContent("Colaboradores", value: "\(r.colaboradores)")
                    LabeledContent("Cuentas desactivadas", value: "\(r.inactivos)")
                    if !r.correoConfigurado {
                        Label("El correo del servidor no está configurado", systemImage: "envelope.badge.shield.half.filled")
                            .foregroundStyle(Tema.rosa)
                    }
                }
            }

            if !pendientes.isEmpty {
                Section("Cuentas esperando aprobación") {
                    ForEach(pendientes) { u in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(u.nombre).font(.headline)
                            Text("@\(u.username) · \(u.email ?? "")").font(.caption).foregroundStyle(Tema.secundario)
                            HStack {
                                Button("Aprobar") { Task { await accion("/api/admin/cuentas/\(u.id)/aprobar") } }
                                    .buttonStyle(BotonPrincipal())
                                Button("Rechazar") { Task { await accion("/api/admin/cuentas/\(u.id)/rechazar") } }
                                    .buttonStyle(BotonSecundario())
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            if !solicitudes.isEmpty {
                Section("Quieren ser colaboradores") {
                    ForEach(solicitudes) { u in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(u.nombre).font(.headline)
                            if let m = u.motivoSolicitud, !m.isEmpty { Text("“\(m)”").font(.subheadline).foregroundStyle(Tema.secundario) }
                            HStack {
                                Button("Aprobar") { Task { await accion("/api/admin/solicitudes/\(u.id)/aprobar") } }
                                    .buttonStyle(BotonPrincipal())
                                Button("Rechazar") { Task { await accion("/api/admin/solicitudes/\(u.id)/rechazar") } }
                                    .buttonStyle(BotonSecundario())
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            Section("Biblioteca") {
                Button { Task { await accion("/api/admin/biblioteca/escanear", texto: "Buscando canciones nuevas en el servidor…") } } label: {
                    Label("Buscar canciones nuevas", systemImage: "arrow.triangle.2.circlepath")
                }
                Button { Task { await accion("/api/admin/sistema/refrescar", texto: "Biblioteca recargada") } } label: {
                    Label("Recargar la biblioteca", systemImage: "arrow.clockwise")
                }
                Button { Task { await accion("/api/admin/generos/buscar", texto: "Buscando géneros de los artistas…") } } label: {
                    Label("Buscar géneros que faltan", systemImage: "tag")
                }
            }

            Section {
                TextField("Texto del aviso (máx. 300)", text: $aviso, axis: .vertical).lineLimit(2...5)
                TextField("Enlace de la app (opcional, ej. #/novedades)", text: $rutaAviso)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Enviar a todos") { Task { await enviarAviso() } }
                    .disabled(aviso.trimmingCharacters(in: .whitespaces).isEmpty || aviso.count > 300 || trabajando)
            } header: { Text("Aviso para todos") } footer: { Text("Les llega como notificación en la campana.") }

            Section("Usuarios") {
                ForEach(usuarios) { u in filaUsuario(u) }
            }

            if let mensaje {
                Section { Text(mensaje).foregroundStyle(Tema.celeste) }
            }
        }
        .navigationTitle("Administración")
        .refreshable { await cargar() }
        .task { await cargar() }
    }

    private func filaUsuario(_ u: Usuario) -> some View {
        HStack(spacing: 12) {
            Avatar(ruta: u.fotoPerfil, nombre: u.nombre, tamano: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(u.nombre).font(.body.weight(.medium)).strikethrough(u.activo == false)
                Text("@\(u.username) · \(u.estadoCuenta ?? "")").font(.caption).foregroundStyle(Tema.secundario)
            }
            Spacer()
            Menu {
                Picker("Rol", selection: Binding(get: { u.rol }, set: { r in Task { await cambiarRol(u, r) } })) {
                    Text("Usuario").tag("USER")
                    Text("Colaborador").tag("COLABORADOR")
                    Text("Administrador").tag("ADMIN")
                }
                Button(u.activo == false ? "Activar cuenta" : "Desactivar cuenta") {
                    Task { await enviar("PUT", "/api/admin/usuarios/\(u.id)/estado", ["activo": u.activo == false]) }
                }
                if u.emailVerificado == false {
                    Button("Marcar correo como verificado") { Task { await accion("/api/admin/usuarios/\(u.id)/verificar") } }
                }
                Button("Enviar correo para cambiar contraseña") {
                    Task { await accion("/api/admin/usuarios/\(u.id)/recuperar", texto: "Correo enviado a \(u.nombre)") }
                }
                Button("Cerrar sus sesiones", role: .destructive) {
                    Task { await accion("/api/admin/usuarios/\(u.id)/cerrar-sesiones", texto: "Sesiones cerradas") }
                }
                NavigationLink(value: Destino.perfil(u.username)) { Label("Ver perfil", systemImage: "person") }
            } label: {
                Text(nombreRol(u.rol)).font(.caption.weight(.semibold))
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(Capsule().fill(Tema.tarjeta))
            }
        }
    }

    private func nombreRol(_ r: String) -> String {
        switch r {
        case "ADMIN": return "Admin"
        case "COLABORADOR": return "Colaborador"
        default: return "Usuario"
        }
    }

    private func cargar() async {
        async let r: AdminResumen? = try? API.get("/api/admin/resumen")
        async let p: [Usuario]? = try? API.get("/api/admin/cuentas/pendientes")
        async let s: [Usuario]? = try? API.get("/api/admin/solicitudes")
        async let u: [Usuario]? = try? API.get("/api/admin/usuarios")
        resumen = await r
        pendientes = await p ?? []
        solicitudes = await s ?? []
        usuarios = await u ?? []
    }

    private func accion(_ ruta: String, texto: String? = nil) async {
        await enviar("POST", ruta, nil, texto: texto)
    }

    @discardableResult
    private func enviar(_ metodo: String, _ ruta: String, _ cuerpo: [String: Any]?, texto: String? = nil) async -> Bool {
        trabajando = true
        defer { trabajando = false }
        do {
            let r: RespuestaOK = try await API.enviar(metodo, ruta, cuerpo)
            mensaje = texto ?? r.message ?? "Listo"
            Avisos.compartido.mostrar(mensaje ?? "Listo")
            await cargar()
            return true
        } catch {
            mensaje = error.localizedDescription
            return false
        }
    }

    private func cambiarRol(_ u: Usuario, _ rol: String) async {
        guard rol != u.rol else { return }
        await enviar("PUT", "/api/admin/usuarios/\(u.id)/rol", ["rol": rol], texto: "\(u.nombre) ahora es \(nombreRol(rol).lowercased())")
    }

    private func enviarAviso() async {
        var cuerpo: [String: Any] = ["texto": aviso.trimmingCharacters(in: .whitespaces)]
        let r = rutaAviso.trimmingCharacters(in: .whitespaces)
        if !r.isEmpty { cuerpo["ruta"] = r }
        if await enviar("POST", "/api/admin/aviso", cuerpo, texto: nil) { aviso = "" }
    }
}
