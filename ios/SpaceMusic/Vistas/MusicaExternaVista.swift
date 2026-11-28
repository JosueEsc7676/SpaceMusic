import SwiftUI

/// Administración → YouTube Music: escuchar en streaming cualquier canción de YouTube Music desde el buscador.
/// Usa bibliotecas no oficiales (ytmusicapi y yt-dlp) en el servidor. Solo para administradores.
/// (Antes: Internet Archive, Audius, Jamendo y radios; se quitaron en la 3.33.)
struct MusicaExternaVista: View {
    @State private var datos: ValorAdmin?
    @State private var activo = false
    @State private var error: String?
    @State private var cargado = false

    var body: some View {
        Form {
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            Section {
                Toggle("Activar YouTube Music", isOn: $activo).tint(Tema.celeste)
                    .onChange(of: activo) { _, v in if cargado { Task { await guardar(["activo": v, "fuentes": ["youtube": true]]) } } }
            } footer: {
                Text("Aparece en el buscador debajo de tus resultados, solo para administradores. Tus canciones no cambian; con «＋», favoritos, una playlist o descargar, la canción se guarda en el servidor (carpeta «Internet»).")
            }
            if datos?["youtubeInstalado"].verdadero == false {
                Section { Text("ytmusicapi no está instalado en el servidor.").foregroundStyle(Tema.peligro) }
            }
        }
        .navigationTitle("YouTube Music")
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
        activo = d["activo"].verdadero && d["fuentes"]["youtube"].verdadero
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
