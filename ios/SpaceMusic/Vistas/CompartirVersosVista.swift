import SwiftUI

struct CompartirVersosVista: View {
    let cancion: Cancion
    @Environment(\.dismiss) private var cerrar
    @State private var letras = Letras.compartido
    @State private var elegidas: Set<Int> = []
    @State private var tarjeta: UIImage?
    @State private var preparando = false
    private var versos: [String] {
        if letras.sincronizada { return letras.lineas.filter { !$0.texto.isEmpty }.map(\.texto) }
        return letras.textoPlano.components(separatedBy: .newlines).filter { !$0.isEmpty }
    }
    var body: some View {
        NavigationStack {
            List {
                Section("Elige hasta 6 versos") {
                    ForEach(Array(versos.enumerated()), id: \.offset) { i, verso in
                        Button {
                            if elegidas.contains(i) { elegidas.remove(i) }
                            else if elegidas.count < 6 { elegidas.insert(i) }
                            tarjeta = nil
                        } label: {
                            HStack { Text(verso).foregroundStyle(Tema.texto); Spacer(); Image(systemName: elegidas.contains(i) ? "checkmark.circle.fill" : "circle").foregroundStyle(Tema.celeste) }
                        }
                    }
                }
                Section {
                    Button("Preparar imagen") { Task { await preparar() } }.disabled(elegidas.isEmpty || preparando)
                    if preparando { ProgressView() }
                    if let tarjeta {
                        Image(uiImage: tarjeta).resizable().scaledToFit()
                        ShareLink(item: Image(uiImage: tarjeta), preview: SharePreview(cancion.nombre, image: Image(uiImage: tarjeta))) { Label("Compartir versos", systemImage: "square.and.arrow.up") }
                    }
                }
            }
            .navigationTitle("Compartir versos")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { cerrar() } } }
        }
    }
    private func preparar() async {
        preparando = true; defer { preparando = false }
        let texto = elegidas.sorted().compactMap { versos.indices.contains($0) ? versos[$0] : nil }
        tarjeta = await TarjetaDedicatoria.imagen(cancion: cancion, lineas: texto, completa: false, de: nil, para: nil, mensaje: nil)
    }
}

struct LetraAmpliadaVista: View {
    @Environment(\.dismiss) private var cerrar
    let cancion: Cancion
    var body: some View {
        NavigationStack {
            LetraCompleta()
                .background(Tema.fondo)
                .navigationTitle(cancion.nombre)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { cerrar() } } }
        }
    }
}

