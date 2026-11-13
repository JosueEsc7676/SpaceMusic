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

/// Letra inmersiva con fondo de portada, cabecera compacta y controles siempre accesibles.
struct LetraAmpliadaVista: View {
    @Environment(\.dismiss) private var cerrar
    let cancion: Cancion
    @State private var r = Reproductor.compartido
    @State private var compartir = false
    @State private var dispositivos = false
    private var actual: Cancion { r.actual ?? cancion }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Portada(ruta: actual.portadaUrl, tamano: 48, radio: 10)
                VStack(alignment: .leading, spacing: 3) {
                    Text(actual.nombre).font(.headline).lineLimit(1)
                    Text(actual.artista).font(.subheadline).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
                }
                Spacer(minLength: 0)
                Button { cerrar() } label: {
                    Image(systemName: "chevron.down").font(.body.weight(.semibold)).frame(width: 44, height: 44)
                        .background(.white.opacity(0.08), in: Circle())
                }
                .accessibilityLabel("Cerrar letra ampliada")
            }
            .padding(.horizontal, 22).padding(.top, 12).padding(.bottom, 14)
            LetraCompleta(ampliada: true)
                .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.04), .init(color: .black, location: 0.94), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
                .frame(maxHeight: .infinity)
            VStack(spacing: 12) {
                ProgressView(value: r.duracion > 0 ? min(1, max(0, r.tiempo / r.duracion)) : 0).tint(.white.opacity(0.8))
                HStack {
                    Button { compartir = true } label: { Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44) }.accessibilityLabel("Compartir versos")
                    Spacer()
                    Button { r.anterior() } label: { Image(systemName: "backward.fill").frame(width: 44, height: 44) }.accessibilityLabel("Anterior")
                    Button { r.alternar() } label: { Image(systemName: r.sonando ? "pause.fill" : "play.fill").font(.title).frame(width: 64, height: 54) }.accessibilityLabel(r.sonando ? "Pausar" : "Reproducir")
                    Button { r.siguiente() } label: { Image(systemName: "forward.fill").frame(width: 44, height: 44) }.accessibilityLabel("Siguiente")
                    Spacer()
                    Button { dispositivos = true } label: { Image(systemName: "airplayaudio").frame(width: 44, height: 44) }.accessibilityLabel("Dispositivos y SyncPlay")
                }
                .font(.title3)
            }
            .padding(.horizontal, 22).padding(.top, 12).padding(.bottom, 12)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .background {
            GeometryReader { g in
                ImagenRemota(ruta: actual.portadaUrl).frame(width: g.size.width, height: g.size.height).blur(radius: 65).scaleEffect(1.2)
                    .overlay(Color.black.opacity(0.66))
            }
            .ignoresSafeArea()
        }
        .background(Color.black.ignoresSafeArea())
        .sheet(isPresented: $compartir) { CompartirVersosVista(cancion: actual) }
        .sheet(isPresented: $dispositivos) { DispositivosVista() }
        .accessibilityAction(.escape) { cerrar() }
    }
}
