import SwiftUI

/// Iniciar sesión, crear cuenta y recuperar contraseña (en blanco y negro, con el logo de la app).
/// El servidor es siempre el de SpaceMusic: ya no se muestra ni se cambia desde aquí.
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
    @State private var verClave = false
    @State private var sacudida: CGFloat = 0
    @State private var aparecer = false
    @FocusState private var foco: Campo?

    enum Campo { case usuario, correo, nombre, clave, clave2, extra }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            // Luz suave en blanco y negro detrás del logo
            Circle().fill(Color.white.opacity(0.07)).frame(width: 420).blur(radius: 110).offset(y: -300)

            ScrollView {
                VStack(spacing: 28) {
                    VStack(spacing: 14) {
                        // El logo aparece con sus ondas creciendo y después se mueven suave, como un ecualizador
                        LogoSpaceMusic(tamano: 84, disco: aparecer ? 1 : 0.5,
                                       ondas: Array(repeating: aparecer ? 1 : 0, count: 7), vivo: true)
                            .shadow(color: .white.opacity(0.14), radius: 24)
                        Text("SpaceMusic")
                            .font(.system(size: 32, weight: .heavy, design: .rounded))
                            .foregroundStyle(.white)
                        Text(subtitulo)
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.6))
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 56)
                    .opacity(aparecer ? 1 : 0)

                    if let m = sesion.motivoSalida, modo == .entrar {
                        mensaje(m, icono: "info.circle", color: .white.opacity(0.85))
                    }

                    VStack(spacing: 12) {
                        switch modo {
                        case .entrar:
                            campo("Usuario o correo", icono: "person", texto: $usuario, campo: .usuario, tipo: .username)
                            campoClave
                            HStack {
                                Spacer()
                                Button("¿Olvidaste tu contraseña?") { cambiar(.olvide) }
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(.white.opacity(0.7))
                            }
                        case .registrar:
                            campo("Correo", icono: "envelope", texto: $correo, campo: .correo, tipo: .emailAddress)
                            campo("Usuario", icono: "person", texto: $usuario, campo: .usuario, tipo: .username)
                            campo("Tu nombre (opcional)", icono: "person.text.rectangle", texto: $nombre, campo: .nombre, tipo: .name)
                            campoClave
                            nota("Mínimo 8 caracteres. Un administrador aprobará tu cuenta.")
                        case .olvide, .verificar:
                            campo("Correo de tu cuenta", icono: "envelope", texto: $correo, campo: .correo, tipo: .emailAddress)
                        case .restablecer:
                            campo("Enlace o código del correo", icono: "link", texto: $enlace, campo: .usuario, tipo: .oneTimeCode)
                            campoClave
                            campoSegura("Repite la contraseña", texto: $clave2, campo: .clave2)
                            nota("Pega el enlace completo que te llegó por correo para cambiar la contraseña.")
                        case .instalar:
                            campo("Correo", icono: "envelope", texto: $correo, campo: .correo, tipo: .emailAddress)
                            campo("Usuario", icono: "person", texto: $usuario, campo: .usuario, tipo: .username)
                            campo("Tu nombre (opcional)", icono: "person.text.rectangle", texto: $nombre, campo: .nombre, tipo: .name)
                            campoClave
                            if requiereClaveInstalacion {
                                campoSegura("Clave de instalación", texto: $claveInstalacion, campo: .extra)
                            } else {
                                nota("Este servidor no tiene clave de instalación: el administrador solo puede crearse desde el propio servidor.")
                            }
                        }
                    }
                    .modifier(Sacudir(veces: sacudida))

                    if let error { mensaje(error, icono: "exclamationmark.circle", color: Tema.peligro) }
                    if let aviso { mensaje(aviso, icono: "checkmark.circle", color: .white) }

                    Button(action: enviar) {
                        ZStack {
                            Text(textoBoton).opacity(cargando ? 0 : 1)
                            if cargando { ProgressView().tint(.black) }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(BotonPrincipal())
                    .disabled(cargando || !listo)
                    .opacity(listo ? 1 : 0.45)
                    .animation(.easeOut(duration: 0.15), value: listo)

                    VStack(spacing: 16) {
                        switch modo {
                        case .entrar:
                            HStack(spacing: 4) {
                                Text("¿No tienes cuenta?").foregroundStyle(.white.opacity(0.6))
                                Button("Crear una") { cambiar(.registrar) }.foregroundStyle(.white)
                            }
                            Button("Reenviar correo de verificación") { cambiar(.verificar) }
                                .font(.footnote)
                                .foregroundStyle(.white.opacity(0.5))
                            if necesitaAdmin {
                                Button("Configurar el servidor: crear administrador") { cambiar(.instalar) }
                                    .foregroundStyle(.white)
                            }
                        case .olvide:
                            Button("Ya tengo el enlace del correo") { cambiar(.restablecer) }.foregroundStyle(.white)
                            botonVolver
                        case .registrar, .restablecer, .verificar, .instalar:
                            botonVolver
                        }
                    }
                    .font(.subheadline.weight(.semibold))
                }
                .padding(.horizontal, 28)
                .padding(.bottom, 40)
                .frame(maxWidth: 460)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .task { await consultarEstado() }
        .onAppear { withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.05)) { aparecer = true } }
        .onChange(of: usuario) { error = nil }
        .onChange(of: clave) { error = nil }
        .onChange(of: correo) { error = nil }
    }

    private var subtitulo: String {
        switch modo {
        case .entrar: return "Inicia sesión para escuchar tu música."
        case .registrar: return "Crea tu cuenta"
        case .olvide: return "Te enviaremos un enlace para cambiar tu contraseña."
        case .restablecer: return "Elige una contraseña nueva."
        case .verificar: return "Te enviaremos otro correo para verificar tu cuenta."
        case .instalar: return "Crea la primera cuenta de administrador."
        }
    }

    private var textoBoton: String {
        switch modo {
        case .entrar: return "Iniciar sesión"
        case .registrar: return "Crear cuenta"
        case .olvide: return "Enviar enlace"
        case .restablecer: return "Cambiar contraseña"
        case .verificar: return "Reenviar correo"
        case .instalar: return "Crear administrador"
        }
    }

    /// El botón principal se activa cuando los datos mínimos están escritos.
    private var listo: Bool {
        let u = !usuario.trimmingCharacters(in: .whitespaces).isEmpty
        let c = !correo.trimmingCharacters(in: .whitespaces).isEmpty
        switch modo {
        case .entrar: return u && !clave.isEmpty
        case .registrar, .instalar: return u && c && !clave.isEmpty
        case .olvide, .verificar: return c
        case .restablecer: return !enlace.isEmpty && !clave.isEmpty && !clave2.isEmpty
        }
    }

    private var botonVolver: some View {
        Button { cambiar(.entrar) } label: { Label("Volver a iniciar sesión", systemImage: "chevron.left") }
            .foregroundStyle(.white.opacity(0.8))
    }

    private func mensaje(_ texto: String, icono: String, color: Color) -> some View {
        Label(texto, systemImage: icono)
            .font(.subheadline)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.white.opacity(0.06)))
            .transition(.opacity)
    }

    private func nota(_ texto: String) -> some View {
        Text(texto)
            .font(.caption)
            .foregroundStyle(.white.opacity(0.55))
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Caja de un campo: ícono a la izquierda y borde blanco cuando está activo.
    private func caja<Contenido: View>(_ icono: String, campo: Campo, @ViewBuilder _ contenido: () -> Contenido) -> some View {
        let activo = foco == campo
        return HStack(spacing: 12) {
            Image(systemName: icono)
                .font(.body)
                .foregroundStyle(.white.opacity(activo ? 0.9 : 0.45))
                .frame(width: 22)
            contenido()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 15)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(activo ? 0.1 : 0.06)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
            .strokeBorder(Color.white.opacity(activo ? 0.55 : 0.1), lineWidth: 1))
        .animation(.easeOut(duration: 0.15), value: activo)
    }

    private func campo(_ titulo: String, icono: String, texto: Binding<String>, campo: Campo, tipo: UITextContentType) -> some View {
        caja(icono, campo: campo) {
            TextField("", text: texto, prompt: Text(titulo).foregroundStyle(.white.opacity(0.4)))
                .textContentType(tipo)
                .keyboardType(tipo == .emailAddress ? .emailAddress : .default)
                .textInputAutocapitalization(tipo == .name ? .words : .never)
                .autocorrectionDisabled()
                .focused($foco, equals: campo)
                .submitLabel(.next)
                .onSubmit { foco = .clave }
                .foregroundStyle(.white)
        }
    }

    private func campoSegura(_ titulo: String, texto: Binding<String>, campo: Campo) -> some View {
        caja("lock", campo: campo) {
            SecureField("", text: texto, prompt: Text(titulo).foregroundStyle(.white.opacity(0.4)))
                .textContentType(.newPassword)
                .focused($foco, equals: campo)
                .foregroundStyle(.white)
        }
    }

    private var campoClave: some View {
        caja("lock", campo: .clave) {
            Group {
                if verClave {
                    TextField("", text: $clave, prompt: Text("Contraseña").foregroundStyle(.white.opacity(0.4)))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } else {
                    SecureField("", text: $clave, prompt: Text("Contraseña").foregroundStyle(.white.opacity(0.4)))
                }
            }
            .textContentType([.registrar, .restablecer, .instalar].contains(modo) ? .newPassword : .password)
            .focused($foco, equals: .clave)
            .submitLabel(.go)
            .onSubmit { if listo { enviar() } }
            .foregroundStyle(.white)
            Button {
                verClave.toggle()
                foco = .clave
            } label: {
                Image(systemName: verClave ? "eye.slash" : "eye")
                    .foregroundStyle(.white.opacity(0.5))
            }
            .accessibilityLabel(verClave ? "Ocultar contraseña" : "Mostrar contraseña")
        }
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

    private func cambiar(_ m: Modo) {
        foco = nil
        withAnimation(.easeInOut(duration: 0.25)) { modo = m }
        error = nil
        aviso = nil
    }

    private func fallo(_ texto: String) {
        withAnimation(.easeOut(duration: 0.2)) { error = texto }
        withAnimation(.linear(duration: 0.4)) { sacudida += 1 }
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }

    private func enviar() {
        guard listo, !cargando else { return }
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
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
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
                fallo(error.localizedDescription)
            }
        }
    }
}

/// Sacude los campos de lado a lado cuando algo sale mal (como el código de desbloqueo del iPhone).
private struct Sacudir: GeometryEffect {
    var veces: CGFloat
    var animatableData: CGFloat {
        get { veces }
        set { veces = newValue }
    }
    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 8 * sin(veces * .pi * 4), y: 0))
    }
}
