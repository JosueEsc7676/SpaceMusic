import SwiftUI

/// Administración → Conexión: por qué dirección se conectan todas las apps (Tailscale o Cloudflare).
struct ConexionVista: View {
    @State private var datos: ValorAdmin?
    @State private var modo = "tailscale"
    @State private var error: String?
    @State private var cargado = false

    var body: some View {
        Form {
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            Section {
                Picker("Las apps usan", selection: $modo) {
                    Text("Tailscale (principal)").tag("tailscale")
                    Text("Cloudflare (respaldo)").tag("cloudflare")
                }
                .pickerStyle(.inline)
                .labelsHidden()
                .onChange(of: modo) { _, m in if cargado { Task { await guardar(m) } } }
            } header: { Text("Por dónde se conectan las apps") } footer: {
                Text("Si una dirección se cae, elige la otra: las apps de Android e iPhone se pasan solas con la misma sesión y reciben las actualizaciones por ahí. Aunque no elijas, si la que usan no responde prueban la otra.")
            }
            if let d = datos {
                Section("Direcciones") {
                    LabeledContent("Tailscale", value: d["principal"].cadena.replacingOccurrences(of: "https://", with: ""))
                    LabeledContent("Cloudflare", value: d["disponible"].verdadero ? d["url"].cadena.replacingOccurrences(of: "https://", with: "") : "No está activa")
                }
                .font(.footnote)
            }
        }
        .navigationTitle("Conexión")
        .navigationBarTitleDisplayMode(.inline)
        .task { await cargar() }
    }

    private func cargar() async {
        do {
            let d: ValorAdmin = try await API.get("/api/admin/conexion")
            datos = d
            modo = d["modo"].cadena == "cloudflare" ? "cloudflare" : "tailscale"
            DispatchQueue.main.async { cargado = true }
        } catch { self.error = error.localizedDescription }
    }

    private func guardar(_ m: String) async {
        do {
            let d: ValorAdmin = try await API.enviar("PUT", "/api/admin/conexion", ["modo": m])
            datos = d
            error = nil
            Avisos.compartido.mostrar(m == "cloudflare" ? "Las apps usarán Cloudflare" : "Las apps usarán Tailscale")
            await Respaldo.recordar()
        } catch { self.error = error.localizedDescription }
    }
}
