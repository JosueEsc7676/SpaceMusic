import SwiftUI

/// Iniciar sesión, crear cuenta y recuperar contraseña.
struct Entrada: View {
    /// Las mismas pantallas de la web: entrar, registro, olvidé, restablecer (enlace del correo),
    /// reenviar verificación e instalación del primer administrador (setup.html).
    enum Modo { case entrar, registrar, olvide, restablecer, verificar, instalar }

    @State private var sesion = Sesion.compartida
    @State private var modo: Modo = .entrar
    @State private var usuario = ""
    @State private var correo = ""
    @State private var nombre = ""
    @State private var clave = ""
    @State private var clave2 = ""
    @State private var enlace = ""
    @State private var claveInstalacion = ""
    @State private var necesitaAdmin = false
    @State private var requiereClaveInstalacion = false
    @State private var cargando = false
    @State private var error: String?
    @State private var aviso: String?
    @State private var editandoServidor = false
    @State private var servidor = ""
    @FocusState private var foco: Campo?

    enum Campo { case usuario, correo, nombre, clave }

    var body: some View {
        ZStack {
            Tema.fondo.ignoresSafeArea()
            Circle().fill(Tema.lila.opacity(0.25)).frame(width: 380).blur(radius: 120).offset(x: -120, y: -300)
            Circle().fill(Tema.celeste.opacity(0.2)).frame(width: 340).blur(radius: 120).offset(x: 140, y: -180)

            ScrollView {
                VStack(spacing: 26) {
                    VStack(spacing: 10) {
                        Image(systemName: "waveform.circle.fill")
                            .font(.system(size: 64))
                            .foregroundStyle(Tema.aurora)
                        Text("SpaceMusic").font(.system(size: 34, weight: .heavy, design: .rounded))
                        Text(subtitulo).font(.subheadline).foregroundStyle(Tema.secundario).multilineTextAlignment(.center)
                    }
                    .padding(.top, 60)

                    if let m = sesion.motivoSalida, modo == .entrar {
                        Text(m).font(.subheadline).foregroundStyle(Tema.rosa).multilineTextAlignment(.center)
                    }

                    VStack(spacing: 12) {
                        switch modo {
                        case .entrar:
                            campo("Usuario o correo", texto: $usuario, campo: .usuario, tipo: .username)
                            campoClave
                        case .registrar:
                            campo("Correo", texto: $correo, campo: .correo, tipo: .emailAddress)
                            campo("Usuario", texto: $usuario, campo: .usuario, tipo: .username)
                            campo("Tu nombre (opcional)", texto: $nombre, campo: .nombre, tipo: .name)
                            campoClave
                            Text("Mínimo 8 caracteres. Un administrador aprobará tu cuenta.")
                                .font(.caption).foregroundStyle(Tema.secundario)
                        case .olvide, .verificar:
                            campo("Correo de tu cuenta", texto: $correo, campo: .correo, tipo: .emailAddress)
                        case .restablecer:
                            campo("Enlace o código del correo", texto: $enlace, campo: .usuario, tipo: .oneTimeCode)
                            campoClave
                            campoSegura("Repite la contraseña", texto: $clave2)
                            Text("Pega el enlace completo que te llegó por correo para cambiar la contraseña.")
                                .font(.caption).foregroundStyle(Tema.secundario)
                        case .instalar:
                            campo("Correo", texto: $correo, campo: .correo, tipo: .emailAddress)
                            campo("Usuario", texto: $usuario, campo: .usuario, tipo: .username)
                            campo("Tu nombre (opcional)", texto: $nombre, campo: .nombre, tipo: .name)
                            campoClave
                            if requiereClaveInstalacion {
                                campoSegura("Clave de instalación (app.setup-key)", texto: $claveInstalacion)
                            } else {
                                Text("El servidor no tiene clave de instalación: solo puede crearse el administrador desde el propio servidor.")
                                    .font(.caption).foregroundStyle(Tema.secundario)
                            }
                        }
                    }

                    if let error {
                        Text(error).font(.subheadline).foregroundStyle(Tema.peligro).multilineTextAlignment(.center)
                    }
                    if let aviso {
                        Text(aviso).font(.subheadline).foregroundStyle(Tema.celeste).multilineTextAlignment(.center)
                    }

                    Button(action: enviar) {
                        Group {
                            if cargando { ProgressView().tint(.black) } else { Text(textoBoton) }
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(BotonPrincipal())
                    .disabled(cargando)

                    VStack(spacing: 14) {
                        switch modo {
                        case .entrar:
                            Button("¿Olvidaste tu contraseña?") { cambiar(.olvide) }
                            Button("Crear una cuenta") { cambiar(.registrar) }
                            Button("Reenviar correo de verificación") { cambiar(.verificar) }
                                .font(.footnote).foregroundStyle(Tema.secundario)
                            if necesitaAdmin {
                                Button("Configurar el servidor: crear administrador") { cambiar(.instalar) }
                                    .foregroundStyle(Tema.lila)
                            }
                        case .olvide:
                            Button("Ya tengo el enlace del correo") { cambiar(.restablecer) }
                            Button("Ya tengo cuenta") { cambiar(.entrar) }
                        case .registrar, .restablecer, .verificar, .instalar:
                            Button("Ya tengo cuenta") { cambiar(.entrar) }
                        }
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Tema.texto)

                    Button {
                        servidor = sesion.servidor
                        editandoServidor = true
                    } label: {
                        Label(sesion.servidor.replacingOccurrences(of: "https://", with: ""), systemImage: "server.rack")
                            .font(.caption)
                            .foregroundStyle(Tema.secundario)
                    }
                    .padding(.top, 20)
                }
                .padding(.horizontal, 28)
                .padding(.bottom, 40)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .task(id: sesion.servidor) { await consultarEstado() }
        .alert("Servidor", isPresented: $editandoServidor) {
            TextField("https://…", text: $servidor)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .autocorrectionDisabled()
            Button("Guardar") { guardarServidor() }
            Button("Usar el de siempre") { sesion.servidor = Sesion.servidorPorDefecto }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Dirección de tu servidor de SpaceMusic. Ejemplo: \(Sesion.servidorPorDefecto)")
        }
    }

    private var subtitulo: String {
        switch modo {
        case .entrar: return "Tu música, en tu servidor."
        case .registrar: return "Crea tu cuenta"
        case .olvide: return "Te enviaremos un enlace para cambiar tu contraseña."
        case .restablecer: return "Elige una contraseña nueva."
        case .verificar: return "Te enviaremos otro correo para verificar tu cuenta."
        case .instalar: return "Crea la primera cuenta de administrador de este servidor."
        }
    }

    private var textoBoton: String {
        switch modo {
        case .entrar: return "Entrar"
        case .registrar: return "Crear cuenta"
        case .olvide: return "Enviar enlace"
        case .restablecer: return "Cambiar contraseña"
        case .verificar: return "Reenviar correo"
        case .instalar: return "Crear administrador"
        }
    }

    private func campo(_ titulo: String, texto: Binding<String>, campo: Campo, tipo: UITextContentType) -> some View {
        TextField(titulo, text: texto)
            .textContentType(tipo)
            .keyboardType(tipo == .emailAddress ? .emailAddress : .default)
            .textInputAutocapitalization(tipo == .name ? .words : .never)
            .autocorrectionDisabled()
            .focused($foco, equals: campo)
            .submitLabel(.next)
            .onSubmit { foco = .clave }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(Tema.tarjeta))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Tema.linea))
    }

    private func campoSegura(_ titulo: String, texto: Binding<String>) -> some View {
        SecureField(titulo, text: texto)
            .textContentType(.newPassword)
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(Tema.tarjeta))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Tema.linea))
    }

    /// Token del enlace del correo (`…/reset-password.html?token=…`) o el código pegado tal cual.
    static func tokenDeEnlace(_ texto: String) -> String {
        let t = texto.trimmingCharacters(in: .whitespacesAndNewlines)
        if let partes = URLComponents(string: t), let token = partes.queryItems?.first(where: { $0.name == "token" })?.value, !token.isEmpty {
            return token
        }
        return t
    }

    private func consultarEstado() async {
        struct Estado: Decodable {
            var necesitaAdmin: Bool
            var requiereClave: Bool
            init(from decoder: Decoder) throws {
                let c = try decoder.campos()
                necesitaAdmin = c.bool("necesitaAdmin") ?? false
                requiereClave = c.bool("requiereClave") ?? false
            }
        }
        guard let e: Estado = try? await API.get("/api/auth/estado") else { return }
        necesitaAdmin = e.necesitaAdmin
        requiereClaveInstalacion = e.requiereClave
    }

    private var campoClave: some View {
        SecureField("Contraseña", text: $clave)
            .textContentType([.registrar, .restablecer, .instalar].contains(modo) ? .newPassword : .password)
            .focused($foco, equals: .clave)
            .submitLabel(.go)
            .onSubmit(enviar)
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(Tema.tarjeta))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Tema.linea))
    }

    private func cambiar(_ m: Modo) {
        withAnimation { modo = m }
        error = nil
        aviso = nil
    }

    private func guardarServidor() {
        var s = servidor.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        if !s.isEmpty && !s.hasPrefix("http") { s = "https://" + s }
        if URL(string: s) != nil, !s.isEmpty { sesion.servidor = s }
    }

    private func enviar() {
        error = nil
        aviso = nil
        foco = nil
        cargando = true
        Task {
            defer { cargando = false }
            do {
                switch modo {
                case .entrar:
                    let r: RespuestaSesion = try await API.enviar("POST", "/api/auth/login",
                                                                  ["usuario": usuario.trimmingCharacters(in: .whitespaces), "password": clave])
                    guard let token = r.token, let u = r.usuario else { throw ErrorAPI(mensaje: "No se pudo entrar", estado: 0) }
                    clave = ""
                    sesion.motivoSalida = nil
                    sesion.guardar(token: token, usuario: u)
                case .registrar:
                    let r: RespuestaOK = try await API.enviar("POST", "/api/auth/register", [
                        "email": correo.trimmingCharacters(in: .whitespaces),
                        "username": usuario.trimmingCharacters(in: .whitespaces),
                        "password": clave,
                        "nombreDisplay": nombre.trimmingCharacters(in: .whitespaces),
                    ])
                    clave = ""
                    cambiar(.entrar)
                    aviso = r.message ?? "Cuenta creada. Un administrador la revisará y te avisaremos por correo."
                case .olvide:
                    let r: RespuestaOK = try await API.enviar("POST", "/api/auth/forgot-password",
                                                              ["email": correo.trimmingCharacters(in: .whitespaces)])
                    aviso = r.message ?? "Si el correo está registrado, recibirás un enlace."
                case .verificar:
                    let r: RespuestaOK = try await API.enviar("POST", "/api/auth/resend-verification",
                                                              ["email": correo.trimmingCharacters(in: .whitespaces)])
                    aviso = r.message ?? "Si la cuenta existe y no está verificada, te enviamos un correo."
                case .restablecer:
                    let token = Entrada.tokenDeEnlace(enlace)
                    guard !token.isEmpty else { throw ErrorAPI(mensaje: "Pega el enlace que te llegó por correo", estado: 0) }
                    guard clave.count >= 8 else { throw ErrorAPI(mensaje: "La contraseña debe tener al menos 8 caracteres", estado: 0) }
                    guard clave == clave2 else { throw ErrorAPI(mensaje: "Las contraseñas no coinciden", estado: 0) }
                    let _: RespuestaOK = try await API.enviar("POST", "/api/auth/reset-password", ["token": token, "password": clave])
                    clave = ""; clave2 = ""; enlace = ""
                    cambiar(.entrar)
                    aviso = "Contraseña actualizada. Inicia sesión."
                case .instalar:
                    let r: RespuestaSesion = try await API.enviar("POST", "/api/auth/setup-admin", [
                        "email": correo.trimmingCharacters(in: .whitespaces),
                        "username": usuario.trimmingCharacters(in: .whitespaces),
                        "nombreDisplay": nombre.trimmingCharacters(in: .whitespaces),
                        "password": clave,
                        "setupKey": claveInstalacion,
                    ])
                    guard let token = r.token, let u = r.usuario else { throw ErrorAPI(mensaje: r.message ?? "No se pudo crear el administrador", estado: 0) }
                    clave = ""; claveInstalacion = ""
                    sesion.guardar(token: token, usuario: u)
                }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
