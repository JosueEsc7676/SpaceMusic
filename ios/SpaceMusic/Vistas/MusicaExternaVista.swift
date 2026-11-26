import SwiftUI
/// (Compilación 3.28: música gratis de internet.)

/// Administración → Música de internet: activar Internet Archive, Audius, Jamendo y radios en vivo.
/// Solo la usan administradores y colaboradores, en el buscador, debajo de los resultados del servidor.
struct MusicaExternaVista: View {
    @State private var datos: ValorAdmin?
    @State private var activo = false
    @State private var fuentes: [String: Bool] = [:]
    @State private var jamendo = ""
    @State private var error: String?
    @State private var cargado = false

    private let lista: [(String, String, String)] = [
        ("archive", "Internet Archive", "Conciertos, netlabels, discos antiguos y música libre."),
        ("audius", "Audius", "Música independiente para escuchar gratis."),
        ("jamendo", "Jamendo", "Música Creative Commons (necesita un client_id gratis)."),
        ("radio", "Radios en vivo", "Miles de radios de todo el mundo.")]

    var body: some View {
        Form {
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            Section {
                Toggle("Activar música de internet", isOn: $activo).tint(Tema.celeste)
                    .onChange(of: activo) { _, v in if cargado { Task { await guardar(["activo": v]) } } }
            } footer: {
                Text("Aparece en el buscador debajo de tus resultados, solo para administradores y colaboradores. Tus canciones no cambian; las de licencia libre se pueden guardar en el servidor con «＋».")
            }
            Section("Fuentes") {
                ForEach(lista, id: \.0) { f in
                    Toggle(isOn: Binding(get: { fuentes[f.0] ?? true }, set: { v in
                        fuentes[f.0] = v
                        Task { await guardar(["fuentes": [f.0: v]]) }
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.1)
                            Text(f.2).font(.caption).foregroundStyle(Tema.secundario)
                        }
                    }
                    .tint(Tema.celeste)
                }
            }
            Section {
                TextField(datos?["jamendoConfigurado"].verdadero == true ? "Guardado (\(datos?["jamendoId"].cadena ?? ""))" : "client_id de Jamendo", text: $jamendo)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Guardar client_id") { Task { await guardar(["jamendoId": jamendo.trimmingCharacters(in: .whitespaces)]); jamendo = "" } }
                    .disabled(jamendo.trimmingCharacters(in: .whitespaces).isEmpty)
                if let url = URL(string: "https://devportal.jamendo.com/") {
                    Link(destination: url) { Label("Obtener un client_id gratis", systemImage: "arrow.up.right.square") }
                }
            } header: { Text("Jamendo") }
        }
        .navigationTitle("Música de internet")
        .navigationBarTitleDisplayMode(.inline)
        .task { await cargar() }
    }

    private func cargar() async {
        do {
            let d: ValorAdmin = try await API.get("/api/admin/externo")
            aplicar(d)
        } catch { self.error = error.localizedDescription }
    }

    private func aplicar(_ d: ValorAdmin) {
        datos = d
        cargado = false
        activo = d["activo"].verdadero
        for f in lista { fuentes[f.0] = d["fuentes"][f.0].verdadero }
        DispatchQueue.main.async { cargado = true }
    }

    private func guardar(_ cuerpo: [String: Any]) async {
        do {
            let d: ValorAdmin = try await API.enviar("PUT", "/api/admin/externo", cuerpo)
            aplicar(d)
            Avisos.compartido.mostrar("Guardado")
        } catch { self.error = error.localizedDescription }
    }
}
