import SwiftUI

/// Personalizar la pantalla bloqueada: posición, tipografía (5), tamaño, color, desordenada, verso siguiente,
/// mayúsculas, desenfoque y sombra de la portada, o «Original» (solo la portada, sin nada encima).
/// Se ve una vista previa con la portada que suena; al cerrar se aplica.
struct EstiloBloqueoVista: View {
    @Environment(\.dismiss) private var cerrar
    @State private var r = Reproductor.compartido
    @State private var e = Reproductor.compartido.estiloBloqueo
    @State private var previa: UIImage?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
            // Vista previa fija arriba: al deslizar las opciones nunca se pierde de vista
            Group {
                if let previa {
                    Image(uiImage: previa).resizable().scaledToFill()
                } else {
                    ZStack {
                        Color.white.opacity(0.06)
                        Text("Reproduce una canción para ver la vista previa").font(.caption).foregroundStyle(Tema.secundario)
                            .multilineTextAlignment(.center).padding()
                    }
                }
            }
            .frame(width: 190, height: 190)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: .black.opacity(0.45), radius: 12, y: 6)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(.ultraThinMaterial)
            .overlay(alignment: .bottom) { Divider() }
            Form {
                Section {
                    Picker("Pantalla bloqueada", selection: $e.modo) {
                        Text("Con letra").tag(EstiloBloqueo.Modo.letra)
                        Text("Original").tag(EstiloBloqueo.Modo.original)
                    }
                    .pickerStyle(.segmented)
                } footer: {
                    Text(e.modo == .original ? "Solo la portada, como antes: sin letra, sin sombra y sin desenfoque." : "La letra sincronizada sobre la portada, con el estilo que elijas.")
                }

                Section {
                    Toggle("Portada ampliada (alta resolución)", isOn: $e.pantallaCompleta)
                } footer: {
                    Text("Con letra se usa una imagen de alta resolución: toca la portada de la pantalla bloqueada para ampliarla. No se reemplazan videos entre versos. iOS controla las transiciones. En «Original», iOS 26 permite la portada animada a toda la pantalla.")
                }

                if e.modo == .letra {
                    Section("Letra") {
                        Picker("Posición", selection: $e.posicion) {
                            ForEach(EstiloBloqueo.Posicion.allCases, id: \.self) { Text(EstiloBloqueo.nombre($0)).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        if e.posicion == .libre {
                            VStack(alignment: .leading) {
                                Text("Altura · \(Int((e.altoLibre * 100).rounded())) % desde arriba")
                                Slider(value: $e.altoLibre, in: 0.08...0.92, step: 0.01)
                            }
                            VStack(alignment: .leading) {
                                Text("A los lados · \(e.ladoLibre < -0.05 ? "izquierda" : e.ladoLibre > 0.05 ? "derecha" : "centro")")
                                Slider(value: $e.ladoLibre, in: -1...1, step: 0.05)
                            }
                        }
                        VStack(alignment: .leading) {
                            Text("Ancho de la letra · \(Int((e.anchoLetra * 100).rounded())) %")
                            Slider(value: $e.anchoLetra, in: 0.55...0.96, step: 0.01)
                        }
                        VStack(alignment: .leading) {
                            Text("Inclinación · \(Int(e.giro.rounded()))°")
                            Slider(value: $e.giro, in: -15...15, step: 1)
                        }
                        Picker("Tipografía", selection: $e.fuente) {
                            ForEach(EstiloBloqueo.Fuente.allCases, id: \.self) { f in
                                Text(EstiloBloqueo.nombre(f)).font(Font(EstiloBloqueo.fuente(f, 30) as CTFont)).tag(f)
                            }
                        }
                        .pickerStyle(.navigationLink)
                        VStack(alignment: .leading) {
                            Text("Tamaño · \(Int((e.tamano * 100).rounded())) %")
                            Slider(value: $e.tamano, in: 0.7...1.5, step: 0.05)
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Color")
                            ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                            ForEach(EstiloBloqueo.ColorLetra.allCases, id: \.self) { c in
                                Button { e.color = c } label: {
                                    Circle().fill(Color(uiColor: EstiloBloqueo.uiColor(c))).frame(width: 28, height: 28)
                                        .overlay(Circle().strokeBorder(e.color == c ? Tema.celeste : Color.white.opacity(0.2), lineWidth: e.color == c ? 3 : 1))
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(EstiloBloqueo.nombre(c))
                                .accessibilityAddTraits(e.color == c ? .isSelected : [])
                            }
                            }
                            .padding(.vertical, 4)
                            }
                        }
                        VStack(alignment: .leading) {
                            Text("Transparencia · \(Int((e.opacidad * 100).rounded())) %")
                            Slider(value: $e.opacidad, in: 0.4...1, step: 0.05)
                        }
                        Picker("Alineación", selection: $e.alineacion) {
                            ForEach(EstiloBloqueo.Alineacion.allCases, id: \.self) { Text(EstiloBloqueo.nombre($0)).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        Picker("Efecto", selection: $e.efecto) {
                            ForEach(EstiloBloqueo.Efecto.allCases, id: \.self) { Text(EstiloBloqueo.nombre($0)).tag($0) }
                        }
                        Picker("Fondo de la letra", selection: $e.fondoLetra) {
                            ForEach(EstiloBloqueo.FondoLetra.allCases, id: \.self) { Text(EstiloBloqueo.nombre($0)).tag($0) }
                        }
                        Toggle("Contorno en las letras", isOn: $e.contorno)
                        VStack(alignment: .leading) {
                            Text("Interlineado · \(Int((e.interlineado * 100).rounded())) %")
                            Slider(value: $e.interlineado, in: 0.8...1.4, step: 0.05)
                        }
                        VStack(alignment: .leading) {
                            Text("Espacio entre letras · \(Int((e.espaciado * 100).rounded()))")
                            Slider(value: $e.espaciado, in: 0...0.2, step: 0.01)
                        }
                        Toggle("Iluminar cada palabra al cantarla", isOn: $e.iluminar)
                        if e.iluminar {
                            Picker("Color de la palabra que suena", selection: $e.resaltado) {
                                Text("El mismo de la letra").tag(EstiloBloqueo.ColorLetra?.none)
                                ForEach(EstiloBloqueo.ColorLetra.allCases, id: \.self) { Text(EstiloBloqueo.nombre($0)).tag(Optional($0)) }
                            }
                        }
                        if e.posicion == .flotante {
                            Toggle("Mecer la letra mientras suena", isOn: $e.mecer)
                        }
                        Toggle("Desordenada (siempre sincronizada)", isOn: $e.desordenada)
                        Toggle("Mostrar el verso siguiente", isOn: $e.siguiente)
                        if e.siguiente {
                            VStack(alignment: .leading) {
                                Text("Tamaño del verso siguiente · \(Int((e.tamanoSiguiente * 100).rounded())) %")
                                Slider(value: $e.tamanoSiguiente, in: 0.35...0.8, step: 0.05)
                            }
                        }
                        Toggle("En mayúsculas", isOn: $e.mayusculas)
                    }
                    Section {
                        Picker("Filtro", selection: $e.filtro) {
                            ForEach(EstiloBloqueo.Filtro.allCases, id: \.self) { Text(EstiloBloqueo.nombre($0)).tag($0) }
                        }
                        VStack(alignment: .leading) {
                            Text("Desenfoque de la portada · \(Int((e.desenfoque * 100).rounded())) %")
                            Slider(value: $e.desenfoque, in: 0...1, step: 0.05)
                        }
                        VStack(alignment: .leading) {
                            Text("Sombra · \(Int((e.sombra * 100).rounded())) %")
                            Slider(value: $e.sombra, in: 0...1, step: 0.05)
                        }
                        Toggle("Bordes oscuros (viñeta)", isOn: $e.vineta)
                    } header: { Text("Portada") } footer: {
                        Text("Desenfocar y oscurecer la portada ayuda a leer la letra.")
                    }
                    if !r.letraEnBloqueo {
                        Section {
                            Button("Activar la letra en la pantalla bloqueada") { r.letraEnBloqueo = true }
                        } footer: { Text("Ahora está apagada: la portada se verá con este estilo pero sin letra.") }
                    }
                }

                Section {
                    Button("Restablecer el estilo", role: .destructive) { e = EstiloBloqueo() }
                }
            }
            }
            .navigationTitle("Pantalla bloqueada")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Listo") { r.estiloBloqueo = e; cerrar() } }
            }
            // Vista previa con una pequeña espera (los deslizadores cambian muy seguido)
            .task(id: e) {
                try? await Task.sleep(for: .milliseconds(220))
                guard !Task.isCancelled else { return }
                if let img = await r.vistaPreviaBloqueo(e) { previa = img }
            }
            .onDisappear { r.estiloBloqueo = e }
        }
        .presentationDetents([.large])
    }
}
