import SwiftUI

/// Fila de otro dispositivo de la cuenta (en «Dispositivos»).
struct FilaDispositivoRemoto: View {
    let d: DispositivoRemoto
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: ControlRemotoVista.icono(d.plataforma))
                .font(.title3)
                .frame(width: 40, height: 40)
                .foregroundStyle(d.sonando ? Color.black : Tema.celeste)
                .background(RoundedRectangle(cornerRadius: 12).fill(d.sonando ? Tema.celeste : Tema.celeste.opacity(0.12)))
            VStack(alignment: .leading, spacing: 3) {
                Text(d.nombre).font(.headline).lineLimit(1)
                Text(d.cancion.map { "\(d.sonando ? "Sonando" : "En pausa"): \($0.nombre) · \($0.artista)" } ?? "Sin música")
                    .font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

/// Controlar otro dispositivo de la cuenta: qué suena, adelantar, anterior/reproducir/siguiente,
/// volumen, «Escuchar aquí» y «Pasar mi música». Se actualiza en vivo con lo que informa ese dispositivo.
struct ControlRemotoVista: View {
    let dispositivoId: String
    @Environment(\.dismiss) private var cerrar
    @State private var control = ControlRemoto.compartido
    @State private var reproductor = Reproductor.compartido
    @State private var ahora = Date()
    @State private var recibido = Date()
    @State private var buscando: Double?
    @State private var volumen: Double?

    private var d: DispositivoRemoto? { control.dispositivos.first { $0.id == dispositivoId } }

    static func icono(_ plataforma: String) -> String {
        switch plataforma {
        case "ios": return "iphone"
        case "android": return "smartphone"
        default: return "laptopcomputer"
        }
    }

    var body: some View {
        Group {
            if let d {
                contenido(d)
            } else {
                EstadoVista(icono: "wifi.slash", titulo: "Se desconectó", texto: "Ese dispositivo ya no está conectado con tu cuenta.")
            }
        }
        .navigationTitle(d?.nombre ?? "Dispositivo")
        .navigationBarTitleDisplayMode(.inline)
        .task { await control.cargar(); recibido = Date() }
        .onChange(of: d) { _, _ in recibido = Date() }
        .task {
            // Reloj de la pantalla: el tiempo avanza solo mientras suena allá
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                ahora = Date()
            }
        }
    }

    private func segundoAhora(_ d: DispositivoRemoto) -> Double {
        let s = d.segundo + (d.sonando ? ahora.timeIntervalSince(recibido) : 0)
        return d.duracion > 0 ? min(s, d.duracion) : s
    }

    @ViewBuilder private func contenido(_ d: DispositivoRemoto) -> some View {
        ScrollView {
            VStack(spacing: 18) {
                if let c = d.cancion {
                    Portada(ruta: c.portadaUrl, tamano: 220, radio: 16)
                    VStack(spacing: 4) {
                        Text(c.nombre).font(.title3.weight(.bold)).lineLimit(2).multilineTextAlignment(.center)
                        Text(c.artista).font(.body).foregroundStyle(Tema.secundario).lineLimit(1)
                    }
                    VStack(spacing: 4) {
                        Slider(value: Binding(get: { buscando ?? segundoAhora(d) }, set: { buscando = $0 }),
                               in: 0...max(1, d.duracion), onEditingChanged: { editando in
                            if !editando, let s = buscando {
                                Task { try? await control.ordenar(d.id, "seek", ["segundo": s]); buscando = nil }
                            }
                        })
                        .accessibilityLabel("Posición en \(d.nombre)")
                        HStack {
                            Text(formatoTiempo(buscando ?? segundoAhora(d)))
                            Spacer()
                            Text(formatoTiempo(d.duracion))
                        }
                        .font(.caption.monospacedDigit()).foregroundStyle(Tema.secundario)
                    }
                } else {
                    Text("No suena nada en \(d.nombre). Puedes pasarle tu música.")
                        .foregroundStyle(Tema.secundario).multilineTextAlignment(.center).padding(.vertical, 30)
                }

                HStack(spacing: 34) {
                    boton("backward.fill", "Anterior") { try await control.ordenar(d.id, "prev") }
                    boton(d.sonando ? "pause.circle.fill" : "play.circle.fill", d.sonando ? "Pausar" : "Reproducir", grande: true) {
                        try await control.ordenar(d.id, "toggle")
                    }
                    boton("forward.fill", "Siguiente") { try await control.ordenar(d.id, "next") }
                }
                .disabled(d.cancion == nil)

                HStack(spacing: 10) {
                    Image(systemName: "speaker.fill").foregroundStyle(Tema.secundario)
                    Slider(value: Binding(get: { volumen ?? d.volumen }, set: { volumen = $0 }), in: 0...1, onEditingChanged: { editando in
                        if !editando, let v = volumen {
                            Task { try? await control.ordenar(d.id, "volumen", ["volumen": v]); volumen = nil }
                        }
                    })
                    .accessibilityLabel("Volumen de \(d.nombre)")
                    Image(systemName: "speaker.wave.3.fill").foregroundStyle(Tema.secundario)
                }

                VStack(spacing: 10) {
                    if d.cancion != nil {
                        Button {
                            Task { await control.escucharAqui(d); cerrar() }
                        } label: { Label("Escuchar aquí", systemImage: "iphone.and.arrow.forward").frame(maxWidth: .infinity) }
                            .buttonStyle(.bordered)
                    }
                    if reproductor.actual != nil {
                        Button {
                            Task {
                                do { try await control.pasarA(d); cerrar() }
                                catch { Avisos.compartido.mostrar(error.localizedDescription) }
                            }
                        } label: { Label("Pasar mi música a \(d.nombre)", systemImage: "arrow.right.circle").frame(maxWidth: .infinity) }
                            .buttonStyle(.borderedProminent)
                    }
                }
                .padding(.top, 6)
            }
            .padding(20)
        }
    }

    private func boton(_ simbolo: String, _ etiqueta: String, grande: Bool = false, _ accion: @escaping () async throws -> Void) -> some View {
        Button {
            Task {
                do { try await accion() } catch { Avisos.compartido.mostrar(error.localizedDescription) }
            }
        } label: {
            Image(systemName: simbolo).font(grande ? .system(size: 64) : .title)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(etiqueta)
    }

    private func formatoTiempo(_ s: Double) -> String {
        guard s.isFinite, s > 0 else { return "0:00" }
        let t = Int(s)
        return "\(t / 60):" + String(format: "%02d", t % 60)
    }
}
