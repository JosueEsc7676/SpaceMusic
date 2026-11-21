import AVFoundation
import SwiftUI

/// El selector público de iOS descubre y cambia las salidas. Solo la ruta activa se muestra aquí;
/// la lista completa de iPhone, Bluetooth y AirPlay la administra el sistema.
struct DispositivosVista: View {
    @Environment(\.dismiss) private var cerrar
    @State private var salidas = AVAudioSession.sharedInstance().currentRoute.outputs
    @State private var sync = EscuchaJuntos.compartida
    @State private var control = ControlRemoto.compartido
    @State private var r = Reproductor.compartido
    private var enEste: Bool { r.remoto == nil }
    @State private var renombrando = false
    /// TVs de tu misma red wifi esperando para iniciar sesión, y el código escrito a mano.
    @State private var tvsCerca: [TvCercana] = []
    @State private var codigoTv = ""
    @State private var aprobandoTv = false
    @State private var nombreNuevo = ""
    var body: some View {
        NavigationStack {
            List {
                // Como Spotify Connect: se elige dónde suena y se sigue usando el mismo reproductor
                Section {
                    Button { Task { await control.elegirEsteIPhone(); cerrar() } } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "iphone")
                                .font(.title3).frame(width: 40, height: 40)
                                .foregroundStyle(enEste ? Color.black : Color.white)
                                .background(RoundedRectangle(cornerRadius: 12).fill(enEste ? Color.white : Color.white.opacity(0.1)))
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Este iPhone").font(.headline)
                                Text(salidas.first?.portName ?? control.nombre).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                            }
                            Spacer(minLength: 0)
                            if enEste { Image(systemName: "checkmark").font(.body.weight(.semibold)) }
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    ForEach(control.otros) { d in
                        Button { Task { await control.elegir(d); cerrar() } } label: {
                            FilaDispositivoRemoto(d: d, elegido: r.remoto?.id == d.id)
                        }
                        .buttonStyle(.plain)
                    }
                    if control.otros.isEmpty {
                        Text("Abre SpaceMusic con tu cuenta en otro teléfono, tablet o computadora y aparecerá aquí.")
                            .font(.caption).foregroundStyle(Tema.secundario)
                    }
                } header: {
                    HStack {
                        Text("Reproducir en")
                        Spacer()
                        Button("Este: \(control.nombre)") { nombreNuevo = control.nombre; renombrando = true }
                            .font(.caption).textCase(nil)
                            .accessibilityLabel("Cambiar el nombre de este dispositivo")
                    }
                } footer: {
                    Text("Al elegir otro dispositivo la música suena allá y la controlas con el mismo reproductor.")
                }
                if enEste {
                    Section("Salida de audio de este iPhone") {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Audífonos, Bluetooth y AirPlay").font(.body.weight(.semibold))
                                Text(salidas.map(\.portName).joined(separator: ", ")).font(.caption).foregroundStyle(Tema.secundario)
                            }
                            Spacer()
                            SelectorSalida().frame(width: 52, height: 52)
                                .accessibilityLabel("Mostrar dispositivos de audio disponibles")
                        }
                    }
                }
                // Iniciar sesión en la TV (Android TV / Google TV) sin escribir la contraseña con el control
                Section {
                    ForEach(tvsCerca) { tv in
                        Button { Task { await aprobarTv(tv.codigo, nombre: tv.nombre) } } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "tv").font(.title3).frame(width: 40, height: 40)
                                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.1)))
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(tv.nombre).font(.headline)
                                    Text("En tu wifi · toca para iniciar sesión").font(.caption).foregroundStyle(Tema.secundario)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right").font(.caption).foregroundStyle(Tema.secundario)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(aprobandoTv)
                    }
                    HStack {
                        TextField("Código de la TV (ABC 123)", text: $codigoTv)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .font(.body.monospaced())
                        Button("Entrar") { Task { await aprobarTv(codigoTv, nombre: nil) } }
                            .disabled(codigoLimpio.count != 6 || aprobandoTv)
                    }
                } header: { Text("Iniciar sesión en una TV") } footer: {
                    Text("Abre SpaceMusic en la TV: muestra un QR y un código. Escanéalo con la cámara, escribe el código aquí o toca tu TV si está en el mismo wifi.")
                }
                Section {
                    NavigationLink { JuntosVista() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "person.2.wave.2.fill").foregroundStyle(Tema.celeste)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("SyncPlay").font(.headline)
                                Text(sync.sala == nil ? "Inicia SyncPlay o únete con tus amigos" : "Tu SyncPlay está activo").font(.caption).foregroundStyle(Tema.secundario)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                } header: { Text("Escuchar en compañía") }
            }
            .task { await buscarTvs() }
            .navigationTitle("Dispositivos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { cerrar() } } }
            .alert("Nombre de este dispositivo", isPresented: $renombrando) {
                TextField("App iPhone", text: $nombreNuevo)
                Button("Guardar") { control.nombre = nombreNuevo }
                Button("Cancelar", role: .cancel) { }
            } message: { Text("Así lo verás en tus otros dispositivos.") }
            .task { await control.cargar() }
            .task {
                for await _ in NotificationCenter.default.notifications(named: AVAudioSession.routeChangeNotification) {
                    guard !Task.isCancelled else { return }
                    salidas = AVAudioSession.sharedInstance().currentRoute.outputs
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
    private func icono(_ tipo: AVAudioSession.Port) -> String {
        switch tipo {
        case .headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE: return "headphones"
        case .builtInSpeaker, .builtInReceiver: return "iphone"
        default: return "hifispeaker.fill"
        }
    }

    private var codigoLimpio: String { codigoTv.uppercased().filter { $0.isLetter || $0.isNumber } }

    private func buscarTvs() async {
        tvsCerca = (try? await API.get("/api/auth/tv/cercanas")) ?? []
    }

    /// Aprueba la TV: entra con tu cuenta (recibe su propia sesión; tu contraseña no viaja).
    private func aprobarTv(_ codigo: String, nombre: String?) async {
        let c = codigo.uppercased().filter { $0.isLetter || $0.isNumber }
        guard c.count == 6 else { return }
        aprobandoTv = true
        defer { aprobandoTv = false }
        do {
            let _: RespuestaOK = try await API.enviar("POST", "/api/auth/tv/vinculo/\(c)/aprobar")
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            Avisos.compartido.mostrar("Listo: \(nombre ?? "la TV") ya entró con tu cuenta")
            codigoTv = ""
            await buscarTvs()
        } catch {
            Avisos.compartido.mostrar(error.localizedDescription)
        }
    }

}

/// TV esperando para iniciar sesión en la misma red (`/api/auth/tv/cercanas`).
struct TvCercana: Decodable, Identifiable {
    let codigo: String
    let nombre: String
    var id: String { codigo }
}
