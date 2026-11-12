import SwiftUI

/// Barras animadas que siguen el nivel de la música (el «Visualizador» de la web en Reproduciendo ahora).
struct Visualizador: View {
    var barras = 5
    var alto: CGFloat = 18
    @State private var r = Reproductor.compartido
    @State private var audio = ProcesadorAudio.compartido

    var body: some View {
        if audio.visualizador {
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !r.sonando)) { contexto in
                let t = contexto.date.timeIntervalSinceReferenceDate
                let nivel = r.sonando ? Double(audio.nivel) : 0
                HStack(alignment: .bottom, spacing: 2.5) {
                    ForEach(0..<barras, id: \.self) { i in
                        // Cada barra oscila a su ritmo y crece con el nivel real de la música.
                        let vaiven = 0.55 + 0.45 * abs(sin(t * (3.1 + Double(i) * 1.7) + Double(i)))
                        Capsule()
                            .fill(.white.opacity(0.85))
                            .frame(width: 3, height: max(3, alto * min(1, nivel * vaiven * 1.4)))
                    }
                }
                .frame(height: alto, alignment: .bottom)
            }
            .accessibilityHidden(true)
        }
    }
}

/// Ecualizador de 5 bandas con los mismos ajustes predefinidos de la web.
struct EcualizadorVista: View {
    @State private var audio = ProcesadorAudio.compartido
    private let nombres = ["60 Hz", "230 Hz", "910 Hz", "3,6 kHz", "14 kHz"]

    var body: some View {
        Form {
            Section {
                Toggle("Ecualizador", isOn: $audio.ecualizador).tint(Tema.celeste)
            } footer: {
                Text("Se aplica a todo lo que suena en SpaceMusic, también con la pantalla apagada y en las descargas.")
            }
            Section("Ajustes predefinidos") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(ProcesadorAudio.presets, id: \.nombre) { p in
                            let activo = audio.ganancias == p.ganancias
                            Button(p.nombre) { audio.aplicarPreset(p.ganancias); if !audio.ecualizador { audio.ecualizador = true } }
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 12).padding(.vertical, 7)
                                .foregroundStyle(activo ? Color.black : Tema.texto)
                                .background(Capsule().fill(activo ? Color.white : Tema.tarjeta))
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            Section("Bandas") {
                ForEach(0..<5, id: \.self) { i in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(nombres[i])
                            Spacer()
                            Text(String(format: "%+.0f dB", audio.ganancias[i])).monospacedDigit().foregroundStyle(Tema.secundario)
                        }
                        .font(.subheadline)
                        Slider(value: Binding(get: { audio.ganancias[i] }, set: { v in
                            var g = audio.ganancias; g[i] = v.rounded(); audio.ganancias = g
                        }), in: -12...12, step: 1)
                        .tint(Tema.celeste)
                        .accessibilityLabel(nombres[i])
                    }
                }
                Button("Restablecer (plano)") { audio.aplicarPreset([0, 0, 0, 0, 0]) }
            }
            .disabled(!audio.ecualizador)
        }
        .navigationTitle("Ecualizador")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Ajustes de reproducción de la web que viven en el iPhone.
struct AjustesReproduccion: View {
    @State private var audio = ProcesadorAudio.compartido
    @State private var descargas = Descargas.compartido
    @AppStorage("acento") private var acento = Tema.acentoPorDefecto
    @State private var guardar = Descargas.compartido.guardarAlEscuchar

    var body: some View {
        Toggle(isOn: $guardar) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Guardar lo que escuchas")
                Text("Para oírlo sin conexión (\(descargas.automaticas.count) de \(Descargas.limiteAutomaticas))").font(.caption).foregroundStyle(Tema.secundario)
            }
        }
        .onChange(of: guardar) { _, v in descargas.guardarAlEscuchar = v }
        if !descargas.automaticas.isEmpty {
            Button("Borrar lo guardado al escuchar", role: .destructive) { descargas.borrarAutomaticas() }
        }
        Toggle("Visualizador", isOn: $audio.visualizador)
        NavigationLink { EcualizadorVista() } label: {
            LabeledContent("Ecualizador", value: audio.ecualizador ? "Activado" : "Desactivado")
        }
        VStack(alignment: .leading, spacing: 10) {
            Text("Color de la app")
            HStack(spacing: 12) {
                ForEach(Tema.acentos, id: \.nombre) { a in
                    Button { acento = a.nombre } label: {
                        Circle().fill(a.color).frame(width: 30, height: 30)
                            .overlay(Circle().strokeBorder(Color.white, lineWidth: acento == a.nombre ? 3 : 0).padding(-4))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(a.nombre)
                    .accessibilityAddTraits(acento == a.nombre ? .isSelected : [])
                }
            }
            .padding(.vertical, 4)
        }
    }
}
