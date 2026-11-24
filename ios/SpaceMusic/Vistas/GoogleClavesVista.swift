import SwiftUI

/// Soporte → Seguridad: claves de Google para activar «Continuar con Google» al iniciar sesión.
struct GoogleClavesVista: View {
    @State private var datos: ValorAdmin?
    @State private var clientId = ""
    @State private var secreto = ""
    @State private var ocupada = false
    @State private var error: String?

    var body: some View {
        Form {
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            if let d = datos {
                let g = d["google"]
                Section {
                    Label(g["configurado"].verdadero ? "Activo: el botón de Google ya aparece al iniciar sesión" : "Desactivado",
                          systemImage: g["configurado"].verdadero ? "checkmark.seal.fill" : "xmark.seal")
                        .foregroundStyle(g["configurado"].verdadero ? Color.green : Tema.secundario)
                }
                Section("Cómo obtener las claves") {
                    if let url = URL(string: d["consola"]["google"].cadena) {
                        Link(destination: url) { Label("Abrir Google Cloud Console", systemImage: "arrow.up.right.square") }
                    }
                    Text("1. Crea un proyecto y configura la «Pantalla de consentimiento de OAuth» (Externo, nombre SpaceMusic). Publica la app.")
                    Text("2. «Crear credenciales» → «ID de cliente de OAuth» → «Aplicación web».")
                    VStack(alignment: .leading, spacing: 4) {
                        Text("3. Orígenes de JavaScript autorizados:")
                        Text(g["origenJavascript"].cadena).font(.caption.monospaced()).textSelection(.enabled)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("4. URI de redireccionamiento autorizados:")
                        Text(g["redirectUri"].cadena).font(.caption.monospaced()).textSelection(.enabled)
                        Button("Copiar dirección") { UIPasteboard.general.string = g["redirectUri"].cadena; Avisos.compartido.mostrar("Copiada") }
                            .font(.caption.weight(.semibold))
                    }
                    Text("5. Copia el «ID de cliente» y el «Secreto del cliente» y pégalos abajo.")
                }
                .font(.subheadline)
                Section {
                    TextField("ID de cliente (…apps.googleusercontent.com)", text: $clientId)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField(g["secretoGuardado"].verdadero ? "Secreto (vacío = conservar el guardado)" : "Secreto del cliente", text: $secreto)
                    Button(ocupada ? "Guardando…" : "Guardar y activar") { Task { await guardar() } }
                        .disabled(ocupada || clientId.trimmingCharacters(in: .whitespaces).isEmpty)
                    if g["origen"].cadena == "soporte" {
                        Button("Quitar claves", role: .destructive) { Task { await quitar() } }
                    }
                } header: { Text("Claves") } footer: { Text("El secreto nunca se vuelve a mostrar; queda guardado solo en el servidor.") }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Iniciar con Google")
        .task { await cargar() }
    }

    private func cargar() async {
        do {
            let d: ValorAdmin = try await API.get("/api/admin/soporte/oauth")
            datos = d
            clientId = d["google"]["clientId"].cadena
        } catch { self.error = error.localizedDescription }
    }

    private func guardar() async {
        ocupada = true; error = nil
        defer { ocupada = false }
        do {
            try await API.hacer("PUT", "/api/admin/soporte/oauth/google", ["clientId": clientId.trimmingCharacters(in: .whitespaces), "secreto": secreto])
            secreto = ""
            Avisos.compartido.mostrar("Google activado")
            await cargar()
        } catch { self.error = error.localizedDescription }
    }

    private func quitar() async {
        do { try await API.hacer("DELETE", "/api/admin/soporte/oauth/google"); await cargar() }
        catch { self.error = error.localizedDescription }
    }
}
