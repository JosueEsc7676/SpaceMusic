import SwiftUI

/// Panel de administración: cuentas nuevas, colaboradores, usuarios, biblioteca y avisos.
struct AdminVista: View {
    @State private var resumen: AdminResumen?
    @State private var errorCarga: String?
    @State private var pendientes: [Usuario] = []
    @State private var solicitudes: [Usuario] = []
    @State private var usuarios: [Usuario] = []
    @State private var aviso = ""
    @State private var notas: [Int: String] = [:]
    @State private var confirmarAviso = false
    @State private var rutaAviso = ""
    @State private var mensaje: String?
    @State private var trabajando = false
    @State private var aprobandoEnlace = false
    @State private var reportesAbiertos = 0

    var body: some View {
        List {
            if let errorCarga {
                Section { Text(errorCarga).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } }
            }
            Section("Herramientas") {
                NavigationLink("Estadísticas del servidor", value: Destino.resumenAdmin)
                NavigationLink { SoporteTecnicoVista() } label: { Label("Soporte técnico", systemImage: "stethoscope") }
                NavigationLink { DescargasAdminVista() } label: { Label("Descargar música", systemImage: "arrow.down.circle") }
                NavigationLink { ReportesAdminVista() } label: {
                    HStack {
                        Label("Reportes de la comunidad", systemImage: "flag")
                        Spacer()
                        if reportesAbiertos > 0 { Chip(texto: "\(reportesAbiertos)", color: Tema.peligro) }
                    }
                }
                NavigationLink { OrganizacionAdminVista() } label: { Label("Organización y catálogo", systemImage: "square.stack.3d.up") }
                NavigationLink("Errores recientes (vista anterior)", value: Destino.soporte)
                NavigationLink("Géneros y artistas", value: Destino.generosAdmin)
                NavigationLink("Mantenimiento de biblioteca", value: Destino.bibliotecaAdmin)
                Button { aprobandoEnlace = true } label: { Label("Aprobar cuenta con el enlace del correo", systemImage: "envelope.badge") }
            }
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
                            TextField("Respuesta al solicitante (opcional)", text: Binding(get: { notas[u.id] ?? "" }, set: { notas[u.id] = $0 }))
                            HStack {
                                Button("Aprobar") { Task { await enviar("POST", "/api/admin/solicitudes/\(u.id)/aprobar", ["nota": notas[u.id] ?? ""]) } }
                                    .buttonStyle(BotonPrincipal())
                                Button("Rechazar") { Task { await enviar("POST", "/api/admin/solicitudes/\(u.id)/rechazar", ["nota": notas[u.id] ?? ""]) } }
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
                Button("Enviar a todos") { confirmarAviso = true }
                    .disabled(aviso.trimmingCharacters(in: .whitespaces).isEmpty || aviso.count > 300 || trabajando)
            } header: { Text("Aviso para todos") } footer: { Text("Les llega como notificación en la campana.") }

            Section("Usuarios") {
                // Lista completa en su propia pantalla, con búsqueda y filtros (como la tabla de la web).
                NavigationLink { UsuariosAdminVista() } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "person.3.fill").foregroundStyle(Tema.lila).frame(width: 28)
                        Text("Gestionar usuarios")
                        Spacer()
                        Text("\(usuarios.count)").foregroundStyle(Tema.secundario).monospacedDigit()
                    }
                }
            }

            if let mensaje {
                Section { Text(mensaje).foregroundStyle(Tema.celeste) }
            }
        }
        .disabled(trabajando)
        .confirmationDialog("¿Enviar este aviso a todos los usuarios?", isPresented: $confirmarAviso, titleVisibility: .visible) {
            Button("Enviar aviso") { Task { await enviarAviso() } }
        }
        .navigationTitle("Administración")
        .sheet(isPresented: $aprobandoEnlace, onDismiss: { Task { await cargar() } }) { AprobarConEnlaceHoja() }
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
                NavigationLink("Detalle y actividad", value: Destino.usuarioAdmin(u.id))
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
        if let c: ValorAdmin = try? await API.get("/api/admin/comunidad/conteos") { reportesAbiertos = c["reportesAbiertos"].entero }
        do {
            async let r: AdminResumen = API.get("/api/admin/resumen")
            async let p: [Usuario] = API.get("/api/admin/cuentas/pendientes")
            async let s: [Usuario] = API.get("/api/admin/solicitudes")
            async let u: [Usuario] = API.get("/api/admin/usuarios")
            let resultado = try await (r, p, s, u)
            resumen = resultado.0; pendientes = resultado.1; solicitudes = resultado.2; usuarios = resultado.3
            errorCarga = nil
        } catch { if !Task.isCancelled { errorCarga = error.localizedDescription } }
    }

    private func accion(_ ruta: String, texto: String? = nil) async {
        await enviar("POST", ruta, nil, texto: texto)
    }

    @discardableResult
    private func enviar(_ metodo: String, _ ruta: String, _ cuerpo: [String: Any]?, texto: String? = nil) async -> Bool {
        guard !trabajando else { return false }
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
