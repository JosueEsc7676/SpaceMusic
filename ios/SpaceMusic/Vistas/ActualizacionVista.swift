import SwiftUI

/// Versión nueva de la app: qué trae y cómo instalarla desde el iPhone.
struct ActualizacionVista: View {
    @Environment(\.dismiss) private var cerrar
    @State private var act = Actualizaciones.compartida
    @State private var aviso: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("Instalada", value: act.versionActual)
                    if let v = act.disponible {
                        LabeledContent("Nueva", value: v.nombre).foregroundStyle(Tema.celeste)
                    }
                    Button {
                        Task { await act.revisar(avisar: false); aviso = act.disponible == nil ? (act.ultimoError ?? "Ya tienes la última versión") : nil }
                    } label: {
                        HStack { Text("Buscar actualizaciones"); Spacer(); if act.revisando { ProgressView() } }
                    }
                    .disabled(act.revisando)
                }
                if let v = act.publicada {
                    if let n = v.notas, !n.isEmpty {
                        Section("Qué cambia") { Text(n).font(.subheadline) }
                    }
                    Section {
                        Button { Task { await abrir(.sidestore) } } label: { Label("Instalar con SideStore", systemImage: "arrow.down.app") }
                        Button { Task { await abrir(.altstore) } } label: { Label("Instalar con AltStore", systemImage: "arrow.down.app") }
                        Button { act.descargar() } label: { Label("Descargar actualización", systemImage: "square.and.arrow.down") }
                            .disabled(act.descargando)
                        if act.descargando {
                            ProgressView(value: act.progreso) { Text("Descargando… \(Int(act.progreso * 100)) %") }
                            Button("Cancelar descarga", role: .cancel) { act.cancelarDescarga() }
                        }
                        if let archivo = act.archivo {
                            ShareLink(item: archivo) { Label("Guardar en Archivos o compartir IPA", systemImage: "square.and.arrow.up") }
                            Text("Descarga terminada. Guarda el archivo y ábrelo con tu instalador.").font(.caption)
                        }
                    } header: {
                        Text("Instalar")
                    } footer: {
                        Text("El iPhone no permite que una app se instale sola: el .ipa se firma con tu Apple ID. Con SideStore o AltStore en el teléfono basta un toque y no pierdes tus datos. Sin ellos, descarga el .ipa y ábrelo en Sideloadly desde la computadora como siempre.")
                    }
                }
                if let error = act.ultimoError { Section { Text(error).foregroundStyle(Tema.peligro) } }
                if let aviso { Section { Text(aviso).foregroundStyle(Tema.secundario) } }
                Section {
                    NavigationLink("Ver novedades de SpaceMusic") { NovedadesVista() }
                }
            }
            .navigationTitle("Actualización")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { cerrar() } } }
            .task { await act.revisar(avisar: false) }
        }
    }

    private func abrir(_ i: Actualizaciones.Instalador) async {
        if !(await act.instalar(con: i)) {
            aviso = i == .sidestore ? "SideStore no está instalado en este iPhone." : "AltStore no está instalado en este iPhone."
        }
    }
}
